<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Popover from 'primevue/popover'
import Select from 'primevue/select'
import { type Tag } from '../api/tag'
import { type DocumentListItem } from '../api/document'
import TagBadge from './TagBadge.vue'
import { assignableTags, topUsedTags } from '../utils/tagQuickMenu'
import { nextFrame } from '../utils/nextFrame'

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

// Opened one rendering step late, deliberately (#213). PrimeVue's Popover binds a scroll
// listener on every scrollable ancestor of its anchor the moment it mounts and treats one
// scroll event as "my anchor moved, dismiss". The right-click that opens this menu is
// routinely preceded by a scroll — the user (or Playwright, bringing the row into view)
// scrolls `.app-content`, and the browser queues that `scroll` event for the next rendering
// update rather than firing it on the spot. Open the popover inline and a busy frame delivers
// that already-finished scroll into the freshly bound listener, so the menu closes itself
// before the user can type (measured: 10 of 15 pinned-CPU runs). `nextFrame()` moves the open
// past that delivery point, so the popover only ever sees scrolls that come after it. A real
// scroll while it is open still dismisses it, unchanged.
//
// Deferring makes the open CANCELLABLE, so it needs a latest-request token. For that one
// frame the popover does not exist yet, which means PrimeVue's own dismissal (outside
// click, Escape) has nothing to act on: without the token a menu the user dismissed inside
// the window would still appear afterwards, and a second right-click would stack a second
// open. Every call claims a token; the frame callback proceeds only while its token is
// still the current one, and anything that supersedes the request bumps it.
let openToken = 0

function cancelPendingOpen() {
  openToken++
}

async function show(event: Event) {
  pendingTag.value = null
  // `currentTarget` is only live while the event is being dispatched — read the anchor now,
  // not after the await.
  const anchor = (event.currentTarget ?? event.target) as HTMLElement | null
  if (!anchor) return
  const token = ++openToken

  // Stand in for the dismissal PrimeVue cannot do yet: a press or an Escape inside the
  // deferred window is the user acting on a menu that is not up, and must cancel it rather
  // than be overtaken by it. Capture phase, so it is seen before any handler can stop it;
  // both listeners come off again the moment the frame resolves.
  const supersede = (superseding: Event) => {
    if (superseding instanceof KeyboardEvent && superseding.key !== 'Escape') return
    cancelPendingOpen()
  }
  document.addEventListener('pointerdown', supersede, true)
  document.addEventListener('keydown', supersede, true)
  try {
    await nextFrame()
  } finally {
    document.removeEventListener('pointerdown', supersede, true)
    document.removeEventListener('keydown', supersede, true)
  }

  // Superseded by a newer right-click, or cancelled by hide()/a dismissing interaction.
  if (token !== openToken) return
  // The list behind this menu is a live query: a refetch landing inside the deferred window
  // replaces the row, leaving the captured anchor detached — positioning against it would
  // put the menu somewhere the row no longer is. The document can be gone with it (the
  // parent resolves it by id from that same list), and a tag menu with nothing to tag has
  // nothing to do. Either way the right outcome is no menu at all, silently.
  if (!anchor.isConnected || !props.document) return

  // PrimeVue takes placement from the second argument and its "was the anchor itself
  // clicked?" test from the event's `currentTarget`; hand the captured anchor to both so
  // deferring changes nothing but the timing.
  popover.value?.show({ currentTarget: anchor } as unknown as Event, anchor)
}

function hide() {
  // Also cancels an open still waiting for its frame — "make it go away" has to win over a
  // menu that has not appeared yet, or it appears right after.
  cancelPendingOpen()
  popover.value?.hide()
}

// Dismissal on an outside RIGHT-click (#234). PrimeVue dismisses a Popover on an outside
// `click`, and a right-click fires no click — so this menu stayed up while the browser drew
// its own menu next to it: two context menus on screen at once, the reported symptom. The
// second right-click only reaches the parent's handler when it lands on a document, which is
// why every other landing spot leaked.
//
// The event is otherwise left completely alone — no preventDefault, no stopPropagation — so
// off a document the native menu is still the user's, and the shift+right-click escape hatch
// (#194) keeps reaching it untouched.
//
// CAPTURE phase, deliberately: the dismissal has to land BEFORE the document row/card handler
// that reopens this menu for whatever is under the cursor. Bubbling would undo that reopen, so
// right-clicking another document would close the menu instead of moving it there.
function onOutsideContextMenu(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof Node)) return
  // The tag Select's overlay is teleported to <body>, so it belongs to this menu without
  // being a DOM descendant of it. Counting it as outside would let a right-click in the tag
  // filter — the gesture that reaches "paste" — take the whole menu away.
  const ownRoots: unknown[] = [popover.value?.container, tagSelect.value?.overlay]
  if (ownRoots.some((root) => root instanceof Node && root.contains(target))) return
  hide()
}

// Bound only while the menu is up: PrimeVue emits `show` as the popover enters and `hide` as
// it leaves. Re-registering the same listener is a no-op, so a `show` without an intervening
// `hide` cannot stack a second one.
function bindOutsideContextMenu() {
  document.addEventListener('contextmenu', onOutsideContextMenu, true)
}

function unbindOutsideContextMenu() {
  document.removeEventListener('contextmenu', onOutsideContextMenu, true)
}

// A menu unmounted while open plays no leave transition, so `hide` never arrives.
onBeforeUnmount(unbindOutsideContextMenu)

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
  <!-- On show the menu only arms the outside-right-click dismissal (#234). It used to also
       open the tag Select and focus its filter for no-click keyboard entry (#171/#204), but
       that auto-opened overlay drew as a second floating panel under the popover and the
       reporter read the pair as "two menus" (#234 follow-up). The Select now opens on a
       click, so the right-click menu presents as a single panel; the slide-over keeps its
       own auto-focus, which is correct in that context. -->
  <Popover
    ref="popover"
    class="tag-quick-menu"
    @show="bindOutsideContextMenu"
    @hide="unbindOutsideContextMenu"
  >
    <div class="tqm-body">
      <!-- OPEN IN NEW TAB (#194). Right-click is claimed by this popover, so the
           browser's own "Open link in new tab" is out of reach on the surfaces that
           raise it; this is the explicit replacement. It sits ABOVE the ADD section
           deliberately — the Select's overlay opens downward and would cover anything
           placed below it once the user opens it. -->
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
