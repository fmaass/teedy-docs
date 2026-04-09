<script setup lang="ts">
import Chip from 'primevue/chip'

interface SimpleTag {
  id: string
  name: string
  color: string
}

interface RelatedTagEntry {
  tag: SimpleTag
  count: number
}

const props = defineProps<{
  selectedTags: SimpleTag[]
  excludedTags: SimpleTag[]
  relatedTags: RelatedTagEntry[]
}>()

const emit = defineEmits<{
  removeTag: [tagId: string]
  toggleTag: [tagId: string]
}>()
</script>

<template>
  <div v-if="selectedTags.length || excludedTags.length || relatedTags.length" class="chip-row">
    <Chip
      v-for="tag in selectedTags"
      :key="tag.id"
      removable
      @remove="emit('removeTag', tag.id)"
      class="active-chip"
    >
      <template #default>
        <span class="chip-dot" :style="{ background: tag.color }" />
        <span class="chip-label">{{ tag.name }}</span>
      </template>
    </Chip>

    <Chip
      v-for="tag in excludedTags"
      :key="'excl-' + tag.id"
      removable
      @remove="emit('removeTag', tag.id)"
      class="excluded-chip"
    >
      <template #default>
        <i class="pi pi-minus-circle excl-icon" />
        <span class="chip-label chip-excluded-label">{{ tag.name }}</span>
      </template>
    </Chip>

    <template v-if="relatedTags.length">
      <span class="chip-separator" />
      <button
        v-for="entry in relatedTags"
        :key="entry.tag.id"
        class="related-pill"
        @click="emit('toggleTag', entry.tag.id)"
      >
        <span class="pill-dot" :style="{ background: entry.tag.color }" />
        <span class="pill-name">{{ entry.tag.name }}</span>
        <span class="pill-count">{{ entry.count }}</span>
      </button>
    </template>
  </div>
</template>

<style scoped>
.chip-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.375rem;
  padding-bottom: 0.25rem;
}

.active-chip {
  font-size: 0.8125rem;
}

.chip-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-right: 0.375rem;
}

.chip-label {
  font-size: 0.8125rem;
}

.excluded-chip {
  opacity: 0.8;
}

.excl-icon {
  font-size: 0.625rem;
  color: var(--teedy-disabled-color);
  margin-right: 0.25rem;
}

.chip-excluded-label {
  text-decoration: line-through;
}

.chip-separator {
  width: 1px;
  height: 1.25rem;
  background: var(--p-content-border-color);
  margin: 0 0.25rem;
}

.related-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.625rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: 12px;
  background: none;
  cursor: pointer;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  transition: background 0.12s, border-color 0.12s, color 0.12s;
  font-family: inherit;
}

.related-pill:hover {
  background: var(--p-content-hover-background);
  border-color: var(--p-primary-color);
  color: var(--p-text-color);
}

.pill-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.pill-name {
  font-weight: 500;
}

.pill-count {
  font-size: 0.625rem;
  opacity: 0.7;
}
</style>
