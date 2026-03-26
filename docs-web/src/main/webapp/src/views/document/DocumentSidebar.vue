<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery, keepPreviousData } from '@tanstack/vue-query'
import { listDocuments, type DocumentListItem } from '../../api/document'
import { listTags } from '../../api/tag'
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
          placeholder="Search..."
          class="w-full"
          size="small"
        />
      </span>
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
          <div class="teedy-doc-item-header">
            <span class="teedy-doc-item-title">{{ doc.title }}</span>
            <span v-if="doc.file_count" class="teedy-doc-item-meta">({{ doc.file_count }})</span>
            <i v-if="doc.shared" class="pi pi-link teedy-doc-item-meta" />
          </div>
          <div class="flex items-center gap-2">
            <span class="teedy-doc-item-meta">{{ formatDate(doc.create_date) }}</span>
            <div v-if="doc.tags?.length" class="teedy-doc-item-tags">
              <TagBadge v-for="tag in doc.tags" :key="tag.id" :name="tag.name" :color="tag.color" />
            </div>
          </div>
        </div>
        <div v-if="!documents.length" class="teedy-empty">
          <i class="pi pi-search" />
          <p>No documents found</p>
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

.sidebar-doclist {
  flex: 1;
  overflow-y: auto;
  border-top: 1px solid #e5e7eb;
  margin-top: 0.25rem;
}
</style>
