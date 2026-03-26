<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listDocuments, type DocumentListItem } from '../../api/document'
import { useTagStore } from '../../stores/tags'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Tag from 'primevue/tag'

const router = useRouter()
const tagStore = useTagStore()

const documents = ref<DocumentListItem[]>([])
const totalRecords = ref(0)
const loading = ref(false)
const search = ref('')
const first = ref(0)
const rows = ref(20)

async function loadDocuments() {
  loading.value = true
  try {
    const { data } = await listDocuments({
      offset: first.value,
      limit: rows.value,
      sort_column: 3,
      asc: false,
      search: search.value || undefined,
    })
    documents.value = data.documents
    totalRecords.value = data.total
  } finally {
    loading.value = false
  }
}

function onPage(event: any) {
  first.value = event.first
  rows.value = event.rows
  loadDocuments()
}

function openDocument(doc: DocumentListItem) {
  router.push({ name: 'document-view', params: { id: doc.id } })
}

function formatDate(timestamp: number) {
  return new Date(timestamp).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

let searchTimeout: ReturnType<typeof setTimeout>
watch(search, () => {
  clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    first.value = 0
    loadDocuments()
  }, 300)
})

onMounted(loadDocuments)
</script>

<template>
  <div class="document-list">
    <div class="list-header">
      <h2>Documents</h2>
      <div class="list-actions">
        <span class="p-input-icon-left">
          <i class="pi pi-search" />
          <InputText
            v-model="search"
            placeholder="Search documents..."
            style="width: 300px"
          />
        </span>
        <Button
          icon="pi pi-plus"
          label="New Document"
          @click="router.push({ name: 'document-add' })"
        />
      </div>
    </div>

    <DataTable
      :value="documents"
      :loading="loading"
      :paginator="true"
      :rows="rows"
      :totalRecords="totalRecords"
      :lazy="true"
      :first="first"
      @page="onPage"
      @row-click="(e: any) => openDocument(e.data)"
      selectionMode="single"
      dataKey="id"
      stripedRows
      class="doc-table"
    >
      <Column field="title" header="Title" style="min-width: 250px">
        <template #body="{ data }">
          <div class="doc-title">{{ data.title }}</div>
          <div v-if="data.tags?.length" class="doc-tags">
            <Tag
              v-for="tag in data.tags"
              :key="tag.id"
              :value="tag.name"
              :style="{ background: tag.color, color: '#fff' }"
              rounded
            />
          </div>
        </template>
      </Column>
      <Column header="Date" style="width: 130px">
        <template #body="{ data }">
          {{ formatDate(data.create_date) }}
        </template>
      </Column>
      <Column header="Files" style="width: 80px; text-align: center">
        <template #body="{ data }">
          {{ data.file_count }}
        </template>
      </Column>
      <Column header="Shared" style="width: 80px; text-align: center">
        <template #body="{ data }">
          <i v-if="data.shared" class="pi pi-link" />
        </template>
      </Column>
      <template #empty>
        <div style="text-align: center; padding: 2rem; color: var(--p-text-muted-color)">
          No documents found.
        </div>
      </template>
    </DataTable>

    <div v-if="tagStore.tags.length" class="tag-sidebar">
      <h3>Tags</h3>
      <div
        v-for="tag in tagStore.tags"
        :key="tag.id"
        class="tag-item"
        @click="search = 'tag:' + tag.name"
      >
        <span class="tag-dot" :style="{ background: tag.color }" />
        {{ tag.name }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.document-list {
  display: grid;
  grid-template-columns: 1fr 220px;
  grid-template-rows: auto 1fr;
  gap: 1.5rem;
}
.list-header {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.list-header h2 {
  margin: 0;
}
.list-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
}
.doc-table {
  cursor: pointer;
}
.doc-title {
  font-weight: 500;
}
.doc-tags {
  display: flex;
  gap: 0.25rem;
  margin-top: 0.25rem;
}
.tag-sidebar {
  background: var(--p-surface-0);
  border-radius: 8px;
  padding: 1rem;
  align-self: start;
}
.tag-sidebar h3 {
  margin: 0 0 0.75rem;
  font-size: 0.875rem;
  color: var(--p-text-muted-color);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.tag-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.375rem 0;
  cursor: pointer;
  font-size: 0.875rem;
}
.tag-item:hover {
  color: var(--p-primary-color);
}
.tag-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .document-list {
    grid-template-columns: 1fr;
  }
  .tag-sidebar {
    display: none;
  }
}
</style>
