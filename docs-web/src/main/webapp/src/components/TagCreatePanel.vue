<script setup lang="ts">
/**
 * Create a tag without leaving the document edit view (#288).
 *
 * The reporter's own shape: "the tag form could be also some kind of panel which appears on
 * the right side (like the document preview panel), so we have a split view: document edit
 * form + tag edit form." So this is the DocumentSlideOver idiom — a right-positioned,
 * drag-resizable PrimeVue Drawer — hosting the SHARED TagForm rather than a second form.
 *
 * Two properties are load-bearing:
 *
 *  1. NON-MODAL. `modal: false` leaves the mask `pointer-events: none` (drawer/style), so the
 *     document form beside it stays visible and clickable: a split view, not a dialog over a
 *     greyed-out page.
 *  2. It renders NOTHING while closed. The Drawer's content is behind `containerVisible`, so
 *     the document edit view in its default state is byte-identical to one without this panel
 *     — which is what keeps the rich-description visual baseline (which screenshots this very
 *     view) from moving.
 *
 * What it does NOT do is save the document. The tag is created and handed back through
 * `created` for the host to fold into the tag SELECTION it is already holding; the document is
 * written when the user presses Save on the form, never behind their back.
 *
 * Permissions are collected, not applied, until the tag exists: AclEditor's deferred mode
 * emits each grant, and they are PUT to /acl immediately after PUT /tag returns an id. That
 * contract — the collection, the ordering, the in-flight snapshot — lives in
 * composables/useTagCreate.ts, shared with the tag management page's create card (#306).
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQueryClient } from '@tanstack/vue-query'
import Drawer from 'primevue/drawer'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import TagForm from './TagForm.vue'
import { type Tag } from '../api/tag'
import { queryKeys } from '../api/queryKeys'
import { useResizablePanel, type ClampCfg } from '../composables/useResizablePanel'
import { useTagCreate, DEFAULT_TAG_COLOR } from '../composables/useTagCreate'

const props = defineProps<{
  visible: boolean
  /** Seed for the Name field: the text typed into the tag picker that opened this panel. */
  initialName: string
  /** Title of the document the tag will be added to. Blank while the document is untitled. */
  documentTitle: string
  /** Every tag the caller knows about, offered as possible parents. */
  tags: Tag[]
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  /** The tag now exists on the server; the host adds it to the document's selection. */
  created: [tag: Tag]
}>()

const { t } = useI18n()
const toast = useToast()
const queryClient = useQueryClient()

// The shared create contract: the grants collected while the tag has no id, and the call that
// creates it and applies them (composables/useTagCreate.ts).
const { errorMessage, aclState, addGrant, removeGrant, reset, create } = useTagCreate()

const name = ref('')
const color = ref(DEFAULT_TAG_COLOR)
const parent = ref<string | null>(null)
const icon = ref<string | null>(null)

// The panel's own in-flight flag, covering its WHOLE save — the create AND the tail that hands
// the tag to the document and closes the panel. It is not the shared composable's to hold: a
// flag released when the request came back would leave the panel closeable while `created` had
// still not been emitted, stranding a tag that exists on the server but reaches no document.
const saving = ref(false)

// Every open starts from scratch: the panel is opened FOR a particular typed name, and a
// draft abandoned by a cancel must not come back with the next one. `immediate` covers the
// panel being mounted already visible.
watch(
  () => props.visible,
  (visible) => {
    if (!visible) return
    name.value = props.initialName
    color.value = DEFAULT_TAG_COLOR
    parent.value = null
    icon.value = null
    saving.value = false
    reset()
  },
  { immediate: true },
)

const leadTitle = computed(() => props.documentTitle.trim())

// A brand-new tag has no descendants, so — unlike the tag management page — nothing has to be
// excluded here to avoid a cycle. Meta tags are offered exactly as that page offers them.
const parentOptions = computed(() => [
  { label: t('ui.tags_page.none_root'), value: null },
  ...props.tags.map((tag) => ({ label: tag.name, value: tag.id })),
])

/** Close the panel. Used by a completed save, which is entitled to close itself. */
function close() {
  emit('update:visible', false)
}

/**
 * A close the USER asked for — the drawer's close icon, Escape, the footer Cancel. Refused
 * while a save is in flight: closing would let the host re-open the panel on a fresh typed
 * name, whose `visible` watch resets every field out from under the request still running.
 * The affordances are also withdrawn in the template, so this is the last line rather than
 * the only one.
 */
function requestClose() {
  if (saving.value) return
  close()
}

function onVisibleChange(value: boolean) {
  if (!value && saving.value) return
  emit('update:visible', value)
}

async function save() {
  if (saving.value) return
  // SNAPSHOT the whole draft before the first await. Every field below is a ref the user can
  // still edit while the request is in flight, and re-reading one after an await would apply
  // a later draft's value to the tag this call created — the second draft's grants landing on
  // the first draft's tag. The collected grants are snapshotted the same way inside `create`,
  // and nothing in the chain below reads a ref again.
  const draft = {
    name: name.value.trim(),
    color: '#' + color.value,
    parent: parent.value,
    icon: icon.value,
  }
  if (!draft.name) return

  // The flag is raised around the ENTIRE flow, not just the request: the tail below is what
  // hands the tag over, and a panel closed between the two would leave it on the server and
  // off the document.
  saving.value = true
  try {
    const outcome = await create(draft)
    // The create failed: `errorMessage` carries the server's own reason and the panel stays
    // open on the draft that produced it.
    if (!outcome) return

    await queryClient.invalidateQueries({ queryKey: queryKeys.tags() })
    toast.add(
      outcome.grantFailed
        ? { severity: 'error', summary: t('ui.acl_editor.failed_add'), life: 3000 }
        : { severity: 'success', summary: t('ui.tags_page.tag_created'), life: 2000 },
    )

    emit('created', {
      id: outcome.id,
      name: draft.name,
      color: draft.color,
      parent: draft.parent,
    })
    close()
  } finally {
    saving.value = false
  }
}

// --- Drag-resizable width, reusing the slide-over's composable ---
// The mockup's 440px, with the same envelope shape the document slide-over uses (a floor wide
// enough for the permissions row, a 90vw cap so it never exceeds the viewport).
const TAG_PANEL_CLAMP: ClampCfg = {
  defaultWidth: 440,
  minWidth: 360,
  maxWidth: 900,
  maxViewportFraction: 0.9,
}
const {
  width: panelWidth,
  startDrag: startResize,
  onKeydown: onResizeKey,
  reset: resetWidth,
} = useResizablePanel({
  storageKey: 'teedy_tag_create_panel_width',
  clamp: TAG_PANEL_CLAMP,
  invert: true,
})

// Below the drawer's responsive breakpoint the CSS keeps it near full-width and the resize
// handle is hidden — the slide-over's desktop-only resize behaviour.
const isMobile = ref(false)
let mql: MediaQueryList | null = null
function onMediaChange(e: MediaQueryListEvent | MediaQueryList) {
  isMobile.value = e.matches
}
onMounted(() => {
  mql = window.matchMedia('(max-width: 1024px)')
  isMobile.value = mql.matches
  mql.addEventListener('change', onMediaChange)
})
onBeforeUnmount(() => mql?.removeEventListener('change', onMediaChange))

const drawerStyle = computed(() => (isMobile.value ? {} : { width: panelWidth.value + 'px' }))
</script>

<template>
  <Drawer
    class="tag-create-panel"
    :visible="visible"
    position="right"
    :modal="false"
    :dismissable="false"
    :showCloseIcon="!saving"
    :pt="{ root: { style: drawerStyle } }"
    @update:visible="onVisibleChange"
  >
    <template #header>
      <span class="tag-create-title">{{ t('ui.tags_page.create_tag') }}</span>
    </template>

    <!-- Drag handle on the drawer's LEFT edge, mirroring DocumentSlideOver's. -->
    <div
      v-if="!isMobile"
      class="tag-create-resizer"
      role="separator"
      aria-orientation="vertical"
      tabindex="0"
      :aria-label="t('ui.resize_panel')"
      :aria-valuenow="panelWidth"
      aria-valuemin="360"
      aria-valuemax="900"
      :style="{ right: panelWidth + 'px' }"
      @pointerdown="startResize"
      @keydown="onResizeKey"
      @dblclick="resetWidth"
    />

    <!-- What Save will do, stated before anything is filled in: the tag is created AND put on
         this document. A document being created has no title yet (the reporter's own case),
         so the untitled wording is a real branch, not a fallback for missing data. -->
    <i18n-t v-if="leadTitle" keypath="ui.tag_panel.lead" tag="p" class="tag-create-lead" scope="global">
      <template #title><strong>{{ leadTitle }}</strong></template>
    </i18n-t>
    <p v-else class="tag-create-lead">{{ t('ui.tag_panel.lead_untitled') }}</p>

    <TagForm
      flat
      id-prefix="tag-create"
      autofocus-name
      v-model:name="name"
      v-model:color="color"
      v-model:icon="icon"
      v-model:parent="parent"
      :parent-options="parentOptions"
      :acl="aclState"
      @acl-add="addGrant"
      @acl-remove="removeGrant"
    >
      <template #permissions-hint>
        <Message
          severity="info"
          icon="pi pi-info-circle"
          :closable="false"
          class="tag-create-perm-hint"
        >
          {{ t('ui.tag_panel.no_permissions_hint') }}
        </Message>
      </template>
    </TagForm>

    <Message
      v-if="errorMessage"
      severity="error"
      :closable="false"
      class="tag-create-error"
    >
      {{ errorMessage }}
    </Message>

    <template #footer>
      <div class="tag-create-actions">
        <Button
          :label="t('save')"
          icon="pi pi-check"
          :disabled="!name.trim()"
          :loading="saving"
          @click="save"
        />
        <Button
          :label="t('cancel')"
          severity="secondary"
          text
          :disabled="saving"
          @click="requestClose"
        />
      </div>
    </template>
  </Drawer>
</template>

<style scoped>
/* Mobile / fallback width. On desktop the width is driven inline by the resize composable
   through the drawer root `:pt` style, which overrides this. */
.tag-create-panel :deep(.p-drawer) {
  width: min(440px, 90vw);
}

.tag-create-resizer {
  position: fixed;
  top: 0;
  width: 6px;
  height: 100%;
  margin-right: -3px;
  cursor: col-resize;
  z-index: 1200;
  background: transparent;
  touch-action: none;
}
.tag-create-resizer::after {
  content: '';
  position: absolute;
  top: 0;
  right: 2px;
  width: 2px;
  height: 100%;
  background: transparent;
  transition: background 0.12s;
}
.tag-create-resizer:hover::after,
.tag-create-resizer:focus-visible::after {
  background: var(--p-primary-color);
}
.tag-create-resizer:focus-visible {
  outline: none;
}

/* The header slot shares the flex `.p-drawer-header` row with the close button. */
.tag-create-title {
  display: block;
  flex: 1;
  min-width: 0;
  font-size: 1.125rem;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag-create-panel :deep(.p-drawer-close-button) {
  flex-shrink: 0;
}

.tag-create-lead {
  margin: 0 0 1rem;
  font-size: 0.8125rem;
  line-height: 1.45;
  color: var(--p-text-muted-color);
}
.tag-create-lead :deep(strong) {
  color: var(--p-text-color);
  font-weight: 600;
}

.tag-create-perm-hint {
  margin-bottom: 0.875rem;
  font-size: 0.8125rem;
}

.tag-create-error {
  margin-top: 1rem;
  font-size: 0.8125rem;
}

.tag-create-actions {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
</style>
