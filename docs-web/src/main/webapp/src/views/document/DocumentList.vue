<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useQuery, keepPreviousData, useQueryClient } from '@tanstack/vue-query'
import { listDocuments, getDocument, type DocumentListItem, type DocumentDetail } from '../../api/document'
import { useTagFilterStore } from '../../stores/tagFilter'
import { useDocumentTags } from '../../composables/useDocumentTags'
import ContextMenu from 'primevue/contextmenu'
import type { MenuItem } from 'primevue/menuitem'
import type { DataTablePageEvent, DataTableSortEvent } from 'primevue/datatable'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'
import DocumentSearchBar from '../../components/DocumentSearchBar.vue'
import TagFilterChips from '../../components/TagFilterChips.vue'
import DocumentTable from '../../components/DocumentTable.vue'
import DocumentSlideOver from '../../components/DocumentSlideOver.vue'
import BulkActionBar from '../../components/BulkActionBar.vue'
import { updateDocument, deleteDocument } from '../../api/document'
import {
  runBulk,
  buildAddTagParams,
  buildLanguageParams,
  type BulkResult,
} from '../../utils/bulkOps'

const { t } = useI18n()
const router = useRouter()
const tf = useTagFilterStore()
const queryClient = useQueryClient()
const toast = useToast()
const confirm = useConfirm()
const { addTag, removeTag } = useDocumentTags()

// --- Pagination & sort state ---

const PAGE_SIZE = 20
const pageOffset = ref(0)
const sortField = ref<string>('create_date')
const sortOrder = ref<number>(-1)

const SORT_FIELD_MAP: Record<string, number> = { title: 1, create_date: 3 }

watch([() => tf.combinedSearch, () => tf.tagMode], () => {
  pageOffset.value = 0
})

const { data: documentsData, isLoading, isError, refetch } = useQuery({
  queryKey: computed(() => ['documents', {
    search: tf.combinedSearch,
    tagMode: tf.tagMode,
    offset: pageOffset.value,
    sortField: sortField.value,
    sortOrder: sortOrder.value,
  }]),
  queryFn: () =>
    listDocuments({
      limit: PAGE_SIZE,
      offset: pageOffset.value,
      sort_column: SORT_FIELD_MAP[sortField.value] ?? 3,
      asc: sortOrder.value === 1,
      search: tf.combinedSearch || undefined,
      'search[tagMode]': tf.selectedTagIds.size > 1 ? tf.tagMode : undefined,
    }).then((r) => r.data),
  placeholderData: keepPreviousData,
})

const documents = computed(() => documentsData.value?.documents ?? [])
const totalCount = computed(() => documentsData.value?.total ?? 0)

function onPage(event: DataTablePageEvent) {
  pageOffset.value = event.first
}

function onSort(event: DataTableSortEvent) {
  sortField.value = (event.sortField as string) ?? 'create_date'
  sortOrder.value = event.sortOrder ?? -1
  pageOffset.value = 0
}

// --- Quick tagging context menu ---

const contextMenuDoc = ref<DocumentListItem | null>(null)
const contextMenu = ref()

function onDocContextMenu(event: Event, doc: DocumentListItem) {
  if (!(event instanceof MouseEvent)) return
  event.preventDefault()
  contextMenuDoc.value = doc
  contextMenu.value?.show(event)
}

async function quickAddTag(tagId: string) {
  const doc = contextMenuDoc.value
  if (!doc) return
  await addTag(doc.id, tagId)
  contextMenu.value?.hide()
}

async function quickRemoveTag(tagId: string) {
  const doc = contextMenuDoc.value
  if (!doc) return
  await removeTag(doc.id, tagId)
  contextMenu.value?.hide()
}

// --- Slide-over panel ---

const slideOverOpen = ref(false)
const slideOverDocId = ref<string | null>(null)

const { data: slideOverDoc, isLoading: slideOverLoading, error: slideOverError } = useQuery({
  queryKey: computed(() => ['document', slideOverDocId.value]),
  queryFn: () => getDocument(slideOverDocId.value!).then((r) => r.data),
  enabled: computed(() => !!slideOverDocId.value && slideOverOpen.value),
})

watch(slideOverError, (err) => {
  if (err) {
    slideOverOpen.value = false
    toast.add({ severity: 'error', summary: t('ui.failed_to_load', { item: 'document' }), life: 3000 })
  }
})

function openSlideOver(doc: DocumentListItem) {
  slideOverDocId.value = doc.id
  slideOverOpen.value = true
}

const availableTagsForSlideOver = computed(() => {
  if (!slideOverDoc.value) return []
  const docTagIds = new Set(slideOverDoc.value.tags?.map((t) => t.id) ?? [])
  return tf.allTags.filter((t) => !docTagIds.has(t.id))
})

async function addTagToSlideOver(tagId: string) {
  if (!slideOverDoc.value || !tagId) return
  const refreshed = await addTag(slideOverDoc.value.id, tagId, slideOverDoc.value)
  if (refreshed) queryClient.setQueryData(['document', refreshed.id], refreshed)
}

async function removeTagFromSlideOver(tagId: string) {
  if (!slideOverDoc.value || !tagId) return
  const refreshed = await removeTag(slideOverDoc.value.id, tagId, slideOverDoc.value)
  if (refreshed) queryClient.setQueryData(['document', refreshed.id], refreshed)
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

const contextMenuItems = computed(() => {
  const doc = contextMenuDoc.value
  if (!doc) return [] as MenuItem[]
  const currentTagIds = new Set((doc.tags ?? []).map((t) => t.id))
  const addItems: MenuItem[] = tf.allTags
    .filter((t) => !currentTagIds.has(t.id))
    .map((tag) => ({
      label: tag.name,
      icon: 'pi pi-plus',
      command: () => quickAddTag(tag.id),
    }))
  const removeItems: MenuItem[] = (doc.tags ?? []).map((tag) => ({
    label: tag.name,
    icon: 'pi pi-minus',
    command: () => quickRemoveTag(tag.id),
  }))

  const items: MenuItem[] = []
  if (addItems.length) items.push({ label: 'Add tag', items: addItems })
  if (removeItems.length) {
    if (items.length) items.push({ separator: true })
    items.push({ label: 'Remove tag', items: removeItems })
  }
  return items
})

// --- Bulk operations ---
//
// Teedy exposes no bulk document endpoint, so each bulk action fans out over the
// existing single-document endpoints (see src/utils/bulkOps.ts). We run the
// operation over the current selection, drive a progress bar, and report a
// per-document success/failure summary — ACL/permission failures surface as
// individual failures rather than aborting the batch.

const selectedDocs = ref<DocumentListItem[]>([])
const bulkProgress = ref<[number, number] | null>(null)

// Documents dropping out of the current page (paging, refetch) must not linger in
// the selection — reconcile against what is actually rendered.
watch(documents, (docs) => {
  if (!selectedDocs.value.length) return
  const visibleIds = new Set(docs.map((d) => d.id))
  selectedDocs.value = selectedDocs.value.filter((d) => visibleIds.has(d.id))
})

function clearSelection() {
  selectedDocs.value = []
}

function summariseBulk(result: BulkResult) {
  const succeeded = result.succeeded.length
  const failed = result.failed.length
  if (failed === 0) {
    toast.add({
      severity: 'success',
      summary: t('ui.bulk.done'),
      detail: t('ui.bulk.summary_ok', { count: succeeded }),
      life: 3000,
    })
  } else {
    toast.add({
      severity: succeeded ? 'warn' : 'error',
      summary: t('ui.bulk.done'),
      detail: t('ui.bulk.summary_partial', { ok: succeeded, failed }),
      life: 6000,
    })
  }
}

async function runBulkOp(op: (doc: DocumentListItem) => Promise<unknown>) {
  const docs = [...selectedDocs.value]
  if (!docs.length || bulkProgress.value) return
  bulkProgress.value = [0, docs.length]
  try {
    const result = await runBulk(docs, op, (done, total) => {
      bulkProgress.value = [done, total]
    })
    summariseBulk(result)
    clearSelection()
    queryClient.invalidateQueries({ queryKey: ['documents'] })
  } finally {
    bulkProgress.value = null
  }
}

function bulkAddTag(tagId: string) {
  runBulkOp((doc) => updateDocument(doc.id, buildAddTagParams(doc, tagId)))
}

function bulkSetLanguage(language: string) {
  runBulkOp((doc) => updateDocument(doc.id, buildLanguageParams(doc, language)))
}

function bulkDelete() {
  const count = selectedDocs.value.length
  if (!count) return
  confirm.require({
    message: t('ui.bulk.delete_confirm', { count }),
    header: t('ui.bulk.delete'),
    icon: 'pi pi-trash',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
    accept: () => runBulkOp((doc) => deleteDocument(doc.id)),
  })
}
</script>

<template>
  <div class="doc-list-page">
    <!-- Address bar -->
    <div class="address-bar">
      <DocumentSearchBar
        v-model="tf.searchText"
        :has-active-filters="tf.hasActiveFilters"
        :total-count="totalCount"
        @clear="tf.clearFilters()"
      />

      <TagFilterChips
        :selected-tags="tf.selectedTags"
        :excluded-tags="tf.excludedTags"
        :related-tags="tf.relatedTags"
        @remove-tag="tf.removeTag($event)"
        @toggle-tag="tf.toggleTag($event)"
      />
    </div>

    <!-- Bulk action toolbar -->
    <div v-if="selectedDocs.length" class="bulk-bar-wrap">
      <BulkActionBar
        :count="selectedDocs.length"
        :tags="tf.allTags"
        :progress="bulkProgress"
        @add-tag="bulkAddTag"
        @set-language="bulkSetLanguage"
        @delete="bulkDelete"
        @clear="clearSelection"
      />
    </div>

    <!-- Document list -->
    <div class="doc-area">
      <DocumentTable
        v-if="documents.length || isLoading"
        v-model:selection="selectedDocs"
        selectable
        :documents="documents"
        :totalRecords="totalCount"
        :rows="PAGE_SIZE"
        :first="pageOffset"
        :loading="isLoading"
        :sortField="sortField"
        :sortOrder="sortOrder"
        @row-click="openDocument"
        @row-context-menu="onDocContextMenu"
        @page="onPage"
        @sort="onSort"
      />

      <ErrorState v-else-if="isError" @retry="refetch()" />

      <EmptyState
        v-else
        icon="pi pi-inbox"
        :message="tf.hasActiveFilters ? t('ui.no_documents_match') : t('ui.no_documents_yet')"
        :action-label="tf.hasActiveFilters ? undefined : t('ui.add_first_document')"
        @action="router.push({ name: 'document-add' })"
      />
    </div>

    <ContextMenu ref="contextMenu" :model="contextMenuItems" />

    <DocumentSlideOver
      v-model:visible="slideOverOpen"
      :loading="slideOverLoading"
      :document="slideOverDoc ?? null"
      :available-tags="availableTagsForSlideOver"
      @add-tag="addTagToSlideOver"
      @remove-tag="removeTagFromSlideOver"
      @open-full-view="openFullView"
      @edit-document="(id: string) => router.push({ name: 'document-edit', params: { id } })"
    />
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

/* --- Bulk action toolbar --- */

.bulk-bar-wrap {
  padding: 0 1.5rem;
  flex-shrink: 0;
}

/* --- Document area --- */

.doc-area {
  flex: 1;
  overflow-y: auto;
  padding: 0.75rem 1.5rem 1.5rem;
}

@media (max-width: 1024px) {
  .address-bar { padding: 0.75rem 1rem 0; }
  .bulk-bar-wrap { padding: 0 1rem; }
  .doc-area { padding: 0.75rem 1rem 1rem; }
}
</style>
