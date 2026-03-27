<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery, keepPreviousData } from '@tanstack/vue-query'
import { listDocuments, type DocumentListItem } from '../../api/document'
import { listTags } from '../../api/tag'
import { getFileUrl } from '../../api/file'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Tree from 'primevue/tree'
import Skeleton from 'primevue/skeleton'
import TagBadge from '../../components/TagBadge.vue'

const emit = defineEmits<{ navigate: [] }>()
const router = useRouter()
const route = useRoute()

const showTags = ref(true)
const debouncedSearch = ref((route.query.search as string) || '')
const searchInput = ref(debouncedSearch.value)
const activeTagIds = ref<Set<string>>(new Set())

const { data: tagsData } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

const { data: documentsData, isLoading } = useQuery({
  queryKey: computed(() => ['documents', { search: debouncedSearch.value }]),
  queryFn: () =>
    listDocuments({
      limit: 50,
      sort_column: 3,
      asc: false,
      search: debouncedSearch.value || undefined,
    }).then((r) => r.data.documents),
  placeholderData: keepPreviousData,
})

const tagTreeNodes = computed(() => {
  const tags = tagsData.value ?? []
  const rootTags = tags.filter((t) => !t.parent)
  function buildNode(tag: typeof tags[0]): any {
    const children = tags.filter((t) => t.parent === tag.id)
    return {
      key: tag.id,
      label: tag.name,
      data: tag,
      children: children.map(buildNode),
    }
  }
  return rootTags.map(buildNode)
})

// Top-level tags as filter chips (max 12 to avoid overflow)
const topTags = computed(() => (tagsData.value ?? []).filter((t) => !t.parent).slice(0, 12))

const documents = computed(() => documentsData.value ?? [])

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
    // Sync active tag chips from search string
    const tagMatches = [...s.matchAll(/tag:(\S+)/g)].map((m) => m[1])
    const tags = tagsData.value ?? []
    activeTagIds.value = new Set(
      tags.filter((t) => tagMatches.includes(t.name)).map((t) => t.id)
    )
  }
})

function openDocument(doc: DocumentListItem) {
  router.push({ name: 'document-view', params: { id: doc.id } })
  emit('navigate')
}

function isActive(doc: DocumentListItem) {
  return route.params.id === doc.id
}

function searchByTag(node: any) {
  searchInput.value = 'tag:' + node.data.name
  debouncedSearch.value = searchInput.value
  router.replace({ query: { search: searchInput.value } })
}

function toggleTagChip(tag: { id: string; name: string }) {
  const newActive = new Set(activeTagIds.value)
  if (newActive.has(tag.id)) {
    newActive.delete(tag.id)
  } else {
    newActive.add(tag.id)
  }
  activeTagIds.value = newActive

  const allTags = tagsData.value ?? []
  const parts = [...newActive].map((id) => {
    const t = allTags.find((x) => x.id === id)
    return t ? `tag:${t.name}` : ''
  }).filter(Boolean)

  const newSearch = parts.join(' ')
  searchInput.value = newSearch
  debouncedSearch.value = newSearch
  router.replace({ query: newSearch ? { search: newSearch } : {} })
}

function formatDate(ts: number) {
  const d = new Date(ts)
  const now = new Date()
  const diffMs = now.getTime() - d.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return 'just now'
  if (diffMin < 60) return `${diffMin}m ago`
  const diffHr = Math.floor(diffMin / 60)
  if (diffHr < 24) return `${diffHr}h ago`
  const diffDays = Math.floor(diffHr / 24)
  if (diffDays < 30) return `${diffDays}d ago`
  return d.toLocaleDateString()
}
</script>

<template>
  <div class="sidebar-inner">
    <!-- Add document -->
    <div class="sidebar-actions">
      <Button
        label="Add a document"
        icon="pi pi-plus"
        size="small"
        @click="router.push({ name: 'document-add' }); emit('navigate')"
        class="w-full"
      />
    </div>

    <!-- Search -->
    <div class="sidebar-search">
      <span class="p-input-icon-left w-full">
        <i class="pi pi-search" />
        <InputText
          v-model="searchInput"
          placeholder="Search…"
          class="w-full"
          size="small"
        />
      </span>
    </div>

    <!-- Tag chips (quick filter) -->
    <div v-if="topTags.length" class="tag-chips">
      <button
        v-for="tag in topTags"
        :key="tag.id"
        class="tag-chip"
        :class="{ active: activeTagIds.has(tag.id) }"
        @click="toggleTagChip(tag)"
      >
        <span class="tag-chip-dot" :style="{ background: tag.color }" />
        {{ tag.name }}
      </button>
    </div>

    <!-- Tag tree toggle + tree -->
    <div class="sidebar-section">
      <button class="sidebar-section-toggle" @click="showTags = !showTags">
        <i :class="showTags ? 'pi pi-chevron-down' : 'pi pi-chevron-right'" />
        <i class="pi pi-tags" />
        <span>Tags</span>
      </button>
      <div v-if="showTags && tagTreeNodes.length" class="sidebar-tags">
        <Tree
          :value="tagTreeNodes"
          selectionMode="single"
          @node-select="searchByTag"
          class="tag-tree"
        >
          <template #default="{ node }">
            <span class="tag-node">
              <span class="tag-dot" :style="{ background: node.data.color }" />
              {{ node.label }}
            </span>
          </template>
        </Tree>
      </div>
    </div>

    <!-- Document list -->
    <div class="sidebar-doclist">
      <div v-if="isLoading" class="p-4">
        <Skeleton v-for="i in 6" :key="i" height="3rem" class="mb-2" />
      </div>
      <template v-else>
        <div
          v-for="doc in documents"
          :key="doc.id"
          class="teedy-doc-item"
          :class="{ active: isActive(doc) }"
          @click="openDocument(doc)"
        >
          <!-- Thumbnail -->
          <div v-if="doc.file_id" class="doc-thumb">
            <img
              :src="getFileUrl(doc.file_id, 'thumb')"
              alt=""
              loading="lazy"
              @error="($event.target as HTMLImageElement).style.display = 'none'"
            />
          </div>
          <div v-else class="doc-thumb doc-thumb-empty">
            <i class="pi pi-file" />
          </div>

          <div class="teedy-doc-item-body">
            <div class="teedy-doc-item-header">
              <span class="teedy-doc-item-title">{{ doc.title }}</span>
              <span v-if="doc.file_count > 1" class="teedy-doc-item-count">{{ doc.file_count }}</span>
            </div>
            <div class="teedy-doc-item-footer">
              <span class="teedy-doc-item-meta">{{ formatDate(doc.create_date) }}</span>
              <div v-if="doc.tags?.length" class="teedy-doc-item-tags">
                <TagBadge v-for="tag in doc.tags.slice(0, 3)" :key="tag.id" :name="tag.name" :color="tag.color" />
                <span v-if="doc.tags.length > 3" class="tag-overflow">+{{ doc.tags.length - 3 }}</span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="!documents.length && !isLoading" class="teedy-empty sidebar-empty">
          <i class="pi pi-search" />
          <p>{{ debouncedSearch ? 'No results' : 'No documents yet' }}</p>
          <button
            v-if="!debouncedSearch"
            class="empty-add-btn"
            @click="router.push({ name: 'document-add' }); emit('navigate')"
          >
            <i class="pi pi-plus" /> Add your first document
          </button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.sidebar-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.sidebar-actions {
  padding: 0.75rem 1rem 0;
}

.sidebar-search {
  padding: 0.5rem 1rem;
}

/* Tag chips */
.tag-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  padding: 0 1rem 0.5rem;
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.2rem 0.5rem;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: none;
  cursor: pointer;
  font-size: 0.75rem;
  color: #374151;
  transition: border-color 0.15s, background 0.15s;
  white-space: nowrap;
}
.tag-chip:hover {
  border-color: #9ca3af;
  background: #f9fafb;
}
.tag-chip.active {
  border-color: var(--teedy-brand);
  background: color-mix(in srgb, var(--teedy-brand) 10%, transparent);
  color: var(--teedy-brand);
}

.tag-chip-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

/* Tree section */
.sidebar-section {
  padding: 0 0.5rem;
}

.sidebar-section-toggle {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  width: 100%;
  padding: 0.375rem 0.5rem;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 0.8125rem;
  font-weight: 600;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.sidebar-section-toggle:hover {
  color: #374151;
}

.sidebar-tags {
  max-height: 200px;
  overflow-y: auto;
}

.tag-tree :deep(.p-tree) {
  padding: 0;
  border: none;
  background: transparent;
}
.tag-tree :deep(.p-tree-node-content) {
  padding: 0.125rem 0;
}

.tag-node {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
}

.tag-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

/* Document list */
.sidebar-doclist {
  flex: 1;
  overflow-y: auto;
  border-top: 1px solid #e5e7eb;
  margin-top: 0.25rem;
}

.teedy-doc-item {
  display: flex;
  align-items: flex-start;
  gap: 0.625rem;
  padding: 0.625rem 1rem;
  cursor: pointer;
  border-bottom: 1px solid #f3f4f6;
  transition: background 0.1s;
}
.teedy-doc-item:hover {
  background: #f9fafb;
}
.teedy-doc-item.active {
  background: color-mix(in srgb, var(--teedy-brand) 8%, transparent);
  border-left: 3px solid var(--teedy-brand);
}

/* Thumbnail */
.doc-thumb {
  width: 36px;
  height: 36px;
  border-radius: 4px;
  overflow: hidden;
  flex-shrink: 0;
  background: #f3f4f6;
  display: flex;
  align-items: center;
  justify-content: center;
}
.doc-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.doc-thumb-empty {
  color: #9ca3af;
  font-size: 0.875rem;
}

.teedy-doc-item-body {
  flex: 1;
  min-width: 0;
}

.teedy-doc-item-header {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
}

.teedy-doc-item-title {
  font-size: 0.8125rem;
  font-weight: 500;
  color: #111827;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.teedy-doc-item-count {
  font-size: 0.6875rem;
  background: #e5e7eb;
  color: #6b7280;
  border-radius: 999px;
  padding: 0.05rem 0.375rem;
  flex-shrink: 0;
}

.teedy-doc-item-footer {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.2rem;
  flex-wrap: wrap;
}

.teedy-doc-item-meta {
  font-size: 0.75rem;
  color: #9ca3af;
  flex-shrink: 0;
}

.teedy-doc-item-tags {
  display: flex;
  gap: 0.2rem;
  flex-wrap: wrap;
}

.tag-overflow {
  font-size: 0.6875rem;
  color: #9ca3af;
  align-self: center;
}

/* Empty state */
.sidebar-empty {
  padding: 2rem 1rem;
}

.empty-add-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  margin-top: 0.75rem;
  padding: 0.375rem 0.875rem;
  border: 1px dashed var(--teedy-brand);
  border-radius: 6px;
  background: none;
  cursor: pointer;
  font-size: 0.8125rem;
  color: var(--teedy-brand);
}
.empty-add-btn:hover {
  background: color-mix(in srgb, var(--teedy-brand) 8%, transparent);
}
</style>
