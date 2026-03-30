<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery, keepPreviousData } from '@tanstack/vue-query'
import { listTags, getTagFacets, type Tag } from '../../api/tag'
import { listDocuments, type DocumentListItem } from '../../api/document'
import { getFileUrl } from '../../api/file'
import { languageLabel } from '../../constants/languages'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Chip from 'primevue/chip'
import Skeleton from 'primevue/skeleton'
import TagBadge from '../../components/TagBadge.vue'

const router = useRouter()
const route = useRoute()

const selectedTagIds = ref<string[]>([])

// Parse URL query on load
watch(() => route.query.tags, (val) => {
  const raw = (val as string) || ''
  selectedTagIds.value = raw ? raw.split(',').filter(Boolean) : []
}, { immediate: true })

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

const selectedTags = computed(() =>
  selectedTagIds.value.map((id) => tagMap.value.get(id)).filter((t): t is Tag => !!t),
)

const { data: facetData } = useQuery({
  queryKey: computed(() => ['tagFacets', selectedTagIds.value]),
  queryFn: () => getTagFacets(selectedTagIds.value.length ? selectedTagIds.value : undefined).then((r) => r.data),
  staleTime: 15_000,
})

const facets = computed(() => facetData.value?.facets ?? {})
const matchingTotal = computed(() => facetData.value?.total ?? 0)

// Group available tags by parent hierarchy for display
const availableTags = computed(() => {
  const entries = Object.entries(facets.value)
    .map(([id, count]) => ({ tag: tagMap.value.get(id), count }))
    .filter((e): e is { tag: Tag; count: number } => !!e.tag && e.count > 0)
    .sort((a, b) => a.tag.name.localeCompare(b.tag.name))
  return entries
})

// Fetch documents matching ALL selected tags
const searchQuery = computed(() => selectedTags.value.map((t) => `tag:${t.name}`).join(' '))

const { data: documentsData, isLoading: docsLoading } = useQuery({
  queryKey: computed(() => ['browse-docs', searchQuery.value]),
  queryFn: () =>
    listDocuments({
      limit: 100,
      sort_column: 3,
      asc: false,
      search: searchQuery.value || undefined,
    }).then((r) => r.data),
  placeholderData: keepPreviousData,
  enabled: computed(() => selectedTagIds.value.length > 0),
})

const documents = computed(() => documentsData.value?.documents ?? [])

function addTag(tagId: string) {
  const next = [...selectedTagIds.value, tagId]
  syncUrl(next)
}

function removeTag(tagId: string) {
  const next = selectedTagIds.value.filter((id) => id !== tagId)
  syncUrl(next)
}

function clearAll() {
  syncUrl([])
}

function syncUrl(tagIds: string[]) {
  selectedTagIds.value = tagIds
  router.replace({ query: tagIds.length ? { tags: tagIds.join(',') } : {} })
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
  <div class="browse-page">
    <div class="page-header">
      <h1>Browse</h1>
      <p class="page-subtitle" v-if="selectedTagIds.length">
        {{ matchingTotal }} document{{ matchingTotal !== 1 ? 's' : '' }} matching
      </p>
    </div>

    <!-- Selected tags (chips) -->
    <div v-if="selectedTags.length" class="selected-tags">
      <Chip
        v-for="tag in selectedTags"
        :key="tag.id"
        removable
        @remove="removeTag(tag.id)"
      >
        <template #default>
          <span class="chip-dot" :style="{ background: tag.color }" />
          <span class="chip-label">{{ tag.name }}</span>
        </template>
      </Chip>
      <button class="clear-btn" @click="clearAll">Clear all</button>
    </div>

    <!-- Available tags to narrow by -->
    <div v-if="availableTags.length" class="facet-section">
      <h3 class="section-title">{{ selectedTagIds.length ? 'Narrow by' : 'Select a tag to start' }}</h3>
      <div class="tag-grid">
        <div
          v-for="entry in availableTags"
          :key="entry.tag.id"
          class="facet-card"
          @click="addTag(entry.tag.id)"
        >
          <span class="facet-dot" :style="{ background: entry.tag.color }" />
          <span class="facet-name">{{ entry.tag.name }}</span>
          <span class="facet-count">{{ entry.count }}</span>
        </div>
      </div>
    </div>

    <div v-else-if="selectedTagIds.length && !docsLoading" class="no-more-facets">
      <p class="meta">No additional tags to narrow by</p>
    </div>

    <!-- Documents -->
    <template v-if="selectedTagIds.length">
      <div v-if="docsLoading" class="loading-area">
        <Skeleton v-for="i in 5" :key="i" height="3rem" class="mb-2" />
      </div>

      <div v-else-if="documents.length" class="docs-section">
        <h3 class="section-title">Documents</h3>
        <DataTable
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
          <Column field="title" header="Title">
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
              <span class="meta">{{ languageLabel(data.language) }}</span>
            </template>
          </Column>
          <Column field="create_date" header="Created" style="width: 120px">
            <template #body="{ data }">
              <span class="meta">{{ formatDate(data.create_date) }}</span>
            </template>
          </Column>
        </DataTable>
      </div>

      <div v-else class="empty-state">
        <i class="pi pi-inbox" />
        <p>No documents match all selected tags</p>
      </div>
    </template>

    <!-- Root empty state -->
    <div v-if="!selectedTagIds.length && !availableTags.length" class="empty-state">
      <i class="pi pi-folder" />
      <p>Create tags to organize your documents</p>
    </div>
  </div>
</template>

<style scoped>
.browse-page {
  padding: 1.5rem;
  max-width: 1100px;
}

.page-header {
  margin-bottom: 1rem;
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

.selected-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  align-items: center;
  margin-bottom: 1.25rem;
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
.clear-btn {
  background: none;
  border: none;
  color: var(--p-text-muted-color);
  font-size: 0.75rem;
  cursor: pointer;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
}
.clear-btn:hover {
  background: var(--p-content-hover-background);
  color: var(--p-text-color);
}

.facet-section {
  margin-bottom: 1.5rem;
}

.section-title {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--p-text-muted-color);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin: 0 0 0.75rem;
}

.tag-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
}

.facet-card {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.375rem 0.75rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.8125rem;
  transition: background 0.15s, border-color 0.15s;
}
.facet-card:hover {
  background: var(--p-content-hover-background);
  border-color: var(--p-primary-color);
}

.facet-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.facet-name {
  font-weight: 500;
}

.facet-count {
  font-size: 0.6875rem;
  color: var(--p-text-muted-color);
  background: var(--p-content-hover-background);
  padding: 0.0625rem 0.375rem;
  border-radius: 10px;
  min-width: 1.25rem;
  text-align: center;
}

.no-more-facets {
  margin-bottom: 1rem;
}

.docs-section {
  margin-top: 0.5rem;
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

.meta {
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
  margin: 0;
  font-size: 1rem;
}

@media (max-width: 768px) {
  .browse-page {
    padding: 1rem;
  }
}
</style>
