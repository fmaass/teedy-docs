<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Select from 'primevue/select'
import Popover from 'primevue/popover'
import ProgressBar from 'primevue/progressbar'
import { SUPPORTED_LANGUAGES } from '../constants/languages'
import type { Tag } from '../api/tag'

const { t } = useI18n()

defineProps<{
  count: number
  tags: Tag[]
  /** Progress of an in-flight bulk op as [done, total]; null when idle. */
  progress: [number, number] | null
}>()

const emit = defineEmits<{
  addTag: [tagId: string]
  setLanguage: [language: string]
  delete: []
  clear: []
}>()

const languages = SUPPORTED_LANGUAGES

const tagPopover = ref()
const langPopover = ref()
const pendingTag = ref<string | null>(null)
const pendingLang = ref<string | null>(null)

function openTagPopover(event: Event) {
  pendingTag.value = null
  tagPopover.value?.toggle(event)
}

function applyTag() {
  if (!pendingTag.value) return
  emit('addTag', pendingTag.value)
  tagPopover.value?.hide()
}

function openLangPopover(event: Event) {
  pendingLang.value = null
  langPopover.value?.toggle(event)
}

function applyLang() {
  if (!pendingLang.value) return
  emit('setLanguage', pendingLang.value)
  langPopover.value?.hide()
}
</script>

<template>
  <div class="bulk-bar" role="toolbar" :aria-label="t('ui.bulk.toolbar')">
    <span class="bulk-count">{{ t('ui.bulk.selected_count', { count }) }}</span>

    <div class="bulk-actions">
      <Button
        size="small"
        severity="secondary"
        icon="pi pi-tag"
        :label="t('ui.bulk.add_tag')"
        :disabled="!!progress"
        @click="openTagPopover"
      />
      <Button
        size="small"
        severity="secondary"
        icon="pi pi-language"
        :label="t('ui.bulk.set_language')"
        :disabled="!!progress"
        @click="openLangPopover"
      />
      <Button
        size="small"
        severity="danger"
        icon="pi pi-trash"
        :label="t('ui.bulk.delete')"
        :disabled="!!progress"
        @click="emit('delete')"
      />
      <Button
        size="small"
        severity="secondary"
        text
        icon="pi pi-times"
        :label="t('ui.bulk.clear')"
        :disabled="!!progress"
        @click="emit('clear')"
      />
    </div>

    <div v-if="progress" class="bulk-progress">
      <span class="bulk-progress-label">{{ t('ui.bulk.applying', { done: progress[0], total: progress[1] }) }}</span>
      <ProgressBar
        :value="progress[1] ? Math.round((progress[0] / progress[1]) * 100) : 0"
        :showValue="false"
        class="bulk-progress-bar"
      />
    </div>

    <Popover ref="tagPopover">
      <div class="bulk-popover">
        <Select
          v-model="pendingTag"
          :options="tags"
          optionLabel="name"
          optionValue="id"
          filter
          :placeholder="t('ui.bulk.choose_tag')"
          class="bulk-select"
        />
        <Button size="small" :label="t('ui.bulk.apply')" :disabled="!pendingTag" @click="applyTag" />
      </div>
    </Popover>

    <Popover ref="langPopover">
      <div class="bulk-popover">
        <Select
          v-model="pendingLang"
          :options="languages"
          optionLabel="label"
          optionValue="value"
          filter
          :placeholder="t('ui.bulk.choose_language')"
          class="bulk-select"
        />
        <Button size="small" :label="t('ui.bulk.apply')" :disabled="!pendingLang" @click="applyLang" />
      </div>
    </Popover>
  </div>
</template>

<style scoped>
.bulk-bar {
  display: flex;
  align-items: center;
  gap: 1rem;
  flex-wrap: wrap;
  padding: 0.5rem 0.75rem;
  background: var(--p-content-hover-background);
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius);
}

.bulk-count {
  font-weight: 600;
  font-size: 0.875rem;
}

.bulk-actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.bulk-progress {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-width: 12rem;
  flex: 1;
}

.bulk-progress-label {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  white-space: nowrap;
}

.bulk-progress-bar {
  flex: 1;
  height: 0.5rem;
}

.bulk-popover {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.bulk-select {
  min-width: 14rem;
}
</style>
