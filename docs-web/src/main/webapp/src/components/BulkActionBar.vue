<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Select from 'primevue/select'
import Popover from 'primevue/popover'
import ProgressBar from 'primevue/progressbar'
import TagPicker from './TagPicker.vue'
import { SUPPORTED_LANGUAGES } from '../constants/languages'
import type { Tag } from '../api/tag'

const { t } = useI18n()

defineProps<{
  count: number
  tags: Tag[]
  /** Progress of an in-flight bulk op as [done, total]; null when idle. */
  progress: [number, number] | null
  /** True while a bulk ZIP download is being assembled/fetched. */
  downloading: boolean
}>()

const emit = defineEmits<{
  addTag: [tagId: string]
  setLanguage: [language: string]
  delete: []
  clear: []
  download: []
  duplicate: []
}>()

const languages = SUPPORTED_LANGUAGES

const tagPopover = ref()
const langPopover = ref()
const tagPicker = ref<{ show: () => void } | null>(null)
/**
 * The tag to apply, held as a list because the shared TagPicker is a MultiSelect —
 * that is what renders the coloured chips (#182). Cardinality is NOT a convention
 * here: the picker is passed `selectionLimit=1`, so PrimeVue disables every other
 * option once one is chosen and this array can never hold two ids. A bulk apply
 * therefore stays exactly one tag per invocation, as `applyTag` below assumes.
 */
const pendingTags = ref<string[]>([])
const pendingLang = ref<string | null>(null)

function openTagPopover(event: Event) {
  pendingTags.value = []
  tagPopover.value?.toggle(event)
}

// The picker lives inside a LAZILY-teleported Popover, so it does not exist at mount
// and no auto-open prop can reach it — this is exactly the bug #171 fixed for the
// quick menu. Opening its overlay once the Popover has shown (after a nextTick, so
// the ref is assigned) is what lets autoFilterFocus put the caret in the filter,
// giving the bulk bar the keyboard tag entry it has never had.
async function onTagPopoverShow() {
  await nextTick()
  tagPicker.value?.show()
}

function applyTag() {
  const tagId = pendingTags.value[0]
  if (!tagId) return
  emit('addTag', tagId)
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
        :disabled="!!progress || downloading"
        @click="openTagPopover"
      />
      <Button
        size="small"
        severity="secondary"
        icon="pi pi-language"
        :label="t('ui.bulk.set_language')"
        :disabled="!!progress || downloading"
        @click="openLangPopover"
      />
      <Button
        size="small"
        severity="secondary"
        icon="pi pi-download"
        :label="t('ui.bulk.download')"
        :disabled="!!progress"
        :loading="downloading"
        @click="emit('download')"
      />
      <Button
        size="small"
        severity="secondary"
        icon="pi pi-copy"
        :label="t('ui.bulk.duplicate')"
        :disabled="!!progress || downloading"
        @click="emit('duplicate')"
      />
      <Button
        size="small"
        severity="danger"
        icon="pi pi-trash"
        :label="t('ui.bulk.delete')"
        :disabled="!!progress || downloading"
        @click="emit('delete')"
      />
      <Button
        size="small"
        severity="secondary"
        text
        icon="pi pi-times"
        :label="t('ui.bulk.clear')"
        :disabled="!!progress || downloading"
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

    <Popover ref="tagPopover" @show="onTagPopoverShow">
      <div class="bulk-popover">
        <TagPicker
          ref="tagPicker"
          v-model="pendingTags"
          :tags="tags"
          :selectionLimit="1"
          :placeholder="t('ui.bulk.choose_tag')"
          :filterPlaceholder="t('ui.tag_menu.search')"
          :ariaLabel="t('ui.bulk.choose_tag')"
          class="bulk-select"
        />
        <Button size="small" :label="t('ui.bulk.apply')" :disabled="!pendingTags.length" @click="applyTag" />
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
