<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Popover from 'primevue/popover'
import Select from 'primevue/select'
import { type Tag } from '../api/tag'
import { type DocumentListItem } from '../api/document'
import TagBadge from './TagBadge.vue'
import { assignableTags, topUsedTags } from '../utils/tagQuickMenu'

/**
 * Compact right-click "tags" menu (#71). Replaces the former full-tag-tree context
 * menu, which overflowed and got cut off on instances with many tags.
 *
 * ADD  — a searchable Select over every assignable (not-yet-assigned) tag, plus a
 *        row of the most-used tags as quick-add chips (usage from `tagCounts`, which
 *        the app already fetches for the sidebar facets). Selecting either adds the
 *        tag. Bounded height, scrolls inside — never overflows the viewport.
 * REMOVE — the document's currently-assigned tags as removable chips.
 *
 * Add/remove reuse the parent's existing tag mutations (useDocumentTags) via emits;
 * this component owns no tag CRUD. Ranking/search logic lives in utils/tagQuickMenu.
 */
const { t } = useI18n()
const router = useRouter()

const props = defineProps<{
  document: DocumentListItem | null
  allTags: Tag[]
  tagCounts: Record<string, number>
}>()

const emit = defineEmits<{
  addTag: [tagId: string]
  removeTag: [tagId: string]
}>()

const popover = ref()
const tagSelect = ref()
const pendingTag = ref<string | null>(null)

// Resolved through the router (not hand-built) so the hash-history prefix and any
// future route change stay correct; empty when no document is bound.
const documentHref = computed(() =>
  props.document
    ? router.resolve({ name: 'document-view', params: { id: props.document.id } }).href
    : '',
)

const assignedTagIds = computed(
  () => new Set((props.document?.tags ?? []).map((tag) => tag.id)),
)

const assignable = computed(() => assignableTags(props.allTags, assignedTagIds.value))

// Top-5 most-used quick-add chips (falls back to first-5-by-name when no usage data).
const quickAddTags = computed(() => topUsedTags(assignable.value, props.tagCounts))

const assignedTags = computed(() => props.document?.tags ?? [])

function show(event: Event) {
  pendingTag.value = null
  popover.value?.show(event)
}

function hide() {
  popover.value?.hide()
}

// Opening the Select on the popover's `show` gives keyboard tag entry with no click
// (#171) — the filter only takes focus once that overlay is up. No-op when every tag is
// already assigned (no Select rendered).
//
// The focus is landed HERE instead of by the Select's own `autoFilterFocus` (#204).
// PrimeVue 4.5.4 defers that focus by one unguarded timer —
// `setTimeout(() => focus(this.$refs.filterInput.$el), 1)` in `onOverlayEnter` — while
// the same overlay-enter scrolls `.app-content`, which is exactly what the Popover's
// scroll handler dismisses on. When the dismissal wins that 1 ms race the Select is
// already unmounted and the timer throws `Cannot read properties of null` into the
// console. Focusing from here puts every hop behind `?.`, so a popover that goes away
// mid-open simply skips the focus instead of throwing. The popover closing is the
// expected outcome of that scroll either way.
async function onPopoverShow() {
  await nextTick()
  tagSelect.value?.show()
  // The overlay — and with it the filter input — mounts a tick later; either can already
  // be gone if the popover was dismissed in between.
  await nextTick()
  const filterInput = tagSelect.value?.$refs?.filterInput?.$el as HTMLElement | undefined
  filterInput?.focus()
}

function onSelect(tagId: string | null) {
  if (!tagId) return
  emit('addTag', tagId)
  hide()
}

function onQuickAdd(tagId: string) {
  emit('addTag', tagId)
  hide()
}

function onRemove(tagId: string) {
  emit('removeTag', tagId)
}

defineExpose({ show, hide })
</script>

<template>
  <Popover ref="popover" class="tag-quick-menu" @show="onPopoverShow">
    <div class="tqm-body">
      <!-- OPEN IN NEW TAB (#194). Right-click is claimed by this popover, so the
           browser's own "Open link in new tab" is out of reach on the surfaces that
           raise it; this is the explicit replacement. It sits ABOVE the ADD section
           deliberately — the Select's overlay auto-opens on popover show (#171) and
           would cover anything placed below it. -->
      <div v-if="document" class="tqm-section">
        <a
          class="tqm-open-link"
          :href="documentHref"
          target="_blank"
          rel="noopener"
          @click="hide"
        >
          <i class="pi pi-external-link" aria-hidden="true" />{{ t('ui.open_in_new_tab') }}
        </a>
      </div>

      <!-- ADD -->
      <div class="tqm-section">
        <span class="tqm-label">{{ t('ui.context_add_tag') }}</span>
        <Select
          v-if="assignable.length"
          ref="tagSelect"
          v-model="pendingTag"
          :options="assignable"
          optionLabel="name"
          optionValue="id"
          filter
          :filterPlaceholder="t('ui.tag_menu.search')"
          :placeholder="t('ui.tag_menu.search')"
          class="tqm-select"
          :autoFilterFocus="false"
          @update:modelValue="onSelect"
        />
        <span v-else class="tqm-empty">{{ t('ui.tag_menu.all_assigned') }}</span>

        <div v-if="quickAddTags.length" class="tqm-chips">
          <button
            v-for="tag in quickAddTags"
            :key="tag.id"
            type="button"
            class="teedy-tag tqm-chip"
            :style="{ backgroundColor: tag.color }"
            :aria-label="t('ui.tag_menu.add_named', { name: tag.name })"
            @click="onQuickAdd(tag.id)"
          >
            <i class="pi pi-plus tqm-chip-icon" aria-hidden="true" />{{ tag.name }}
          </button>
        </div>
      </div>

      <!-- REMOVE -->
      <div v-if="assignedTags.length" class="tqm-section tqm-remove">
        <span class="tqm-label">{{ t('ui.context_remove_tag') }}</span>
        <div class="tqm-assigned">
          <TagBadge
            v-for="tag in assignedTags"
            :key="tag.id"
            :name="tag.name"
            :color="tag.color"
            removable
            @remove="onRemove(tag.id)"
          />
        </div>
      </div>
    </div>
  </Popover>
</template>

<style scoped>
.tqm-body {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  /* Bounded so a document/instance with many tags scrolls inside the popover
     instead of overflowing the viewport (#71). */
  width: 15rem;
  max-height: 60vh;
  overflow-y: auto;
}

.tqm-section {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.tqm-remove {
  border-top: 1px solid var(--p-content-border-color);
  padding-top: 0.625rem;
}

.tqm-label {
  font-size: 0.6875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--p-text-muted-color);
}

.tqm-select {
  width: 100%;
}

.tqm-open-link {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
}
.tqm-open-link i {
  font-size: 0.75rem;
}

.tqm-empty {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.tqm-chips,
.tqm-assigned {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
}

.tqm-chip {
  border: none;
  font-family: inherit;
  cursor: pointer;
  color: #fff;
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  transition: filter 0.12s, box-shadow 0.12s;
}
.tqm-chip:hover {
  filter: brightness(1.08);
}
.tqm-chip:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--p-primary-color);
}
.tqm-chip-icon {
  font-size: 0.5625rem;
}
</style>
