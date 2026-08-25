<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import MultiSelect from 'primevue/multiselect'
import Checkbox from 'primevue/checkbox'
import IconField from 'primevue/iconfield'
import InputIcon from 'primevue/inputicon'
import InputText from 'primevue/inputtext'
import { usePrimeVue } from 'primevue/config'
import TagBadge from './TagBadge.vue'
import type { Tag } from '../api/tag'

/**
 * The single tag-selection field, shared by the document edit form and the bulk
 * action bar (#182).
 *
 * It was extracted from DocumentEdit, which had grown filtering, coloured chips
 * and an unknown-id fallback while BulkActionBar stayed frozen on a plain,
 * uncoloured, keyboard-unreachable `Select`. Both surfaces now render this one
 * component, so a picker improvement can no longer land on only one of them.
 *
 * Contract notes:
 * - `tags` is the RAW tag list. The `{label,value,color}` option mapping lives
 *   here (not in the callers) so the unknown-id chip fallback below always has
 *   the same source of truth as the options.
 * - `id`/`inputId` are forwarded onto the MultiSelect. `#edit-tags` is a
 *   cross-suite selector (the edit form's `<label for>` plus six e2e call sites),
 *   so the root element must keep carrying the caller's id.
 * - No user-visible string is hardcoded here: every label is a prop, so the
 *   component adds no locale keys.
 * - The tag SEARCH box in the overlay header is this component's own, not
 *   PrimeVue's (#286 — see the header block below).
 */

const props = defineProps<{
  /** Selected tag ids. */
  modelValue: string[]
  /** Every selectable tag, unmapped. */
  tags: Tag[]
  placeholder: string
  filterPlaceholder?: string
  /**
   * Accessible name of the search box's clear (×). Required rather than optional:
   * a caller that forgot it would ship a button whose only content is an
   * aria-hidden icon — invisible to a screen reader.
   */
  clearFilterLabel: string
  ariaLabel?: string
  /** Forwarded to the MultiSelect root element (e2e selectors, `<label for>`). */
  id?: string
  /** Forwarded to the MultiSelect's hidden focusable input. */
  inputId?: string
  /**
   * Cap on how many tags may be selected at once. The bulk bar passes 1: a bulk
   * apply is one tag per invocation, so the model must not be able to hold two.
   * Omitted (undefined) means unlimited, which is what the edit form wants.
   */
  selectionLimit?: number
  /**
   * Opt in to the create-tag row (#288): when the typed search matches no existing tag, the
   * overlay offers to create one under that name, beneath the empty result list.
   *
   * A LABEL BUILDER rather than a boolean, because the row's text quotes the typed name and
   * this component adds no locale keys of its own (see the contract note above) — the caller
   * owns the wording. Omitted means no create affordance at all, which is what the bulk action
   * bar wants: a bulk apply picks one EXISTING tag for many documents.
   */
  createTagLabel?: (name: string) => string
}>()

const emit = defineEmits<{
  'update:modelValue': [ids: string[]]
  /** The create row was chosen; the trimmed text the user typed. */
  create: [name: string]
}>()

/**
 * The MultiSelect instance. Beyond show/hide, the owned search box (below) hands its
 * keystrokes to `onFilterKeyDown` — the very handler PrimeVue binds to its own filter
 * input — so ArrowDown/Enter/Escape keep driving option navigation from a box PrimeVue
 * no longer renders, and `$id`/`focusedOptionId` back the same ARIA wiring that input
 * carried. Delegating is what makes `filter="false"` a relocation of the box rather
 * than a loss of everything attached to it.
 */
interface PickerInstance {
  show: (isFocus?: boolean) => void
  hide: (isFocus?: boolean) => void
  onFilterKeyDown: (event: KeyboardEvent) => void
  onFilterBlur: () => void
  onFilterUpdated: () => void
  focusedOptionIndex: number
  $id: string
  focusedOptionId: string | null
}

const picker = ref<PickerInstance | null>(null)
const filterInput = ref<{ $el: HTMLInputElement } | null>(null)
const filterText = ref('')
const primevue = usePrimeVue()

const allOptions = computed(() =>
  props.tags.map((tag) => ({ label: tag.name, value: tag.id, color: tag.color })),
)

/**
 * Accent-folded, lower-cased comparison key. PrimeVue's built-in `contains` filter folded
 * accents through a Latin-1/Latin-Extended-A lookup table, so a German user typing "uber"
 * found "Über"; canonical decomposition reproduces that for every accented letter that
 * decomposes, which covers the diacritics of all twelve shipped locales. It does NOT fold
 * the stroked/ligature letters that have no decomposition (Ø, Ł, Đ, Æ, Œ) — those still
 * match on themselves, they just no longer match their unstroked spelling.
 */
function foldForSearch(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLocaleLowerCase()
}

// What the overlay lists. PrimeVue derives its `visibleOptions` from `options` and only
// narrows them further when ITS filter holds a value, so handing it the already-narrowed
// list keeps everything downstream (toggle-all, keyboard navigation, the empty-filter
// message) working off exactly the set the user can see.
const options = computed(() => {
  if (!filterText.value) return allOptions.value
  const needle = foldForSearch(filterText.value)
  return allOptions.value.filter((option) => foldForSearch(option.label).includes(needle))
})

const tagMap = computed(() => {
  const map = new Map<string, Tag>()
  for (const tag of props.tags) map.set(tag.id, tag)
  return map
})

// The MultiSelect chip slot's removeCallback closes over the selected value itself and its
// runtime only reads event.stopPropagation() — the second `item` param in PrimeVue's typed
// signature is unused at runtime. We pass a synthetic Event so the TagBadge remove button
// (whose own remove emit carries no event) can deselect the chip.
function removeTagChip(removeCallback: (event: Event, item?: unknown) => void) {
  removeCallback(new Event('remove'))
}

// Resolve a selected tag id to a display chip. A tag known in the tag list renders coloured
// with its real name; an UNKNOWN id (a selection not in the loaded tag list, or a gap
// before the list populates) still renders a VISIBLE, REMOVABLE fallback chip — a neutral
// grey chip labelled with the raw id — so a selected tag is never invisible/unremovable.
const UNKNOWN_TAG_COLOR = '#9e9e9e'
function tagChip(tagId: string): { name: string; color: string } {
  const tag = tagMap.value.get(tagId)
  return tag ? { name: tag.name, color: tag.color } : { name: tagId, color: UNKNOWN_TAG_COLOR }
}

function focusFilter() {
  filterInput.value?.$el.focus()
}

/**
 * Empty the search box and put the caret straight back in it, so the next search can be
 * typed without re-clicking (#286, the behaviour #274 shipped on the quick menu).
 */
function clearFilter() {
  filterText.value = ''
  focusFilter()
}

function onFilterKeyDown(event: KeyboardEvent) {
  picker.value?.onFilterKeyDown(event)
}

function onFilterBlur() {
  picker.value?.onFilterBlur()
}

// The option list shrinking or growing changes the overlay's height; PrimeVue realigns it
// off its own filter input's render, which is now this one.
function onFilterUpdated() {
  picker.value?.onFilterUpdated()
}

// The other half of what PrimeVue's `onFilterChange` did on every keystroke: drop the
// focused option. The highlight is an INDEX into the visible options, so a search that
// narrows the list under it leaves it pointing past the end — `aria-activedescendant`
// then names a row that is not on screen and Enter commits against that phantom index.
// Native sets the index directly (MultiSelect.vue `onFilterChange`: `focusedOptionIndex
// = -1`); there is no method to call, so this mirrors the same assignment through the
// same instance. Runs on the clear too, which changes the list just as much.
watch(filterText, () => {
  if (picker.value) picker.value.focusedOptionIndex = -1
})

/**
 * PrimeVue's `autoFilterFocus` focuses ITS filter input, which no longer exists — with
 * `filter="false"` that prop would dereference a missing ref — so the picker lands the
 * caret in the owned box itself. That is the #171 keyboard path: a caller (the bulk
 * popover) opens the picker and the very next keystroke searches.
 *
 * The box's own mount is the signal, not the MultiSelect's `before-show`/`show` events:
 * the overlay is teleported through a Portal that defers its content by a tick, so at
 * `before-show` the box does not exist yet, and `show` fires only once the open
 * transition has finished — late enough for early keystrokes to fall on the floor. The
 * box exists exactly when the overlay is open, so its mount is the precise moment.
 */
function onFilterBoxMounted() {
  onFilterUpdated()
  void nextTick(focusFilter)
}

// Toggle-all mirrors what PrimeVue's header checkbox did: hidden once a selection limit
// applies, computed over the VISIBLE options (so it means "all matches"), and clearing the
// whole selection when it is already satisfied.
const showToggleAll = computed(() => props.selectionLimit == null)
const allSelected = computed(
  () => options.value.length > 0 && options.value.every((o) => props.modelValue.includes(o.value)),
)
function toggleAll() {
  emit('update:modelValue', allSelected.value ? [] : options.value.map((o) => o.value))
}

const locale = computed(
  () =>
    (primevue?.config.locale ?? {}) as {
      aria?: Record<string, string | undefined>
      searchMessage?: string
      emptyMessage?: string
      emptySearchMessage?: string
      emptyFilterMessage?: string
    },
)

/**
 * PrimeVue picks between "no options at all" and "nothing matched your search" by looking
 * at ITS filter value, which is now permanently empty — so an unmatched search would read
 * "No available options", telling the user their tag list is empty when it is not. The
 * choice is made here instead, off the box this component owns.
 */
const emptyListMessage = computed(() =>
  filterText.value
    ? locale.value.emptySearchMessage || locale.value.emptyFilterMessage || ''
    : locale.value.emptyMessage || '',
)

const toggleAllAriaLabel = computed(() => {
  const aria = locale.value.aria
  return aria ? aria[allSelected.value ? 'selectAll' : 'unselectAll'] : undefined
})

// The live region PrimeVue rendered beside its filter, kept alive here: without it a
// screen-reader user typing into the search box gets no count of what survived.
const filterResultMessage = computed(() => {
  if (options.value.length) {
    return (locale.value.searchMessage ?? '').replaceAll('{0}', String(options.value.length))
  }
  return locale.value.emptySearchMessage || locale.value.emptyFilterMessage || ''
})

const listId = computed(() => (picker.value ? `${picker.value.$id}_list` : undefined))
const activeDescendant = computed(() => picker.value?.focusedOptionId ?? undefined)

/**
 * Open the option overlay. Focus only lands in the search box when the overlay opens,
 * which otherwise takes a click — so a caller that wants keyboard entry (the bulk bar,
 * whose picker lives in a lazily-teleported Popover) calls this from its container's
 * `show` event after a `nextTick`, exactly as #171 required for the quick-menu Select.
 * A mount-time auto-open cannot work there: the picker does not exist yet when the
 * Popover has not rendered.
 */
/**
 * The create-tag row (#288). It appears only when the search box holds text AND that text
 * matched NO tag — "matches no existing tag" in the reporter-approved mockup, which shows the
 * row directly under an empty result list. A search that still has matches is a selection, so
 * the row would only be in the way of it.
 */
const createName = computed(() => filterText.value.trim())
const showCreateRow = computed(
  () => !!props.createTagLabel && createName.value !== '' && options.value.length === 0,
)

/**
 * Hand the typed name to the caller and get the overlay out of the way of whatever it opens.
 *
 * The typed text is deliberately KEPT in the search box. Clearing it here would throw away
 * the user's typing the moment they cancel; left alone, a cancelled create finds the search
 * exactly as it was, and a completed one finds a search that now matches the new tag.
 */
function chooseCreate() {
  const name = createName.value
  if (!name) return
  hide()
  emit('create', name)
}

function show() {
  picker.value?.show()
}

function hide() {
  picker.value?.hide()
}

defineExpose({ show, hide })
</script>

<template>
  <MultiSelect
    ref="picker"
    :id="id"
    :inputId="inputId"
    :modelValue="modelValue"
    :options="options"
    optionLabel="label"
    optionValue="value"
    :placeholder="placeholder"
    :ariaLabel="ariaLabel"
    :selectionLimit="selectionLimit"
    class="tag-multiselect"
    display="chip"
    :filter="false"
    :showToggleAll="false"
    @update:modelValue="emit('update:modelValue', $event)"
  >
    <!-- The overlay header is ours (#286). PrimeVue keeps its filter's text in component
         state with no prop, ref or event to reach it — the wall #274 hit on the quick
         menu — so no clear (×) can empty it. Disabling `filter` and rendering the same
         row here puts the text in this component, where the clear can. PrimeVue's own
         header classes are reused so the row keeps its themed padding and layout. -->
    <template #header>
      <div class="p-multiselect-header">
        <Checkbox
          v-if="showToggleAll"
          :modelValue="allSelected"
          binary
          :aria-label="toggleAllAriaLabel"
          @change="toggleAll"
        />
        <IconField class="p-multiselect-filter-container">
          <InputIcon class="pi pi-search" />
          <InputText
            ref="filterInput"
            v-model="filterText"
            class="p-multiselect-filter tp-filter-input"
            :placeholder="filterPlaceholder"
            :aria-label="filterPlaceholder"
            role="searchbox"
            autocomplete="off"
            :aria-owns="listId"
            :aria-activedescendant="activeDescendant"
            @keydown="onFilterKeyDown"
            @blur="onFilterBlur"
            @vue:mounted="onFilterBoxMounted"
            @vue:updated="onFilterUpdated"
          />
        </IconField>
        <!-- Clear (×) for the search box, shown only once something is typed. A real,
             focusable button in the accessibility tree — not an icon in PrimeVue's
             aria-hidden InputIcon slot, where #274 first tried to put it. The
             mousedown guard keeps the caret in the field while the click lands. -->
        <button
          v-if="filterText"
          type="button"
          class="tp-filter-clear"
          @click="clearFilter"
          @mousedown.prevent
        >
          <i class="pi pi-times" aria-hidden="true" />{{ clearFilterLabel }}
        </button>
        <span role="status" aria-live="polite" class="p-hidden-accessible">
          {{ filterResultMessage }}
        </span>
      </div>
    </template>

    <template #empty>{{ emptyListMessage }}</template>

    <!-- Beneath the results, in PrimeVue's footer slot — which renders with no wrapper element
         of its own, so a picker without the row (the bulk bar) gets no stray markup. -->
    <template #footer>
      <button v-if="showCreateRow" type="button" class="tp-create-row" @click="chooseCreate">
        <i class="pi pi-plus" aria-hidden="true" />
        <span>{{ createTagLabel!(createName) }}</span>
      </button>
    </template>

    <!-- Colour the selected chips from the tag map (the slot's `value` is the tag
         id, since optionValue is the id). An id missing from the map still gets a
         visible, removable fallback chip. Chips wrap instead of clipping. -->
    <template #chip="{ value, removeCallback }">
      <TagBadge
        :name="tagChip(value).name"
        :color="tagChip(value).color"
        removable
        @remove="removeTagChip(removeCallback)"
      />
    </template>
  </MultiSelect>
</template>

<style scoped>
/* Let the coloured TagBadge chips wrap onto multiple rows instead of clipping in a
   single overflow line (#23). Owned here so both consumers inherit it. */
.tag-multiselect :deep(.p-multiselect-label) {
  flex-wrap: wrap;
  gap: 0.25rem;
  white-space: normal;
}

/* Mirrors the quick menu's clear (#274) so both tag search boxes read the same. */
.tp-filter-clear {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.125rem 0.25rem;
  margin-inline-start: 0.25rem;
  border: none;
  background: transparent;
  color: var(--p-text-muted-color);
  font: inherit;
  font-size: 0.75rem;
  line-height: 1;
  white-space: nowrap;
  flex-shrink: 0;
}
.tp-filter-clear:hover {
  color: var(--p-text-color);
}
.tp-filter-clear:focus-visible {
  outline: none;
  border-radius: 2px;
  box-shadow: 0 0 0 2px var(--p-primary-color);
}

/* The create-tag row (#288). A full-width option-shaped row, separated from the list above it
   so it reads as an action rather than one more tag to pick. */
.tp-create-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: none;
  border-top: 1px solid var(--p-content-border-color);
  background: transparent;
  color: var(--p-primary-color);
  font: inherit;
  font-size: 0.875rem;
  text-align: start;
  cursor: pointer;
}
.tp-create-row:hover {
  background: var(--p-content-hover-background);
}
.tp-create-row:focus-visible {
  outline: none;
  box-shadow: inset 0 0 0 2px var(--p-primary-color);
}
.tp-create-row .pi-plus {
  font-size: 0.75rem;
  flex-shrink: 0;
}
</style>
