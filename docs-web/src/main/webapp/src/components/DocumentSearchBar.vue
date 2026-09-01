<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Popover from 'primevue/popover'
import SearchHelpContent from './SearchHelpContent.vue'

const { t } = useI18n()

const props = defineProps<{
  modelValue: string
  hasActiveFilters: boolean
  totalCount: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  clear: []
}>()

const helpPanel = ref<InstanceType<typeof Popover> | null>(null)

function toggleHelp(event: Event) {
  helpPanel.value?.toggle(event)
}
</script>

<template>
  <div class="search-row">
    <InputText
      :model-value="props.modelValue"
      :placeholder="t('document.search')"
      class="search-input"
      @update:model-value="(value) => emit('update:modelValue', value as string)"
    />
    <Button
      icon="pi pi-question-circle"
      text
      rounded
      size="small"
      severity="secondary"
      :aria-label="t('document.search_help.title')"
      @click="toggleHelp"
    />
    <Button
      v-if="hasActiveFilters"
      icon="pi pi-times"
      :label="t('document.search_clear')"
      text
      size="small"
      severity="secondary"
      @click="emit('clear')"
    />
    <span v-if="totalCount" class="doc-count">{{ t('document.count', { count: totalCount }) }}</span>

    <Popover ref="helpPanel">
      <SearchHelpContent />
    </Popover>
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
