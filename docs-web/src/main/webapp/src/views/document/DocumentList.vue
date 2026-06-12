<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, keepPreviousData, useQueryClient } from '@tanstack/vue-query'
import { listDocuments, getDocument, type DocumentListItem, type DocumentDetail } from '../../api/document'
import { useTagFilterStore } from '../../stores/tagFilter'
import { useDocumentTags } from '../../composables/useDocumentTags'
import ContextMenu from 'primevue/contextmenu'
import type { MenuItem } from 'primevue/menuitem'
import type { DataTablePageEvent, DataTableSortEvent } from 'primevue/datatable'
import { useToast } from 'primevue/usetoast'
import EmptyState from '../../components/EmptyState.vue'
import DocumentSearchBar from '../../components/DocumentSearchBar.vue'
import TagFilterChips from '../../components/TagFilterChips.vue'
import DocumentTable from '../../components/DocumentTable.vue'
import DocumentSlideOver from '../../components/DocumentSlideOver.vue'

const router = useRouter()
const tf = useTagFilterStore()
const queryClient = useQueryClient()
const toast = useToast()
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

const { data: documentsData, isLoading } = useQuery({
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
    toast.add({ severity: 'error', summary: 'Failed to load document', life: 3000 })
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

    <!-- Document list -->
    <div class="doc-area">
      <DocumentTable
        v-if="documents.length || isLoading"
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

      <EmptyState
        v-else
        icon="pi pi-inbox"
        :message="tf.hasActiveFilters ? 'No documents match your filters' : 'No documents yet'"
        :action-label="tf.hasActiveFilters ? undefined : 'Add your first document'"
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

/* --- Document area --- */

.doc-area {
  flex: 1;
  overflow-y: auto;
  padding: 0.75rem 1.5rem 1.5rem;
}

@media (max-width: 1024px) {
  .address-bar { padding: 0.75rem 1rem 0; }
  .doc-area { padding: 0.75rem 1rem 1rem; }
}
</style>
