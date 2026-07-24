<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
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

// autoFilterFocus only lands focus in the filter when the Select's overlay opens,
// which otherwise takes a click. Opening it on the popover's `show` gives keyboard
// tag entry with no click (#171). No-op when every tag is assigned (Select absent).
async function onPopoverShow() {
  await nextTick()
  tagSelect.value?.show()
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
          :autoFilterFocus="true"
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
