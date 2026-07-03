<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import DOMPurify from 'dompurify'
import { getFileUrl } from '../api/file'
import { type DocumentDetail } from '../api/document'
import { type Tag } from '../api/tag'
import { languageLabel } from '../constants/languages'
import { formatDate, formatFileSize } from '../composables/useFormatters'
import Drawer from 'primevue/drawer'
import Button from 'primevue/button'
import Select from 'primevue/select'
import Skeleton from 'primevue/skeleton'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import TagBadge from './TagBadge.vue'

const { t } = useI18n()

const props = defineProps<{
  visible: boolean
  loading: boolean
  document: DocumentDetail | null
  availableTags: Tag[]
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  addTag: [tagId: string]
  removeTag: [tagId: string]
  openFullView: []
  editDocument: [id: string]
}>()

const slideOverTab = ref<'overview' | 'files'>('overview')
const slideOverTagAdding = ref(false)
const selectedTagId = ref<string | null>(null)

const sanitizedDescription = computed(() =>
  DOMPurify.sanitize(props.document?.description ?? ''),
)

watch(
  () => [props.visible, props.document?.id],
  () => {
    if (props.visible) {
      slideOverTab.value = 'overview'
      slideOverTagAdding.value = false
    }
  },
)

function onTagSelect(tagId: string) {
  if (!tagId) return
  emit('addTag', tagId)
  slideOverTagAdding.value = false
  selectedTagId.value = null
}

const tagOptions = computed(() =>
  props.availableTags.map((tag) => ({ label: tag.name, value: tag.id })),
)
</script>

<template>
  <Drawer
    :visible="visible"
    @update:visible="(value) => emit('update:visible', value)"
    position="right"
    class="doc-slide-over"
    :showCloseIcon="true"
  >
    <template #header>
      <div class="slide-over-header">
        <span class="slide-over-title">{{ document?.title ?? t('directive.auditlog.Document') }}</span>
      </div>
    </template>
    <div v-if="loading" class="slide-over-loading">
      <Skeleton height="10rem" class="mb-3" />
      <Skeleton height="1.5rem" width="60%" class="mb-2" />
      <Skeleton height="1.5rem" width="40%" />
    </div>
    <div v-else-if="document" class="slide-over-body">
      <div v-if="document.file_id" class="slide-preview">
        <img :src="getFileUrl(document.file_id, 'web')" alt="" loading="lazy" @error="($event.target as HTMLImageElement).style.display = 'none'" />
      </div>

      <div class="slide-section">
        <div class="slide-tags-row">
          <TagBadge v-for="tag in document.tags" :key="tag.id" :name="tag.name" :color="tag.color" removable @remove="emit('removeTag', tag.id)" />
          <Button v-if="!slideOverTagAdding" icon="pi pi-plus" text rounded size="small" class="tag-add-btn" @click="slideOverTagAdding = true" :aria-label="t('document.tags')" />
        </div>
        <div v-if="slideOverTagAdding" class="tag-add-row">
          <Select
            v-model="selectedTagId"
            :options="tagOptions"
            optionLabel="label"
            optionValue="value"
            :placeholder="t('document.tags')"
            class="tag-add-select"
            @update:modelValue="onTagSelect"
          />
          <Button icon="pi pi-times" text rounded size="small" @click="slideOverTagAdding = false" :aria-label="t('cancel')" />
        </div>
      </div>

      <Tabs v-model:value="slideOverTab">
        <TabList>
          <Tab value="overview">{{ t('ui.overview') }}</Tab>
          <Tab value="files">{{ document.files?.length ? t('ui.files_count', { count: document.files.length }) : t('ui.files') }}</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="overview">
            <div class="slide-tab-content">
              <div v-if="document.description" class="slide-section">
                <h4 class="slide-label">{{ t('document.description') }}</h4>
                <div class="slide-description" v-html="sanitizedDescription" />
              </div>
              <div class="slide-section">
                <h4 class="slide-label">{{ t('ui.details') }}</h4>
                <div class="slide-meta-grid">
                  <div class="meta-item"><span class="meta-key">{{ t('document.language') }}</span><span class="meta-val">{{ languageLabel(document.language) }}</span></div>
                  <div class="meta-item"><span class="meta-key">{{ t('document.creation_date') }}</span><span class="meta-val">{{ formatDate(document.create_date) }}</span></div>
                  <div class="meta-item" v-if="document.creator"><span class="meta-key">{{ t('document.search_creator') }}</span><span class="meta-val">{{ document.creator }}</span></div>
                  <div class="meta-item" v-if="document.subject"><span class="meta-key">{{ t('document.subject') }}</span><span class="meta-val">{{ document.subject }}</span></div>
                </div>
              </div>
            </div>
          </TabPanel>
          <TabPanel value="files">
            <div class="slide-tab-content">
              <div v-if="document.files?.length" class="slide-file-list">
                <div v-for="file in document.files" :key="file.id" class="slide-file-card">
                  <div v-if="file.mimetype?.startsWith('image/')" class="file-inline-preview">
                    <img :src="getFileUrl(file.id, 'web')" alt="" loading="lazy" />
                  </div>
                  <div class="file-card-row">
                    <i class="pi pi-file" /><span class="file-name">{{ file.name }}</span><span class="file-size">{{ formatFileSize(file.size) }}</span>
                    <a :href="getFileUrl(file.id)" target="_blank" class="file-dl-btn" :title="t('download')" :aria-label="t('download')"><i class="pi pi-download" /></a>
                  </div>
                </div>
              </div>
              <div v-else class="slide-empty-files"><span class="meta">{{ t('ui.no_files') }}</span></div>
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>

      <div class="slide-actions">
        <Button :label="t('open')" icon="pi pi-external-link" outlined size="small" @click="emit('openFullView')" />
        <Button :label="t('edit')" icon="pi pi-pencil" text size="small" @click="emit('editDocument', document.id)" />
      </div>
    </div>
  </Drawer>
</template>

<style scoped>
.doc-slide-over :deep(.p-drawer) { width: min(500px, 90vw); }
.slide-over-header { min-width: 0; }
.slide-over-title { font-size: 1.125rem; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.slide-over-loading { padding: 1rem 0; }
.slide-over-body { display: flex; flex-direction: column; gap: 1rem; }
.slide-preview { margin: -1rem -1.25rem 0; max-height: 200px; overflow: hidden; background: var(--p-content-hover-background); display: flex; align-items: center; justify-content: center; }
.slide-preview img { width: 100%; height: 200px; object-fit: contain; }
.slide-section { display: flex; flex-direction: column; gap: 0.5rem; }
.slide-tags-row { display: flex; flex-wrap: wrap; gap: 0.25rem; align-items: center; }
.tag-add-row { display: flex; gap: 0.375rem; align-items: center; }
.tag-add-select { flex: 1; font-size: 0.8125rem; }
.slide-tab-content { display: flex; flex-direction: column; gap: 1rem; }
.slide-label { margin: 0; font-size: 0.75rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; color: var(--p-text-muted-color); }
.slide-description { font-size: 0.875rem; line-height: 1.5; color: var(--p-text-color); }
.slide-description :deep(p) { margin: 0 0 0.5rem; }
.slide-meta-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.5rem; }
.meta-item { display: flex; flex-direction: column; gap: 0.125rem; }
.meta-key { font-size: 0.6875rem; font-weight: 500; text-transform: uppercase; letter-spacing: 0.03em; color: var(--p-text-muted-color); }
.meta-val { font-size: 0.8125rem; }
.slide-file-list { display: flex; flex-direction: column; gap: 0.25rem; }
.slide-file-card { border: 1px solid var(--p-content-border-color); border-radius: 6px; overflow: hidden; }
.file-inline-preview { background: var(--p-content-hover-background); max-height: 150px; overflow: hidden; display: flex; align-items: center; justify-content: center; }
.file-inline-preview img { width: 100%; max-height: 150px; object-fit: contain; }
.file-card-row { display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem 0.625rem; font-size: 0.8125rem; }
.file-card-row i.pi-file { color: var(--p-text-muted-color); font-size: 0.875rem; }
.file-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500; }
.file-size { color: var(--p-text-muted-color); font-size: 0.75rem; flex-shrink: 0; }
.file-dl-btn { color: var(--p-text-muted-color); text-decoration: none; padding: 0.25rem; border-radius: 4px; transition: color 0.12s, background 0.12s; }
.file-dl-btn:hover { color: var(--p-primary-color); background: var(--p-content-hover-background); }
.slide-empty-files { padding: 1rem; text-align: center; }
.meta { font-size: 0.8125rem; color: var(--p-text-muted-color); }
.slide-actions { display: flex; gap: 0.5rem; padding-top: 0.5rem; border-top: 1px solid var(--p-content-border-color); }
</style>
