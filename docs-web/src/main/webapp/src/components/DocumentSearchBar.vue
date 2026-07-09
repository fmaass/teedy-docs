<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Popover from 'primevue/popover'

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

// Search operators actually parsed by the backend
// (DocumentSearchCriteriaUtil.parseSearchQuery). Tokens are literal; only the
// descriptions are translated.
const operators: { token: string; example: string; descKey: string }[] = [
  { token: 'tag:', example: 'tag:invoice', descKey: 'document.search_help.op_tag' },
  { token: '!tag:', example: '!tag:draft', descKey: 'document.search_help.op_nottag' },
  { token: 'after:', example: 'after:2024-01', descKey: 'document.search_help.op_after' },
  { token: 'before:', example: 'before:2024-12-31', descKey: 'document.search_help.op_before' },
  { token: 'uafter:', example: 'uafter:2024', descKey: 'document.search_help.op_uafter' },
  { token: 'ubefore:', example: 'ubefore:2024-06', descKey: 'document.search_help.op_ubefore' },
  { token: 'at:', example: 'at:2024-05-01', descKey: 'document.search_help.op_at' },
  { token: 'uat:', example: 'uat:2024-05', descKey: 'document.search_help.op_uat' },
  { token: 'by:', example: 'by:alice', descKey: 'document.search_help.op_by' },
  { token: 'lang:', example: 'lang:eng', descKey: 'document.search_help.op_lang' },
  { token: 'mime:', example: 'mime:application/pdf', descKey: 'document.search_help.op_mime' },
  { token: 'title:', example: 'title:report', descKey: 'document.search_help.op_title' },
  { token: 'shared:yes', example: 'shared:yes', descKey: 'document.search_help.op_shared' },
]
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
      <div class="search-help">
        <h4 class="search-help-title">{{ t('document.search_help.title') }}</h4>
        <p class="search-help-intro">{{ t('document.search_help.contents_hint') }}</p>
        <p class="search-help-intro">{{ t('document.search_help.operators_intro') }}</p>
        <table class="search-help-table">
          <tbody>
            <tr v-for="op in operators" :key="op.token">
              <td><code>{{ op.example }}</code></td>
              <td>{{ t(op.descKey) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
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

.search-help {
  max-width: 30rem;
}

.search-help-title {
  margin: 0 0 0.5rem;
  font-size: 0.95rem;
  font-weight: 600;
}

.search-help-intro {
  margin: 0 0 0.5rem;
  font-size: 0.8rem;
  color: var(--p-text-muted-color);
}

.search-help-table {
  border-collapse: collapse;
  font-size: 0.8rem;
}

.search-help-table td {
  padding: 0.15rem 0.5rem 0.15rem 0;
  vertical-align: top;
}

.search-help-table code {
  background: var(--p-surface-100);
  border-radius: 4px;
  padding: 0.05rem 0.35rem;
  font-size: 0.75rem;
  white-space: nowrap;
}

:global(.dark-mode) .search-help-table code {
  background: var(--p-surface-800);
}
</style>
