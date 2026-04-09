<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, keepPreviousData, useQueryClient } from '@tanstack/vue-query'
import { listDocuments, getDocument, updateDocument, type DocumentListItem, type DocumentDetail } from '../../api/document'
import { useTagFilterStore } from '../../stores/tagFilter'
import Skeleton from 'primevue/skeleton'
import ContextMenu from 'primevue/contextmenu'
import type { MenuItem } from 'primevue/menuitem'
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
const contextMenu = ref()

function onDocContextMenu(event: Event, doc: DocumentListItem) {
  if (!(event instanceof MouseEvent)) return
  event.preventDefault()
  contextMenuDoc.value = doc
  contextMenu.value?.show(event)
}

function showTagUpdateError() {
  toast.add({
    severity: 'error',
    summary: 'Error',
    detail: 'Failed to update tags',
    life: 3000,
  })
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
  } catch {
    showTagUpdateError()
  }
  contextMenu.value?.hide()
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
  } catch {
    showTagUpdateError()
  }
  contextMenu.value?.hide()
}

// --- Slide-over panel ---

const slideOverOpen = ref(false)
const slideOverDoc = ref<DocumentDetail | null>(null)
const slideOverLoading = ref(false)

async function openSlideOver(doc: DocumentListItem) {
  slideOverOpen.value = true
  slideOverLoading.value = true
  try {
    const { data } = await getDocument(doc.id)
    slideOverDoc.value = data
    queryClient.setQueryData(['document', doc.id], data)
  } catch {
    slideOverOpen.value = false
    toast.add({ severity: 'error', summary: 'Failed to load document', life: 3000 })
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
  } catch {
    showTagUpdateError()
  }
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
      <div v-if="isLoading" class="loading-area">
        <Skeleton v-for="i in 8" :key="i" height="3rem" class="mb-2" />
      </div>

      <DocumentTable
        v-else-if="documents.length"
        :documents="documents"
        @row-click="openDocument"
        @row-context-menu="onDocContextMenu"
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
      :document="slideOverDoc"
      :available-tags="availableTagsForSlideOver"
      @add-tag="addTagToSlideOver"
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
.loading-area { padding: 1rem 0; }

@media (max-width: 1024px) {
  .address-bar { padding: 0.75rem 1rem 0; }
  .doc-area { padding: 0.75rem 1rem 1rem; }
}
</style>
