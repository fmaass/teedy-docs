<script setup lang="ts">
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'

const props = defineProps<{
  modelValue: string
  hasActiveFilters: boolean
  totalCount: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  clear: []
}>()
</script>

<template>
  <div class="search-row">
    <InputText
      :model-value="props.modelValue"
      placeholder="Search documents..."
      class="search-input"
      @update:model-value="(value) => emit('update:modelValue', value as string)"
    />
    <Button
      v-if="hasActiveFilters"
      icon="pi pi-times"
      label="Clear"
      text
      size="small"
      severity="secondary"
      @click="emit('clear')"
    />
    <span v-if="totalCount" class="doc-count">{{ totalCount }} doc{{ totalCount !== 1 ? 's' : '' }}</span>
  </div>
</template>

<style scoped>
.search-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.search-input {
  flex: 1;
  min-width: 200px;
  max-width: 400px;
}

.doc-count {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  flex-shrink: 0;
}
</style>
