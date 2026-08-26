<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Popover from 'primevue/popover'
import InputText from 'primevue/inputtext'
import IconField from 'primevue/iconfield'
import InputIcon from 'primevue/inputicon'
import { type Tag } from '../api/tag'
import { type DocumentListItem } from '../api/document'
import TagBadge from './TagBadge.vue'
import TagIconMark from './TagIconMark.vue'
import { assignableTags, topUsedTags } from '../utils/tagQuickMenu'
import { matchTagsByName, type TagNameMatch } from '../utils/tagSynonyms'
import { nextFrame } from '../utils/nextFrame'

/**
 * Compact right-click "tags" menu (#71). Replaces the former full-tag-tree context
 * menu, which overflowed and got cut off on instances with many tags.
 *
 * ADD  — an owned search box (an InputText, not PrimeVue's built-in Select filter) over a
 *        scrollable list of add-action buttons, one per assignable (not-yet-assigned) tag,
 *        plus a row of the most-used tags as quick-add chips (usage from `tagCounts`, which
 *        the app already fetches for the sidebar facets). Selecting either adds the tag. Each
 *        list row is a stateless "add this tag" button — NOT a stateful single-select widget
 *        (a Listbox's sticky selection could swallow a re-click of the just-added tag when
 *        the popover reopens on the same instance mid-fade). Bounded and scrolls inline —
 *        never overflows the viewport, and never teleports an overlay.
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
const filterInput = ref()

// The search box is OURS (TEEDY-86). The add control used to be a PrimeVue Select whose
// built-in filter lived in a teleported overlay; clearing it meant querySelector-ing that
// private DOM (`input.p-select-filter`) and dispatching a synthetic input event — a hard
// coupling to PrimeVue internals with no supported alternative in 4.x (#274). It is now a
// plain InputText feeding a list of add-action buttons rendered inline in the popover, so the
// filter text is a ref we own: the clear × just empties it, and the list is a computed over it.
const filterText = ref('')

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

// The assignable tags narrowed by the owned search box. `matchTagsByName` is the same pure,
// unit-tested selector both tag inputs search with; an empty query returns the full list
// unchanged. Each entry carries the SYNONYM that matched (#280), or null when the tag's own name
// did, which is what the row label uses to show why a tag the user did not type is being offered.
const filtered = computed(() => matchTagsByName(assignable.value, filterText.value))

// Top-5 most-used quick-add chips (falls back to first-5-by-name when no usage data). Ranked
// over all assignable tags, not the filtered view — the chips are a shortcut, not the search.
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
  filterText.value = ''
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

// Keeping the placed panel on screen (#284 follow-up). PrimeVue positions a Popover RELATIVE
// TO ITS ANCHOR, and this menu's anchor is the whole right-clicked `<tr>` — not a compact
// control like the button every other Popover in the app hangs off. On a phone-width screen
// the document table is wider than the viewport and scrolls horizontally inside
// `.p-datatable-table-container`, so that row ends off screen; `absolutePosition` then takes
// its collision branch and right-aligns the panel to the ROW's right edge
// (`left = max(0, targetLeft + scrollX + targetWidth - panelWidth)`), which put ~317px of a
// 385px panel past the right edge of a 393px screen (measured). The panel's own width is not
// in that expression, so bounding the width — as `.tqm-body` does — cannot reach it.
//
// WHY A CORRECTION AND NOT A DIFFERENT ANCHOR: handing `Popover.show` a narrow proxy anchor
// (a virtual element at the pointer) would make the collision math come out right on its own,
// but the anchor is load-bearing twice over. PrimeVue builds its dismiss-on-scroll handler
// from the anchor's scrollable ancestors (#213 — and it captures that element once, on the
// first open, then reuses it for the life of the component), and the anchor is what the
// popover treats as "clicked itself". Both contracts are asserted in `tag-add-focus.spec.ts`.
// Correcting the RESULT leaves the anchor, and therefore all of that, exactly as it was: this
// only ever moves the finished panel along an axis, and only when it hangs off an edge.
const PANEL_EDGE_GUTTER = 4

function clampPanelIntoViewport() {
  const container = popover.value?.container as HTMLElement | undefined
  if (!container) return

  // Both axes, every time: they correct independent insets, so an inline axis that is already
  // where it belongs must not return before the block axis has been looked at.
  clampPanelInlineAxis(container)
  clampPanelBlockAxis(container)
}

function clampPanelInlineAxis(container: HTMLElement) {
  // Read back the value the popover placed the panel with, and correct it by a DELTA. Both of
  // `absolutePosition`'s branches write a physical left edge in PAGE coordinates — in RTL it
  // writes `inset-inline-end`, which maps to `left` there — so one arithmetic serves both
  // directions, and staying in its own units means no assumption about the scroll offset.
  const style = container.style
  const prop = style.insetInlineEnd !== '' ? 'insetInlineEnd' : 'insetInlineStart'
  const placed = Number.parseFloat(style[prop])
  // Not positioned (yet): nothing placed is nothing to correct.
  if (Number.isNaN(placed)) return

  // LAYOUT metrics, never `getBoundingClientRect()`. The popover plays a 300ms
  // `scale(0.93)` enter animation, so for the first frames the panel's client rect is ~27px
  // narrower than the panel and sits ~13px right of where it settles (the animation scales
  // about the horizontally-centred `transform-origin` the popover sets). Clamping that
  // transient box left the settled panel 3px over the edge — measured 396.2px of panel on a
  // 393px screen, a green-looking near-miss. `offsetWidth` is the untransformed border box,
  // which is also what PrimeVue measures the panel with when it places it.
  const width = container.offsetWidth
  if (!width) return

  const scrollLeft = window.scrollX
  const left = placed - scrollLeft
  const slack = document.documentElement.clientWidth - width
  // The gutter is a preference, not a promise — it collapses rather than pushing a panel that
  // barely fits (`.tqm-body` is `100vw - 2rem`, most of which the popover's own padding eats)
  // back off the other edge. With a panel WIDER than the screen the range degenerates and the
  // START edge wins, because a menu cut off on the right is still usable and one cut off on
  // the left is not.
  const gutter = Math.max(0, Math.min(PANEL_EDGE_GUTTER, slack / 2))
  const wanted = Math.max(gutter, Math.min(left, slack - gutter))
  // Also the re-entrancy guard: the write below is itself a style mutation the observer sees.
  if (Math.abs(wanted - left) < 0.5) return

  style[prop] = `${wanted + scrollLeft}px`
  // The arrow (`--p-popover-arrow-left`) is deliberately left where `alignOverlay` put it: it
  // is an offset INSIDE the panel, and in the case this clamp fires it is already 0, i.e. the
  // panel's own leading corner — the anchor row it would otherwise point at is off screen.
}

// The same correction on the BLOCK axis, for the other way this anchor is unlike the compact
// buttons PrimeVue's collision math was written against. A tall panel hung off a row low on
// the page takes `absolutePosition`'s FLIP branch — `top = rowTop + scrollY - panelHeight`,
// `transform-origin: bottom` — and that branch has its own guard, `if (top < 0) top = scrollY`,
// which pins the inset to the top of the viewport. The overshoot happens AFTER it: flipping
// also makes `alignOverlay` tag the panel `.p-popover-flipped`, whose
// `margin-block-start: calc(popover.gutter * -1)` (-10px in this theme) pulls the rendered box
// back up out of the viewport. The guarded inset is inside the screen; the border box is not,
// by exactly the gutter — the measured -10px.
//
// TOP EDGE ONLY. The flip IS PrimeVue's remedy for bottom overflow, and it re-decides on every
// `alignOverlay`, so a bottom clamp here would be a second, uncoordinated authority over the
// same overflow: in the un-flipped case it would slide the panel up over the very row it is
// anchored to, a visible change with no defect behind it. Because the flip converts bottom
// overflow into top overflow, clamping the top covers the observed class either way.
function clampPanelBlockAxis(container: HTMLElement) {
  const style = container.style
  const placed = Number.parseFloat(style.top)
  if (Number.isNaN(placed)) return

  // LAYOUT metrics, for the reason spelled out on the inline axis: the 300ms `scale(0.93)`
  // enter animation makes the client rect lie for the first frames. `offsetHeight` is the
  // untransformed border box, and computed margins are used values a transform never touches.
  const height = container.offsetHeight
  if (!height) return

  // The flip's negative margin is part of where the panel RENDERS but not part of the inset
  // PrimeVue wrote, so the correction has to carry it or it would clamp a phantom edge.
  // `marginTop` rather than `marginBlockStart`: identical in this app's horizontal-tb writing
  // mode, and the physical property is the one every engine resolves.
  const margin = Number.parseFloat(getComputedStyle(container).marginTop) || 0
  const scrollTop = window.scrollY
  const top = placed - scrollTop + margin
  const slack = document.documentElement.clientHeight - height
  // Capped at half the slack, as inline: that is what keeps a downward correction from
  // creating the bottom overflow it was never asked to fix. A panel taller than the screen
  // degenerates the range to 0 and simply gets its top edge back.
  const gutter = Math.max(0, Math.min(PANEL_EDGE_GUTTER, slack / 2))
  const wanted = Math.max(gutter, top)
  // Fixed point, so the observer's own re-entry converges instead of ringing.
  if (Math.abs(wanted - top) < 0.5) return

  style.top = `${wanted + scrollTop - margin}px`
}

// `alignOverlay()` is not a one-shot: the popover re-runs it from its own content
// ResizeObserver, whose very first delivery lands a frame after the menu opens, and again
// whenever the panel's content changes size (typing in the search box shrinks the tag list).
// A clamp applied only on `show` is therefore overwritten before the user ever sees it. Watch
// the panel's inline style instead of trying to run last: whoever repositions it, the
// correction goes back on in the same rendering update, before paint. Watching the style is
// also the loosest available coupling — it assumes only that the position is expressed as an
// inline style on the popover's container, which is what `absolutePosition` documents itself
// as doing, rather than any particular internal call order.
let panelStyleObserver: MutationObserver | null = null

function watchPanelPlacement() {
  const container = popover.value?.container as HTMLElement | undefined
  if (!container || panelStyleObserver) return
  panelStyleObserver = new MutationObserver(clampPanelIntoViewport)
  panelStyleObserver.observe(container, { attributeFilter: ['style'] })
}

function unwatchPanelPlacement() {
  panelStyleObserver?.disconnect()
  panelStyleObserver = null
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
  // The whole menu — search box, tag list and all — now renders inline inside the popover, so
  // "inside" is simply a DOM-descendant test against the popover's own container. (The old
  // teleported Select overlay needed a second own-root; going inline removed it. A right-click
  // in the search box is inside this container, so it never takes the menu away.)
  const container = popover.value?.container
  if (container instanceof Node && container.contains(target)) return
  hide()
}

// Bound only while the menu is up: PrimeVue emits `show` as the popover enters and `hide` as
// it leaves. Re-registering the same listener is a no-op, so a `show` without an intervening
// `hide` cannot stack a second one. `show` is emitted at the END of the popover's enter hook,
// after it has positioned itself, so the panel measured here is the placed one.
function onPopoverShow() {
  document.addEventListener('contextmenu', onOutsideContextMenu, true)
  clampPanelIntoViewport()
  watchPanelPlacement()
}

function unbindOutsideContextMenu() {
  document.removeEventListener('contextmenu', onOutsideContextMenu, true)
}

// A menu unmounted while open plays no leave transition, so `hide` never arrives.
onBeforeUnmount(() => {
  unbindOutsideContextMenu()
  unwatchPanelPlacement()
})

// On leave: drop the dismissal listener and the placement watch, and reset the owned filter,
// so a reopened menu starts on the full list with no stale search text (and no clear × over
// an empty box).
function onPopoverHide() {
  unbindOutsideContextMenu()
  unwatchPanelPlacement()
  filterText.value = ''
}

// Add-action for a tag row (or the Enter-committed top match). Each row is a plain button, so
// this only ever runs on a real add — there is no stateful selection to toggle or go stale.
function onSelect(tagId: string) {
  emit('addTag', tagId)
  hide()
}

// Clear (×) for the owned filter (#274, reworked in TEEDY-86): empty our ref and return the
// caret so the next search can be typed straight away. No DOM reach, no synthetic events.
function clearFilter() {
  filterText.value = ''
  ;(filterInput.value?.$el as HTMLInputElement | undefined)?.focus()
}

// Enter in the search box commits the top match, so a tag can be added by keyboard alone
// (#171/#204) without leaving the field. Mouse users click a row; keyboard users can also tab
// onto the row buttons and press Enter.
function onFilterEnter() {
  const top = filtered.value[0]
  if (top) onSelect(top.tag.id)
}

/**
 * What the row is called for a screen reader and a tooltip. A tag offered because a SYNONYM
 * matched is announced with that reason, so the search that produced it is never a mystery —
 * the same information the visible "(via …)" gives a sighted user.
 */
function optionTitle(match: TagNameMatch): string {
  return match.via ? match.tag.name + t('ui.tag_menu.via', { synonym: match.via }) : match.tag.name
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
  <!-- On show the menu only arms the outside-right-click dismissal (#234); it does not steal
       focus or auto-open anything (the earlier auto-opened Select overlay drew as a second
       floating panel, which the reporter read as "two menus" — #234 follow-up). The search
       box and tag list are plain inline content of this one popover panel, so the menu always
       presents as a single panel; the caret goes to the search box on a click, and Enter there
       adds the top match for keyboard-only entry (#171/#204). -->
  <Popover
    ref="popover"
    class="tag-quick-menu"
    @show="onPopoverShow"
    @hide="onPopoverHide"
  >
    <div class="tqm-body">
      <!-- OPEN IN NEW TAB (#194). Right-click is claimed by this popover, so the
           browser's own "Open link in new tab" is out of reach on the surfaces that
           raise it; this is the explicit replacement. It sits ABOVE the ADD section,
           the natural reading order for the menu's primary link. -->
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
        <template v-if="assignable.length">
          <div class="tqm-filter-row">
            <IconField class="tqm-filter-field">
              <InputIcon class="pi pi-search" />
              <InputText
                ref="filterInput"
                v-model="filterText"
                :placeholder="t('ui.tag_menu.search')"
                :aria-label="t('ui.tag_menu.search')"
                class="tqm-filter-input"
                size="small"
                @keydown.enter.prevent="onFilterEnter"
              />
            </IconField>
            <!-- Clear (×) for the search box (#274): the parity with the main search bar's
                 "Clear" the reporter asked for, shown only once something is typed. A real,
                 focusable button in the accessibility tree — reachable by keyboard. -->
            <button
              v-if="filterText"
              type="button"
              class="tqm-filter-clear"
              @click="clearFilter"
              @mousedown.prevent
            >
              <i class="pi pi-times" aria-hidden="true" />{{ t('document.search_clear') }}
            </button>
          </div>
          <!-- The tag list is a set of ADD ACTIONS, not a persistent selection: each row is a
               stateless button that just adds its tag. A PrimeVue single-select Listbox would
               keep a sticky internal selection and toggle it off on a re-click, silently
               swallowing a re-add of the just-added tag when the popover reopens on the same
               instance mid-fade (the reported regression). Buttons have no such state. -->
          <div class="tqm-tag-list">
            <button
              v-for="match in filtered"
              :key="match.tag.id"
              type="button"
              class="tqm-option"
              :title="optionTitle(match)"
              :aria-label="t('ui.tag_menu.add_named', { name: optionTitle(match) })"
              @click="onSelect(match.tag.id)"
            >
              {{ match.tag.name
              }}<span v-if="match.via" class="tqm-via">{{
                t('ui.tag_menu.via', { synonym: match.via })
              }}</span>
            </button>
            <div v-if="!filtered.length" class="tqm-option-empty">
              {{ t('primevue.empty_filter_message') }}
            </div>
          </div>
        </template>
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
            <i class="pi pi-plus tqm-chip-icon" aria-hidden="true" /><TagIconMark :icon="tag.icon" />{{ tag.name }}
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
            :icon="tag.icon"
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
     instead of overflowing the viewport (#71). The 15rem this used to be clipped a
     realistic tag name by ~106px with no way to read the rest (#284); 24rem carries one
     where the room exists, and the viewport clamp keeps the panel inside a phone screen
     rather than trading one glitch for another.
     The subtrahend is the popover's own chrome plus a gutter, not the gutter alone: this
     body sits inside 1rem of popover content padding and a 1px border on EACH side, so the
     PANEL measures 34px more than the number below. `100vw - 2rem` (#284) therefore still
     rendered 395px of panel onto a 393px screen — a 2px overflow that the geometry spec
     missed only because it measured during the popover's `scale(0.93)` enter animation. */
  width: min(24rem, calc(100vw - 3.5rem));
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

/* Search box + clear, the clear tucked to the right like the main search bar's "Clear". */
.tqm-filter-row {
  display: flex;
  align-items: center;
  gap: 0.25rem;
}
.tqm-filter-field {
  flex: 1;
  min-width: 0;
}
.tqm-filter-input {
  width: 100%;
}

/* Bounded, scrolling list of add-action rows (#71). */
.tqm-tag-list {
  display: flex;
  flex-direction: column;
  max-height: 12rem;
  overflow-y: auto;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 4px);
}
.tqm-option {
  border: none;
  background: transparent;
  font: inherit;
  color: var(--p-text-color);
  text-align: left;
  cursor: pointer;
  padding: 0.3rem 0.5rem;
  white-space: nowrap;
  /* `overflow: hidden` is what ellipsises the name — and it also zeroes this flex item's
     automatic minimum size (Flexbox 4.5), which let the bounded list above squash every row
     to its padding (9.6px, text box 0) from ~20 assignable tags on: an empty-looking box
     whose rows still added a tag on click (#284). Pinning the shrink factor restores the
     content-based floor without giving up the ellipsis, so the list scrolls instead. Same
     guard as .tqm-filter-clear below. */
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tqm-option:hover {
  background: var(--p-content-hover-background, var(--p-surface-100));
  color: var(--p-content-hover-color, var(--p-text-color));
}
.tqm-option:focus-visible {
  outline: none;
  box-shadow: inset 0 0 0 2px var(--p-primary-color);
}
/* The "(via …)" reason on a row offered because a SYNONYM matched (#280). Muted, so the
   canonical name stays the thing being read. */
.tqm-via {
  color: var(--p-text-muted-color);
  font-size: 0.75rem;
}

.tqm-option-empty {
  padding: 0.3rem 0.5rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.tqm-filter-clear {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.125rem 0.25rem;
  border: none;
  background: transparent;
  color: var(--p-text-muted-color);
  font: inherit;
  font-size: 0.75rem;
  line-height: 1;
  white-space: nowrap;
  flex-shrink: 0;
}
.tqm-filter-clear:hover {
  color: var(--p-text-color);
}
.tqm-filter-clear:focus-visible {
  outline: none;
  border-radius: 2px;
  box-shadow: 0 0 0 2px var(--p-primary-color);
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
