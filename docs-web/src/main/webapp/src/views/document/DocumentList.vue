<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery, keepPreviousData } from '@tanstack/vue-query'
import { listDocuments, type DocumentListItem } from '../../api/document'
import { listTags } from '../../api/tag'
import { getFileUrl } from '../../api/file'
import { languageLabel } from '../../constants/languages'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import Tree from 'primevue/tree'
import TagBadge from '../../components/TagBadge.vue'

const router = useRouter()
const route = useRoute()

const searchInput = ref((route.query.search as string) || '')
const debouncedSearch = ref(searchInput.value)

const { data: tagsData } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

const { data: documentsData, isLoading } = useQuery({
  queryKey: computed(() => ['documents', { search: debouncedSearch.value }]),
  queryFn: () =>
    listDocuments({
      limit: 100,
      sort_column: 3,
      asc: false,
      search: debouncedSearch.value || undefined,
    }).then((r) => r.data),
  placeholderData: keepPreviousData,
})

const documents = computed(() => documentsData.value?.documents ?? [])
const totalCount = computed(() => documentsData.value?.total ?? 0)
const showTagFilter = ref(false)

const tagTreeNodes = computed(() => {
  const allTags = tagsData.value ?? []
  const rootTags = allTags.filter((t) => !t.parent)
  function buildNode(tag: typeof allTags[0]): any {
    const children = allTags.filter((t) => t.parent === tag.id)
    return { key: tag.id, label: tag.name, data: tag, children: children.map(buildNode) }
  }
  return rootTags.map(buildNode)
})

let searchTimeout: ReturnType<typeof setTimeout>
watch(searchInput, (val) => {
  clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    debouncedSearch.value = val
    router.replace({ query: val ? { search: val } : {} })
  }, 300)
})

watch(() => route.query.search, (val) => {
  const s = (val as string) || ''
  if (s !== searchInput.value) {
    searchInput.value = s
    debouncedSearch.value = s
  }
})

function filterByTag(node: any) {
  const tagName = node.data.name
  searchInput.value = 'tag:' + tagName
  debouncedSearch.value = searchInput.value
  router.replace({ query: { search: searchInput.value } })
}

function clearTagFilter() {
  searchInput.value = ''
  debouncedSearch.value = ''
  router.replace({ query: {} })
}

function openDocument(doc: DocumentListItem) {
  router.push({ name: 'document-view', params: { id: doc.id } })
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
    <!-- Header -->
    <div class="page-header">
      <div>
        <h1>Documents</h1>
        <p class="page-subtitle" v-if="totalCount">{{ totalCount }} document{{ totalCount !== 1 ? 's' : '' }}</p>
      </div>
      <Button
        label="Add document"
        icon="pi pi-plus"
        @click="router.push({ name: 'document-add' })"
      />
    </div>

    <!-- Search + tag filters -->
    <div class="filter-bar">
      <InputText
        v-model="searchInput"
        placeholder="Search documents..."
        class="search-input"
      />
      <Button
        :icon="showTagFilter ? 'pi pi-chevron-up' : 'pi pi-tags'"
        :label="showTagFilter ? 'Hide tags' : 'Filter by tag'"
        text
        size="small"
        @click="showTagFilter = !showTagFilter"
      />
      <Button
        v-if="debouncedSearch"
        icon="pi pi-times"
        label="Clear"
        text
        size="small"
        severity="secondary"
        @click="clearTagFilter"
      />
    </div>

    <div v-if="showTagFilter && tagTreeNodes.length" class="tag-filter-panel">
      <Tree
        :value="tagTreeNodes"
        selectionMode="single"
        @node-select="filterByTag"
        class="filter-tree"
      >
        <template #default="{ node }">
          <span class="filter-tag-node">
            <span class="filter-tag-dot" :style="{ background: node.data.color }" />
            {{ node.label }}
          </span>
        </template>
      </Tree>
    </div>

    <!-- Loading -->
    <div v-if="isLoading" class="loading-area">
      <Skeleton v-for="i in 8" :key="i" height="3rem" class="mb-2" />
    </div>

    <!-- Document table -->
    <DataTable
      v-else-if="documents.length"
      :value="documents"
      stripedRows
      :rowHover="true"
      class="doc-table"
      @row-click="(e: any) => openDocument(e.data)"
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

    <!-- Empty state -->
    <div v-else class="empty-state">
      <i class="pi pi-inbox" />
      <p v-if="debouncedSearch">No documents match "{{ debouncedSearch }}"</p>
      <p v-else>No documents yet</p>
      <Button
        v-if="!debouncedSearch"
        label="Add your first document"
        icon="pi pi-plus"
        outlined
        @click="router.push({ name: 'document-add' })"
      />
    </div>
  </div>
</template>

<style scoped>
.doc-list-page {
  padding: 1.5rem;
  max-width: 1100px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1.25rem;
}
.page-header h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
}
.page-subtitle {
  margin: 0.2rem 0 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
}
.search-input {
  width: 100%;
  max-width: 400px;
}

.tag-filter-panel {
  margin-bottom: 1rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: 8px;
  padding: 0.5rem;
  max-height: 300px;
  overflow-y: auto;
}
.filter-tree :deep(.p-tree) {
  border: none;
  padding: 0;
  background: transparent;
}
.filter-tag-node {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
  cursor: pointer;
}
.filter-tag-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.loading-area {
  padding: 1rem 0;
}

.doc-table {
  cursor: pointer;
}
.doc-table :deep(.p-datatable-row-selected) {
  cursor: pointer;
}

.doc-thumb {
  width: 32px;
  height: 32px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--p-content-hover-background);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
}
.doc-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.doc-title {
  font-weight: 500;
}

.doc-tags {
  display: flex;
  gap: 0.2rem;
  flex-wrap: wrap;
}
.tag-overflow {
  font-size: 0.6875rem;
  color: var(--p-text-muted-color);
  align-self: center;
}

.doc-lang {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.doc-meta {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 4rem 1rem;
  text-align: center;
  color: var(--p-text-muted-color);
}
.empty-state i {
  font-size: 3rem;
  margin-bottom: 1rem;
}
.empty-state p {
  margin: 0 0 1rem;
  font-size: 1rem;
}

@media (max-width: 768px) {
  .doc-list-page {
    padding: 1rem;
  }
}
</style>
