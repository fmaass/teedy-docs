<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery, keepPreviousData } from '@tanstack/vue-query'
import { listDocuments, type DocumentListItem } from '../../api/document'
import { listTags, type Tag } from '../../api/tag'
import { getFileUrl } from '../../api/file'
import { languageLabel } from '../../constants/languages'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import Tree from 'primevue/tree'
import Checkbox from 'primevue/checkbox'
import Chip from 'primevue/chip'
import TagBadge from '../../components/TagBadge.vue'

const router = useRouter()
const route = useRoute()

const selectedTagIds = ref(new Set<string>())
const searchText = ref('')
const debouncedText = ref('')
const showTagFilter = ref(false)

const { data: tagsData } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

// Parse the URL search param into tag selections + free text on initial load
// and whenever tags data becomes available
watch(tagsData, (allTags) => {
  if (!allTags?.length) return
  const raw = (route.query.search as string) || ''
  if (!raw) return
  const { tagIds, text } = parseSearchParam(raw, allTags)
  if (tagIds.size) {
    selectedTagIds.value = tagIds
    showTagFilter.value = true
  }
  searchText.value = text
  debouncedText.value = text
}, { immediate: true })

function parseSearchParam(search: string, allTags: Tag[]) {
  const tagIds = new Set<string>()
  const textParts: string[] = []
  for (const token of search.split(/\s+/)) {
    if (token.startsWith('tag:') && token.length > 4) {
      const name = token.substring(4)
      const tag = allTags.find((t) => t.name.toLowerCase() === name.toLowerCase())
      if (tag) tagIds.add(tag.id)
      else textParts.push(token)
    } else if (token) {
      textParts.push(token)
    }
  }
  return { tagIds, text: textParts.join(' ') }
}

const selectedTags = computed(() => {
  const allTags = tagsData.value ?? []
  return [...selectedTagIds.value]
    .map((id) => allTags.find((t) => t.id === id))
    .filter((t): t is Tag => !!t)
})

const combinedSearch = computed(() => {
  const parts: string[] = selectedTags.value.map((t) => `tag:${t.name}`)
  const text = debouncedText.value.trim()
  if (text) parts.push(text)
  return parts.join(' ')
})

const hasActiveFilters = computed(() => selectedTagIds.value.size > 0 || debouncedText.value.trim().length > 0)

const { data: documentsData, isLoading } = useQuery({
  queryKey: computed(() => ['documents', { search: combinedSearch.value }]),
  queryFn: () =>
    listDocuments({
      limit: 100,
      sort_column: 3,
      asc: false,
      search: combinedSearch.value || undefined,
    }).then((r) => r.data),
  placeholderData: keepPreviousData,
})

const documents = computed(() => documentsData.value?.documents ?? [])
const totalCount = computed(() => documentsData.value?.total ?? 0)

const tagTreeNodes = computed(() => {
  const allTags = tagsData.value ?? []
  const rootTags = allTags.filter((t) => !t.parent)
  function buildNode(tag: (typeof allTags)[0]): any {
    const children = allTags.filter((t) => t.parent === tag.id)
    return { key: tag.id, label: tag.name, data: tag, children: children.map(buildNode) }
  }
  return rootTags.map(buildNode)
})

const expandedKeys = computed(() => {
  const keys: Record<string, boolean> = {}
  for (const tag of tagsData.value ?? []) {
    const hasChildren = (tagsData.value ?? []).some((t) => t.parent === tag.id)
    if (hasChildren) keys[tag.id] = true
  }
  return keys
})

// Debounce free-text input
let searchTimeout: ReturnType<typeof setTimeout>
watch(searchText, (val) => {
  clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    debouncedText.value = val
  }, 300)
})

// Sync URL whenever the combined search changes
watch(combinedSearch, (val) => {
  const current = (route.query.search as string) || ''
  if (val !== current) {
    router.replace({ query: val ? { search: val } : {} })
  }
})

function toggleTag(tagId: string) {
  const next = new Set(selectedTagIds.value)
  if (next.has(tagId)) next.delete(tagId)
  else next.add(tagId)
  selectedTagIds.value = next
}

function removeTag(tagId: string) {
  toggleTag(tagId)
}

function clearFilters() {
  selectedTagIds.value = new Set()
  searchText.value = ''
  debouncedText.value = ''
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

    <!-- Search + tag toggle -->
    <div class="filter-bar">
      <InputText
        v-model="searchText"
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
        v-if="hasActiveFilters"
        icon="pi pi-times"
        label="Clear"
        text
        size="small"
        severity="secondary"
        @click="clearFilters"
      />
    </div>

    <!-- Active tag chips -->
    <div v-if="selectedTags.length" class="active-tag-chips">
      <Chip
        v-for="tag in selectedTags"
        :key="tag.id"
        :label="tag.name"
        removable
        @remove="removeTag(tag.id)"
      >
        <template #default>
          <span class="chip-tag-dot" :style="{ background: tag.color }" />
          <span class="chip-tag-label">{{ tag.name }}</span>
        </template>
      </Chip>
    </div>

    <!-- Tag tree with checkboxes -->
    <div v-if="showTagFilter && tagTreeNodes.length" class="tag-filter-panel">
      <Tree
        :value="tagTreeNodes"
        :expandedKeys="expandedKeys"
        class="filter-tree"
      >
        <template #default="{ node }">
          <div class="filter-tag-node" @click.stop="toggleTag(node.key)">
            <Checkbox
              :modelValue="selectedTagIds.has(node.key)"
              :binary="true"
              :tabindex="-1"
              @click.stop="toggleTag(node.key)"
            />
            <span class="filter-tag-dot" :style="{ background: node.data.color }" />
            <span>{{ node.label }}</span>
          </div>
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
      <p v-if="hasActiveFilters">No documents match your filters</p>
      <p v-else>No documents yet</p>
      <Button
        v-if="!hasActiveFilters"
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

.active-tag-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  margin-bottom: 0.75rem;
}
.chip-tag-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-right: 0.375rem;
}
.chip-tag-label {
  font-size: 0.8125rem;
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
