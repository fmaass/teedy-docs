<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, keepPreviousData, useQueryClient } from '@tanstack/vue-query'
import { listDocuments, getDocument, updateDocument, type DocumentListItem, type DocumentDetail } from '../../api/document'
import { getFileUrl } from '../../api/file'
import { languageLabel } from '../../constants/languages'
import { useTagFilterStore } from '../../stores/tagFilter'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import Chip from 'primevue/chip'
import Drawer from 'primevue/drawer'
import TagBadge from '../../components/TagBadge.vue'

const router = useRouter()
const tf = useTagFilterStore()
const queryClient = useQueryClient()

// --- Document search ---

const { data: documentsData, isLoading } = useQuery({
  queryKey: computed(() => ['documents', { search: tf.combinedSearch, tagMode: tf.tagMode }]),
  queryFn: () =>
    listDocuments({
      limit: 100,
      sort_column: 3,
      asc: false,
      search: tf.combinedSearch || undefined,
      'search[tagMode]': tf.selectedTagIds.size > 1 ? tf.tagMode : undefined,
    }).then((r) => r.data),
  placeholderData: keepPreviousData,
})

const documents = computed(() => documentsData.value?.documents ?? [])
const totalCount = computed(() => documentsData.value?.total ?? 0)

// --- Quick tagging context menu ---

const contextMenuDoc = ref<DocumentListItem | null>(null)
const contextMenuVisible = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)

function onDocContextMenu(event: MouseEvent, doc: DocumentListItem) {
  event.preventDefault()
  contextMenuDoc.value = doc
  contextMenuX.value = event.clientX
  contextMenuY.value = event.clientY
  contextMenuVisible.value = true
}

function closeContextMenu() {
  contextMenuVisible.value = false
}

async function quickAddTag(tagId: string) {
  const doc = contextMenuDoc.value
  if (!doc) return
  const currentTagIds = doc.tags?.map((t) => t.id) ?? []
  if (currentTagIds.includes(tagId)) return
  const params = new URLSearchParams()
  params.set('title', doc.title)
  params.set('language', doc.language)
  for (const id of [...currentTagIds, tagId]) params.append('tags', id)
  try {
    await updateDocument(doc.id, params)
    queryClient.invalidateQueries({ queryKey: ['documents'] })
  } catch { /* silently fail */ }
  contextMenuVisible.value = false
}

async function quickRemoveTag(tagId: string) {
  const doc = contextMenuDoc.value
  if (!doc) return
  const currentTagIds = doc.tags?.map((t) => t.id).filter((id) => id !== tagId) ?? []
  const params = new URLSearchParams()
  params.set('title', doc.title)
  params.set('language', doc.language)
  for (const id of currentTagIds) params.append('tags', id)
  try {
    await updateDocument(doc.id, params)
    queryClient.invalidateQueries({ queryKey: ['documents'] })
  } catch { /* silently fail */ }
  contextMenuVisible.value = false
}

// --- Slide-over panel ---

const slideOverOpen = ref(false)
const slideOverDoc = ref<DocumentDetail | null>(null)
const slideOverLoading = ref(false)
const slideOverTab = ref<'overview' | 'files'>('overview')
const slideOverTagAdding = ref(false)

async function openSlideOver(doc: DocumentListItem) {
  slideOverOpen.value = true
  slideOverLoading.value = true
  slideOverTab.value = 'overview'
  slideOverTagAdding.value = false
  try {
    const { data } = await getDocument(doc.id)
    slideOverDoc.value = data
    queryClient.setQueryData(['document', doc.id], data)
  } finally {
    slideOverLoading.value = false
  }
}

const availableTagsForSlideOver = computed(() => {
  if (!slideOverDoc.value) return []
  const docTagIds = new Set(slideOverDoc.value.tags?.map((t) => t.id) ?? [])
  return tf.allTags.filter((t) => !docTagIds.has(t.id))
})

async function addTagToSlideOver(tagId: string) {
  if (!slideOverDoc.value || !tagId) return
  const doc = slideOverDoc.value
  const currentTagIds = doc.tags?.map((t) => t.id) ?? []
  const params = new URLSearchParams()
  params.set('title', doc.title)
  params.set('language', doc.language)
  for (const id of [...currentTagIds, tagId]) params.append('tags', id)
  try {
    await updateDocument(doc.id, params)
    const { data } = await getDocument(doc.id)
    slideOverDoc.value = data
    queryClient.invalidateQueries({ queryKey: ['documents'] })
    slideOverTagAdding.value = false
  } catch { /* silently fail */ }
}

function buildFilterLabel(): string {
  const parts: string[] = []
  for (const tag of tf.selectedTags) parts.push(tag.name)
  if (tf.debouncedText.trim()) parts.push(`"${tf.debouncedText.trim()}"`)
  return parts.join(' · ')
}

function openFullView() {
  if (slideOverDoc.value) {
    const returnQuery: Record<string, string> = {}
    if (tf.selectedTagIds.size) returnQuery.tags = [...tf.selectedTagIds].join(',')
    if (tf.tagMode === 'or') returnQuery.mode = 'or'
    if (tf.debouncedText.trim()) returnQuery.search = tf.debouncedText.trim()

    router.push({
      name: 'document-view',
      params: { id: slideOverDoc.value.id },
      state: {
        returnTo: router.resolve({ name: 'documents', query: returnQuery }).fullPath,
        filterLabel: buildFilterLabel() || undefined,
      },
    })
  }
}

function openDocument(doc: DocumentListItem) {
  openSlideOver(doc)
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatDate(ts: number) {
  return new Date(ts).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
</script>

<template>
  <div class="doc-list-page">
    <!-- Address bar -->
    <div class="address-bar">
      <div class="search-row">
        <InputText
          v-model="tf.searchText"
          placeholder="Search documents..."
          class="search-input"
        />
        <Button
          v-if="tf.hasActiveFilters"
          icon="pi pi-times"
          label="Clear"
          text
          size="small"
          severity="secondary"
          @click="tf.clearFilters()"
        />
        <span v-if="totalCount" class="doc-count">{{ totalCount }} doc{{ totalCount !== 1 ? 's' : '' }}</span>
      </div>

      <!-- Active + excluded chips + related tags -->
      <div v-if="tf.selectedTags.length || tf.excludedTags.length || tf.relatedTags.length" class="chip-row">
        <Chip
          v-for="tag in tf.selectedTags"
          :key="tag.id"
          removable
          @remove="tf.removeTag(tag.id)"
          class="active-chip"
        >
          <template #default>
            <span class="chip-dot" :style="{ background: tag.color }" />
            <span class="chip-label">{{ tag.name }}</span>
          </template>
        </Chip>

        <Chip
          v-for="tag in tf.excludedTags"
          :key="'excl-' + tag.id"
          removable
          @remove="tf.removeTag(tag.id)"
          class="excluded-chip"
        >
          <template #default>
            <i class="pi pi-minus-circle excl-icon" />
            <span class="chip-label chip-excluded-label">{{ tag.name }}</span>
          </template>
        </Chip>

        <template v-if="tf.relatedTags.length">
          <span class="chip-separator" />
          <button
            v-for="entry in tf.relatedTags"
            :key="entry.tag.id"
            class="related-pill"
            @click="tf.toggleTag(entry.tag.id)"
          >
            <span class="pill-dot" :style="{ background: entry.tag.color }" />
            <span class="pill-name">{{ entry.tag.name }}</span>
            <span class="pill-count">{{ entry.count }}</span>
          </button>
        </template>
      </div>
    </div>

    <!-- Document list -->
    <div class="doc-area">
      <div v-if="isLoading" class="loading-area">
        <Skeleton v-for="i in 8" :key="i" height="3rem" class="mb-2" />
      </div>

      <DataTable
        v-else-if="documents.length"
        :value="documents"
        stripedRows
        :rowHover="true"
        class="doc-table"
        @row-click="(e: any) => openDocument(e.data)"
        @row-contextmenu="(e: any) => onDocContextMenu(e.originalEvent, e.data)"
        selectionMode="single"
      >
        <Column header="" style="width: 44px">
          <template #body="{ data }">
            <div class="doc-thumb">
              <img
                v-if="data.file_id"
                :src="getFileUrl(data.file_id, 'thumb')"
                alt=""
                loading="lazy"
                @error="($event.target as HTMLImageElement).style.display = 'none'"
              />
              <i v-else class="pi pi-file" />
            </div>
          </template>
        </Column>
        <Column field="title" header="Title" sortable>
          <template #body="{ data }">
            <span class="doc-title">{{ data.title }}</span>
          </template>
        </Column>
        <Column header="Tags" style="width: 200px">
          <template #body="{ data }">
            <div class="doc-tags" v-if="data.tags?.length">
              <TagBadge v-for="tag in data.tags.slice(0, 3)" :key="tag.id" :name="tag.name" :color="tag.color" />
              <span v-if="data.tags.length > 3" class="tag-overflow">+{{ data.tags.length - 3 }}</span>
            </div>
          </template>
        </Column>
        <Column header="Language" style="width: 100px">
          <template #body="{ data }">
            <span class="doc-lang">{{ languageLabel(data.language) }}</span>
          </template>
        </Column>
        <Column header="Files" style="width: 60px">
          <template #body="{ data }">
            <span class="doc-meta">{{ data.file_count }}</span>
          </template>
        </Column>
        <Column field="create_date" header="Created" style="width: 120px" sortable>
          <template #body="{ data }">
            <span class="doc-meta">{{ formatDate(data.create_date) }}</span>
          </template>
        </Column>
      </DataTable>

      <div v-else class="empty-state">
        <i class="pi pi-inbox" />
        <p v-if="tf.hasActiveFilters">No documents match your filters</p>
        <p v-else>No documents yet</p>
        <Button
          v-if="!tf.hasActiveFilters"
          label="Add your first document"
          icon="pi pi-plus"
          outlined
          @click="router.push({ name: 'document-add' })"
        />
      </div>
    </div>

    <!-- Quick-tag context menu -->
    <div
      v-if="contextMenuVisible"
      class="ctx-menu-backdrop"
      @click="closeContextMenu"
      @contextmenu.prevent="closeContextMenu"
    />
    <div
      v-if="contextMenuVisible && contextMenuDoc"
      class="ctx-menu"
      :style="{ left: contextMenuX + 'px', top: contextMenuY + 'px' }"
    >
      <div class="ctx-menu-header">Add tag</div>
      <template v-if="tf.allTags.length">
        <button
          v-for="tag in tf.allTags.filter(t => !(contextMenuDoc?.tags ?? []).some(dt => dt.id === t.id))"
          :key="tag.id"
          class="ctx-menu-item"
          @click="quickAddTag(tag.id)"
        >
          <span class="tag-dot" :style="{ background: tag.color }" />
          {{ tag.name }}
        </button>
      </template>
      <div v-if="contextMenuDoc.tags?.length" class="ctx-menu-header" style="margin-top: 0.25rem">Remove tag</div>
      <button
        v-for="tag in (contextMenuDoc.tags ?? [])"
        :key="'rm-' + tag.id"
        class="ctx-menu-item ctx-menu-remove"
        @click="quickRemoveTag(tag.id)"
      >
        <span class="tag-dot" :style="{ background: tag.color }" />
        {{ tag.name }}
        <i class="pi pi-times" />
      </button>
    </div>

    <!-- Document slide-over panel -->
    <Drawer
      v-model:visible="slideOverOpen"
      position="right"
      class="doc-slide-over"
      :showCloseIcon="true"
    >
      <template #header>
        <div class="slide-over-header">
          <span class="slide-over-title">{{ slideOverDoc?.title ?? 'Document' }}</span>
        </div>
      </template>
      <div v-if="slideOverLoading" class="slide-over-loading">
        <Skeleton height="10rem" class="mb-3" />
        <Skeleton height="1.5rem" width="60%" class="mb-2" />
        <Skeleton height="1.5rem" width="40%" />
      </div>
      <div v-else-if="slideOverDoc" class="slide-over-body">
        <div v-if="slideOverDoc.file_id" class="slide-preview">
          <img :src="getFileUrl(slideOverDoc.file_id, 'web')" alt="" loading="lazy" @error="($event.target as HTMLImageElement).style.display = 'none'" />
        </div>

        <div class="slide-section">
          <div class="slide-tags-row">
            <TagBadge v-for="tag in slideOverDoc.tags" :key="tag.id" :name="tag.name" :color="tag.color" />
            <button v-if="!slideOverTagAdding" class="tag-add-btn" @click="slideOverTagAdding = true" title="Add tag">
              <i class="pi pi-plus" />
            </button>
          </div>
          <div v-if="slideOverTagAdding" class="tag-add-row">
            <select class="tag-add-select" @change="addTagToSlideOver(($event.target as HTMLSelectElement).value); ($event.target as HTMLSelectElement).value = ''">
              <option value="" disabled selected>Add a tag...</option>
              <option v-for="tag in availableTagsForSlideOver" :key="tag.id" :value="tag.id">{{ tag.name }}</option>
            </select>
            <button class="tag-add-cancel" @click="slideOverTagAdding = false"><i class="pi pi-times" /></button>
          </div>
        </div>

        <div class="slide-tabs">
          <button class="slide-tab" :class="{ active: slideOverTab === 'overview' }" @click="slideOverTab = 'overview'">Overview</button>
          <button class="slide-tab" :class="{ active: slideOverTab === 'files' }" @click="slideOverTab = 'files'">Files{{ slideOverDoc.files?.length ? ` (${slideOverDoc.files.length})` : '' }}</button>
        </div>

        <div v-if="slideOverTab === 'overview'" class="slide-tab-content">
          <div v-if="slideOverDoc.description" class="slide-section">
            <h4 class="slide-label">Description</h4>
            <div class="slide-description" v-html="slideOverDoc.description" />
          </div>
          <div class="slide-section">
            <h4 class="slide-label">Details</h4>
            <div class="slide-meta-grid">
              <div class="meta-item"><span class="meta-key">Language</span><span class="meta-val">{{ languageLabel(slideOverDoc.language) }}</span></div>
              <div class="meta-item"><span class="meta-key">Created</span><span class="meta-val">{{ formatDate(slideOverDoc.create_date) }}</span></div>
              <div class="meta-item" v-if="slideOverDoc.creator"><span class="meta-key">Creator</span><span class="meta-val">{{ slideOverDoc.creator }}</span></div>
              <div class="meta-item" v-if="slideOverDoc.subject"><span class="meta-key">Subject</span><span class="meta-val">{{ slideOverDoc.subject }}</span></div>
            </div>
          </div>
        </div>

        <div v-if="slideOverTab === 'files'" class="slide-tab-content">
          <div v-if="slideOverDoc.files?.length" class="slide-file-list">
            <div v-for="file in slideOverDoc.files" :key="file.id" class="slide-file-card">
              <div v-if="file.mimetype?.startsWith('image/')" class="file-inline-preview">
                <img :src="getFileUrl(file.id, 'web')" alt="" loading="lazy" />
              </div>
              <div class="file-card-row">
                <i class="pi pi-file" /><span class="file-name">{{ file.name }}</span><span class="file-size">{{ formatFileSize(file.size) }}</span>
                <a :href="getFileUrl(file.id)" target="_blank" class="file-dl-btn" title="Download"><i class="pi pi-download" /></a>
              </div>
            </div>
          </div>
          <div v-else class="slide-empty-files"><span class="meta">No files attached</span></div>
        </div>

        <div class="slide-actions">
          <Button label="Open full view" icon="pi pi-external-link" outlined size="small" @click="openFullView" />
          <Button label="Edit" icon="pi pi-pencil" text size="small" @click="router.push({ name: 'document-edit', params: { id: slideOverDoc.id } })" />
        </div>
      </div>
    </Drawer>
  </div>
</template>

<style scoped>
.doc-list-page {
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* --- Address bar --- */

.address-bar {
  padding: 0.75rem 1.5rem 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  flex-shrink: 0;
}

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

/* --- Chips --- */

.chip-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.375rem;
  padding-bottom: 0.25rem;
}
.active-chip { font-size: 0.8125rem; }
.chip-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; margin-right: 0.375rem;
}
.chip-label { font-size: 0.8125rem; }
.excluded-chip { opacity: 0.8; }
.excl-icon { font-size: 0.625rem; color: var(--p-red-500, #ef4444); margin-right: 0.25rem; }
.chip-excluded-label { text-decoration: line-through; }
.chip-separator {
  width: 1px; height: 1.25rem; background: var(--p-content-border-color); margin: 0 0.25rem;
}
.related-pill {
  display: inline-flex; align-items: center; gap: 0.25rem; padding: 0.25rem 0.625rem;
  border: 1px solid var(--p-content-border-color); border-radius: 12px; background: none;
  cursor: pointer; font-size: 0.75rem; color: var(--p-text-muted-color);
  transition: background 0.12s, border-color 0.12s, color 0.12s; font-family: inherit;
}
.related-pill:hover { background: var(--p-content-hover-background); border-color: var(--p-primary-color); color: var(--p-text-color); }
.pill-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.pill-name { font-weight: 500; }
.pill-count { font-size: 0.625rem; opacity: 0.7; }

/* --- Document area --- */

.doc-area {
  flex: 1;
  overflow-y: auto;
  padding: 0.75rem 1.5rem 1.5rem;
}
.doc-table { cursor: pointer; }
.doc-thumb {
  width: 32px; height: 32px; border-radius: 4px; overflow: hidden;
  background: var(--p-content-hover-background); display: flex; align-items: center;
  justify-content: center; color: var(--p-text-muted-color); font-size: 0.875rem;
}
.doc-thumb img { width: 100%; height: 100%; object-fit: cover; }
.doc-title { font-weight: 500; }
.doc-tags { display: flex; gap: 0.2rem; flex-wrap: wrap; }
.tag-overflow { font-size: 0.6875rem; color: var(--p-text-muted-color); align-self: center; }
.doc-lang, .doc-meta { font-size: 0.8125rem; color: var(--p-text-muted-color); }
.loading-area { padding: 1rem 0; }
.empty-state {
  display: flex; flex-direction: column; align-items: center; padding: 4rem 1rem;
  text-align: center; color: var(--p-text-muted-color);
}
.empty-state i { font-size: 3rem; margin-bottom: 1rem; }
.empty-state p { margin: 0 0 1rem; font-size: 1rem; }

/* --- Context menu --- */

.tag-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.ctx-menu-backdrop { position: fixed; inset: 0; z-index: 999; }
.ctx-menu {
  position: fixed; z-index: 1000; background: var(--p-content-background);
  border: 1px solid var(--p-content-border-color); border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.12); min-width: 180px; max-width: 260px;
  max-height: 320px; overflow-y: auto; padding: 0.375rem;
}
.ctx-menu-header {
  font-size: 0.6875rem; font-weight: 600; text-transform: uppercase;
  letter-spacing: 0.04em; color: var(--p-text-muted-color); padding: 0.375rem 0.5rem 0.25rem;
}
.ctx-menu-item {
  display: flex; align-items: center; gap: 0.375rem; width: 100%; padding: 0.375rem 0.5rem;
  border: none; background: none; font-size: 0.8125rem; font-family: inherit;
  color: var(--p-text-color); cursor: pointer; border-radius: 4px; text-align: left;
  transition: background 0.1s;
}
.ctx-menu-item:hover { background: var(--p-content-hover-background); }
.ctx-menu-remove { color: var(--p-text-muted-color); }
.ctx-menu-remove .pi-times { margin-left: auto; font-size: 0.625rem; opacity: 0.5; }

/* --- Slide-over --- */

.doc-slide-over :deep(.p-drawer) { width: min(500px, 90vw) !important; }
.slide-over-header { min-width: 0; }
.slide-over-title { font-size: 1.125rem; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.slide-over-loading { padding: 1rem 0; }
.slide-over-body { display: flex; flex-direction: column; gap: 1rem; }
.slide-preview { margin: -1rem -1.25rem 0; max-height: 200px; overflow: hidden; background: var(--p-content-hover-background); display: flex; align-items: center; justify-content: center; }
.slide-preview img { width: 100%; height: 200px; object-fit: contain; }
.slide-section { display: flex; flex-direction: column; gap: 0.5rem; }
.slide-tags-row { display: flex; flex-wrap: wrap; gap: 0.25rem; align-items: center; }
.tag-add-btn {
  display: inline-flex; align-items: center; justify-content: center; width: 1.5rem; height: 1.5rem;
  border-radius: 50%; border: 1px dashed var(--p-content-border-color); background: none;
  cursor: pointer; color: var(--p-text-muted-color); font-size: 0.625rem;
  transition: border-color 0.12s, color 0.12s;
}
.tag-add-btn:hover { border-color: var(--p-primary-color); color: var(--p-primary-color); }
.tag-add-row { display: flex; gap: 0.375rem; align-items: center; }
.tag-add-select {
  flex: 1; padding: 0.25rem 0.5rem; font-size: 0.8125rem; font-family: inherit;
  border: 1px solid var(--p-content-border-color); border-radius: 4px;
  background: var(--p-content-background); color: var(--p-text-color);
}
.tag-add-cancel { background: none; border: none; cursor: pointer; color: var(--p-text-muted-color); padding: 0.25rem; }
.slide-tabs { display: flex; gap: 0; border-bottom: 1px solid var(--p-content-border-color); }
.slide-tab {
  padding: 0.5rem 1rem; font-size: 0.8125rem; font-weight: 500; font-family: inherit;
  background: none; border: none; border-bottom: 2px solid transparent; cursor: pointer;
  color: var(--p-text-muted-color); transition: color 0.12s, border-color 0.12s;
}
.slide-tab:hover { color: var(--p-text-color); }
.slide-tab.active { color: var(--p-primary-color); border-bottom-color: var(--p-primary-color); }
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

@media (max-width: 1024px) {
  .address-bar { padding: 0.75rem 1rem 0; }
  .doc-area { padding: 0.75rem 1rem 1rem; }
}
</style>
