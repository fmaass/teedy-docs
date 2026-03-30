<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery, keepPreviousData } from '@tanstack/vue-query'
import { listDocuments, getDocument, type DocumentListItem, type DocumentDetail } from '../../api/document'
import { listTags, getTagStats, getTagFacets, type Tag } from '../../api/tag'
import { getFileUrl } from '../../api/file'
import { useQueryClient } from '@tanstack/vue-query'
import { languageLabel } from '../../constants/languages'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import Tree from 'primevue/tree'
import Chip from 'primevue/chip'
import SelectButton from 'primevue/selectbutton'
import Drawer from 'primevue/drawer'
import TagBadge from '../../components/TagBadge.vue'

const router = useRouter()
const route = useRoute()

const selectedTagIds = ref(new Set<string>())
const searchText = ref('')
const debouncedText = ref('')
const tagMode = ref<'and' | 'or'>('and')

const modeOptions = [
  { label: 'AND', value: 'and' },
  { label: 'OR', value: 'or' },
]

const isMobile = ref(false)
const tagDrawerOpen = ref(false)
let mql: MediaQueryList

function updateMobile(e: MediaQueryListEvent | MediaQueryList) {
  isMobile.value = e.matches
  if (!e.matches) tagDrawerOpen.value = false
}

onMounted(() => {
  mql = window.matchMedia('(max-width: 1024px)')
  isMobile.value = mql.matches
  mql.addEventListener('change', updateMobile)
})

onUnmounted(() => {
  mql?.removeEventListener('change', updateMobile)
})

// --- Data fetching ---

const { data: tagsData } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

const allTags = computed(() => tagsData.value ?? [])
const tagMap = computed(() => {
  const m = new Map<string, Tag>()
  for (const t of allTags.value) m.set(t.id, t)
  return m
})

const selectedTagIdArray = computed(() => [...selectedTagIds.value])

// Two-endpoint strategy: stats when nothing selected, facets when tags active
const { data: statsData } = useQuery({
  queryKey: ['tagStats'],
  queryFn: () => getTagStats().then((r) => r.data.stats),
  staleTime: 30_000,
})

const { data: facetData } = useQuery({
  queryKey: computed(() => ['tagFacets', selectedTagIdArray.value, tagMode.value]),
  queryFn: () => getTagFacets(selectedTagIdArray.value, tagMode.value).then((r) => r.data),
  staleTime: 15_000,
  enabled: computed(() => selectedTagIds.value.size > 0),
})

const tagCounts = computed<Record<string, number>>(() => {
  if (selectedTagIds.value.size > 0 && facetData.value) {
    return facetData.value.facets
  }
  return statsData.value ?? {}
})

const matchingTotal = computed(() => facetData.value?.total ?? 0)

// --- URL sync ---

watch(tagsData, (tags) => {
  if (!tags?.length) return
  const raw = (route.query.tags as string) || ''
  const mode = (route.query.mode as string) || 'and'
  const search = (route.query.search as string) || ''

  if (raw) {
    const ids = raw.split(',').filter(Boolean)
    selectedTagIds.value = new Set(ids.filter((id) => tagMap.value.has(id)))
  }
  if (mode === 'or') tagMode.value = 'or'
  if (search) {
    searchText.value = search
    debouncedText.value = search
  }
}, { immediate: true })

function syncUrl() {
  const query: Record<string, string> = {}
  if (selectedTagIds.value.size) query.tags = [...selectedTagIds.value].join(',')
  if (tagMode.value === 'or') query.mode = 'or'
  if (debouncedText.value.trim()) query.search = debouncedText.value.trim()
  router.replace({ query: Object.keys(query).length ? query : {} })
}

watch([selectedTagIdArray, tagMode, debouncedText], syncUrl)

// --- Document search ---

const selectedTags = computed(() =>
  [...selectedTagIds.value]
    .map((id) => tagMap.value.get(id))
    .filter((t): t is Tag => !!t),
)

const combinedSearch = computed(() => {
  const parts: string[] = selectedTags.value.map((t) => `tag:${t.name}`)
  const text = debouncedText.value.trim()
  if (text) parts.push(text)
  return parts.join(' ')
})

const hasActiveFilters = computed(() =>
  selectedTagIds.value.size > 0 || debouncedText.value.trim().length > 0,
)

const { data: documentsData, isLoading } = useQuery({
  queryKey: computed(() => ['documents', { search: combinedSearch.value, tagMode: tagMode.value }]),
  queryFn: () =>
    listDocuments({
      limit: 100,
      sort_column: 3,
      asc: false,
      search: combinedSearch.value || undefined,
      'search[tagMode]': selectedTagIds.value.size > 1 ? tagMode.value : undefined,
    }).then((r) => r.data),
  placeholderData: keepPreviousData,
})

const documents = computed(() => documentsData.value?.documents ?? [])
const totalCount = computed(() => documentsData.value?.total ?? 0)

// --- Tag tree ---

interface TreeNode {
  key: string
  label: string
  data: Tag
  children: TreeNode[]
}

const tagTreeNodes = computed<TreeNode[]>(() => {
  const roots = allTags.value.filter((t) => !t.parent)
  function buildNode(tag: Tag): TreeNode {
    const children = allTags.value.filter((t) => t.parent === tag.id)
    return { key: tag.id, label: tag.name, data: tag, children: children.map(buildNode) }
  }
  return roots.map(buildNode)
})

const expandedKeys = computed(() => {
  const keys: Record<string, boolean> = {}
  if (selectedTagIds.value.size === 0) return keys
  for (const id of selectedTagIds.value) {
    let tag = tagMap.value.get(id)
    while (tag?.parent) {
      keys[tag.parent] = true
      tag = tagMap.value.get(tag.parent)
    }
  }
  return keys
})

// Related tags: facets that aren't selected (for address bar pills)
const relatedTags = computed(() => {
  if (selectedTagIds.value.size === 0) return []
  const entries = Object.entries(tagCounts.value)
    .map(([id, count]) => ({ tag: tagMap.value.get(id), count }))
    .filter((e): e is { tag: Tag; count: number } =>
      !!e.tag && e.count > 0 && !selectedTagIds.value.has(e.tag.id),
    )
    .sort((a, b) => b.count - a.count)
    .slice(0, 8)
  return entries
})

// --- Actions ---

function toggleTag(tagId: string) {
  const next = new Set(selectedTagIds.value)
  if (next.has(tagId)) next.delete(tagId)
  else next.add(tagId)
  selectedTagIds.value = next
}

function removeTag(tagId: string) {
  const next = new Set(selectedTagIds.value)
  next.delete(tagId)
  selectedTagIds.value = next
}

function clearFilters() {
  selectedTagIds.value = new Set()
  searchText.value = ''
  debouncedText.value = ''
  tagMode.value = 'and'
}

// --- Slide-over panel ---

const queryClient = useQueryClient()
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
  } finally {
    slideOverLoading.value = false
  }
}

function openFullView() {
  if (slideOverDoc.value) {
    router.push({ name: 'document-view', params: { id: slideOverDoc.value.id } })
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

let searchTimeout: ReturnType<typeof setTimeout>
watch(searchText, (val) => {
  clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    debouncedText.value = val
  }, 300)
})
</script>

<template>
  <div class="unified-view">
    <!-- Tag tree sidebar (desktop) -->
    <aside v-if="!isMobile" class="tag-sidebar">
      <div class="tag-sidebar-header">
        <span class="tag-sidebar-title">Tags</span>
      </div>
      <div class="tag-tree-container">
        <Tree
          :value="tagTreeNodes"
          :expandedKeys="expandedKeys"
          class="tag-tree"
        >
          <template #default="{ node }">
            <div
              class="tag-tree-node"
              :class="{
                'tag-active': selectedTagIds.has(node.key),
                'tag-dimmed': selectedTagIds.size > 0 && !selectedTagIds.has(node.key) && !(tagCounts[node.key] > 0),
              }"
              @click.stop="toggleTag(node.key)"
            >
              <span class="tag-dot" :style="{ background: node.data.color }" />
              <span class="tag-name">{{ node.label }}</span>
              <span class="tag-count" v-if="tagCounts[node.key] !== undefined">
                {{ tagCounts[node.key] }}
              </span>
            </div>
          </template>
        </Tree>
        <div v-if="!tagTreeNodes.length" class="tag-empty">
          <span class="meta">No tags yet</span>
        </div>
      </div>
    </aside>

    <!-- Mobile tag drawer toggle -->
    <Drawer
      v-if="isMobile"
      v-model:visible="tagDrawerOpen"
      position="left"
      header="Tags"
      class="tag-drawer"
    >
      <div class="tag-tree-container">
        <Tree
          :value="tagTreeNodes"
          :expandedKeys="expandedKeys"
          class="tag-tree"
        >
          <template #default="{ node }">
            <div
              class="tag-tree-node"
              :class="{
                'tag-active': selectedTagIds.has(node.key),
                'tag-dimmed': selectedTagIds.size > 0 && !selectedTagIds.has(node.key) && !(tagCounts[node.key] > 0),
              }"
              @click.stop="toggleTag(node.key)"
            >
              <span class="tag-dot" :style="{ background: node.data.color }" />
              <span class="tag-name">{{ node.label }}</span>
              <span class="tag-count" v-if="tagCounts[node.key] !== undefined">
                {{ tagCounts[node.key] }}
              </span>
            </div>
          </template>
        </Tree>
      </div>
    </Drawer>

    <!-- Main content area -->
    <div class="main-content">
      <!-- Address bar -->
      <div class="address-bar">
        <div class="address-row">
          <div class="address-left">
            <Button
              v-if="isMobile"
              icon="pi pi-tags"
              text
              size="small"
              @click="tagDrawerOpen = true"
              class="mobile-tag-btn"
            />
            <h1 class="page-title">Documents</h1>
            <span v-if="totalCount" class="doc-count">{{ totalCount }}</span>
          </div>
          <Button
            label="Add document"
            icon="pi pi-plus"
            size="small"
            @click="router.push({ name: 'document-add' })"
          />
        </div>

        <!-- Search + mode toggle -->
        <div class="search-row">
          <InputText
            v-model="searchText"
            placeholder="Search documents..."
            class="search-input"
          />
          <SelectButton
            v-model="tagMode"
            :options="modeOptions"
            optionLabel="label"
            optionValue="value"
            :allowEmpty="false"
            class="mode-toggle"
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

        <!-- Active tag chips + related tags -->
        <div v-if="selectedTags.length || relatedTags.length" class="chip-row">
          <Chip
            v-for="tag in selectedTags"
            :key="tag.id"
            removable
            @remove="removeTag(tag.id)"
            class="active-chip"
          >
            <template #default>
              <span class="chip-dot" :style="{ background: tag.color }" />
              <span class="chip-label">{{ tag.name }}</span>
            </template>
          </Chip>

          <template v-if="relatedTags.length">
            <span class="chip-separator" />
            <button
              v-for="entry in relatedTags"
              :key="entry.tag.id"
              class="related-pill"
              @click="toggleTag(entry.tag.id)"
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
        <Skeleton height="2rem" class="mb-3" />
        <Skeleton height="4rem" class="mb-3" />
        <Skeleton height="1.5rem" width="60%" class="mb-2" />
        <Skeleton height="1.5rem" width="40%" />
      </div>
      <div v-else-if="slideOverDoc" class="slide-over-body">
        <!-- Tags -->
        <div v-if="slideOverDoc.tags?.length" class="slide-section">
          <div class="slide-tags">
            <TagBadge
              v-for="tag in slideOverDoc.tags"
              :key="tag.id"
              :name="tag.name"
              :color="tag.color"
            />
          </div>
        </div>

        <!-- Description -->
        <div v-if="slideOverDoc.description" class="slide-section">
          <h4 class="slide-label">Description</h4>
          <div class="slide-description" v-html="slideOverDoc.description" />
        </div>

        <!-- Metadata -->
        <div class="slide-section">
          <h4 class="slide-label">Details</h4>
          <div class="slide-meta-grid">
            <div class="meta-item">
              <span class="meta-key">Language</span>
              <span class="meta-val">{{ languageLabel(slideOverDoc.language) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-key">Created</span>
              <span class="meta-val">{{ formatDate(slideOverDoc.create_date) }}</span>
            </div>
            <div class="meta-item" v-if="slideOverDoc.creator">
              <span class="meta-key">Creator</span>
              <span class="meta-val">{{ slideOverDoc.creator }}</span>
            </div>
            <div class="meta-item" v-if="slideOverDoc.subject">
              <span class="meta-key">Subject</span>
              <span class="meta-val">{{ slideOverDoc.subject }}</span>
            </div>
          </div>
        </div>

        <!-- Files -->
        <div v-if="slideOverDoc.files?.length" class="slide-section">
          <h4 class="slide-label">Files ({{ slideOverDoc.files.length }})</h4>
          <div class="slide-file-list">
            <a
              v-for="file in slideOverDoc.files"
              :key="file.id"
              :href="getFileUrl(file.id)"
              target="_blank"
              class="slide-file-item"
            >
              <i class="pi pi-download" />
              <span class="file-name">{{ file.name }}</span>
              <span class="file-size">{{ formatFileSize(file.size) }}</span>
            </a>
          </div>
        </div>

        <!-- Open full view button -->
        <div class="slide-actions">
          <Button
            label="Open full view"
            icon="pi pi-external-link"
            outlined
            @click="openFullView"
          />
          <Button
            label="Edit"
            icon="pi pi-pencil"
            text
            @click="router.push({ name: 'document-edit', params: { id: slideOverDoc.id } })"
          />
        </div>
      </div>
    </Drawer>
  </div>
</template>

<style scoped>
.unified-view {
  display: flex;
  height: 100%;
  min-height: 0;
}

/* --- Tag sidebar --- */

.tag-sidebar {
  width: 250px;
  min-width: 250px;
  border-right: 1px solid var(--p-content-border-color);
  display: flex;
  flex-direction: column;
  background: var(--p-content-background);
  overflow: hidden;
}

.tag-sidebar-header {
  padding: 1rem 1rem 0.5rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tag-sidebar-title {
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--p-text-muted-color);
}

.tag-tree-container {
  flex: 1;
  overflow-y: auto;
  padding: 0 0.25rem 0.5rem;
}

.tag-tree :deep(.p-tree) {
  border: none;
  padding: 0;
  background: transparent;
}
.tag-tree :deep(.p-tree-node-content) {
  padding: 0.125rem 0;
}

.tag-tree-node {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
  cursor: pointer;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  transition: background 0.12s;
  width: 100%;
}
.tag-tree-node:hover {
  background: var(--p-content-hover-background);
}
.tag-tree-node.tag-active {
  background: color-mix(in srgb, var(--p-primary-color) 15%, transparent);
  font-weight: 600;
}
.tag-tree-node.tag-dimmed {
  opacity: 0.4;
}

.tag-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.tag-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-count {
  font-size: 0.6875rem;
  color: var(--p-text-muted-color);
  background: var(--p-content-hover-background);
  padding: 0.0625rem 0.375rem;
  border-radius: 10px;
  min-width: 1.25rem;
  text-align: center;
  flex-shrink: 0;
}

.tag-empty {
  padding: 1rem;
  text-align: center;
}

/* --- Main content --- */

.main-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* --- Address bar --- */

.address-bar {
  padding: 1rem 1.5rem 0;
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
  flex-shrink: 0;
}

.address-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.address-left {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.page-title {
  margin: 0;
  font-size: 1.375rem;
  font-weight: 600;
}

.doc-count {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  background: var(--p-content-hover-background);
  padding: 0.125rem 0.5rem;
  border-radius: 10px;
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

.mode-toggle :deep(.p-selectbutton) {
  height: 2rem;
}
.mode-toggle :deep(.p-togglebutton) {
  padding: 0.25rem 0.625rem;
  font-size: 0.75rem;
  font-weight: 600;
}

/* --- Chip row --- */

.chip-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.375rem;
  padding-bottom: 0.25rem;
}

.active-chip {
  font-size: 0.8125rem;
}

.chip-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-right: 0.375rem;
}
.chip-label {
  font-size: 0.8125rem;
}

.chip-separator {
  width: 1px;
  height: 1.25rem;
  background: var(--p-content-border-color);
  margin: 0 0.25rem;
}

.related-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.625rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: 12px;
  background: none;
  cursor: pointer;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  transition: background 0.12s, border-color 0.12s, color 0.12s;
  font-family: inherit;
}
.related-pill:hover {
  background: var(--p-content-hover-background);
  border-color: var(--p-primary-color);
  color: var(--p-text-color);
}

.pill-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
.pill-name {
  font-weight: 500;
}
.pill-count {
  font-size: 0.625rem;
  opacity: 0.7;
}

/* --- Document area --- */

.doc-area {
  flex: 1;
  overflow-y: auto;
  padding: 0.75rem 1.5rem 1.5rem;
}

.doc-table {
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

.doc-lang,
.doc-meta {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.loading-area {
  padding: 1rem 0;
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

.meta {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

/* --- Slide-over panel --- */

.doc-slide-over :deep(.p-drawer) {
  width: min(500px, 90vw) !important;
}

.slide-over-header {
  min-width: 0;
}
.slide-over-title {
  font-size: 1.125rem;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.slide-over-loading {
  padding: 1rem 0;
}

.slide-over-body {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.slide-section {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.slide-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
}

.slide-label {
  margin: 0;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--p-text-muted-color);
}

.slide-description {
  font-size: 0.875rem;
  line-height: 1.5;
  color: var(--p-text-color);
}
.slide-description :deep(p) {
  margin: 0 0 0.5rem;
}

.slide-meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.meta-key {
  font-size: 0.6875rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  color: var(--p-text-muted-color);
}

.meta-val {
  font-size: 0.8125rem;
}

.slide-file-list {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.slide-file-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.625rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: 6px;
  text-decoration: none;
  color: var(--p-text-color);
  font-size: 0.8125rem;
  transition: background 0.12s;
}
.slide-file-item:hover {
  background: var(--p-content-hover-background);
  text-decoration: none;
}
.slide-file-item i {
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
}
.file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}
.file-size {
  color: var(--p-text-muted-color);
  font-size: 0.75rem;
  flex-shrink: 0;
}

.slide-actions {
  display: flex;
  gap: 0.5rem;
  padding-top: 0.5rem;
  border-top: 1px solid var(--p-content-border-color);
}

/* --- Mobile --- */

.mobile-tag-btn {
  margin-right: 0.25rem;
}

.tag-drawer :deep(.p-drawer) {
  width: 280px !important;
}

@media (max-width: 1024px) {
  .address-bar {
    padding: 0.75rem 1rem 0;
  }
  .doc-area {
    padding: 0.75rem 1rem 1rem;
  }
}
</style>
