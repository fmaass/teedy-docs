<script setup lang="ts">
import { computed, ref } from 'vue'
import MultiSelect from 'primevue/multiselect'
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
 */

const props = defineProps<{
  /** Selected tag ids. */
  modelValue: string[]
  /** Every selectable tag, unmapped. */
  tags: Tag[]
  placeholder: string
  filterPlaceholder?: string
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
}>()

const emit = defineEmits<{
  'update:modelValue': [ids: string[]]
}>()

const picker = ref<{ show: (isFocus?: boolean) => void; hide: (isFocus?: boolean) => void } | null>(null)

const options = computed(() =>
  props.tags.map((tag) => ({ label: tag.name, value: tag.id, color: tag.color })),
)

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

/**
 * Open the option overlay. `autoFilterFocus` only lands focus in the filter when the
 * overlay opens, which otherwise takes a click — so a caller that wants keyboard entry
 * (the bulk bar, whose picker lives in a lazily-teleported Popover) calls this from its
 * container's `show` event after a `nextTick`, exactly as #171 required for the
 * quick-menu Select. A mount-time auto-open cannot work there: the picker does not
 * exist yet when the Popover has not rendered.
 */
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
    :filterPlaceholder="filterPlaceholder"
    :ariaLabel="ariaLabel"
    :selectionLimit="selectionLimit"
    class="tag-multiselect"
    display="chip"
    filter
    :autoFilterFocus="true"
    @update:modelValue="emit('update:modelValue', $event)"
  >
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
</style>
