<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getDocument, deleteDocument, type DocumentDetail } from '../../api/document'
import { getFileUrl } from '../../api/file'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import Card from 'primevue/card'
import { useToast } from 'primevue/usetoast'

const props = defineProps<{ id: string }>()
const router = useRouter()
const toast = useToast()

const doc = ref<DocumentDetail | null>(null)
const loading = ref(true)

function formatDate(timestamp: number) {
  return new Date(timestamp).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}

function formatSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function isImage(mimetype: string) {
  return mimetype.startsWith('image/')
}

function isPdf(mimetype: string) {
  return mimetype === 'application/pdf'
}

async function handleDelete() {
  if (!confirm('Delete this document?')) return
  try {
    await deleteDocument(props.id)
    toast.add({ severity: 'success', summary: 'Document deleted', life: 2000 })
    router.push({ name: 'documents' })
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to delete document', life: 3000 })
  }
}

onMounted(async () => {
  try {
    const { data } = await getDocument(props.id)
    doc.value = data
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-if="loading" style="text-align: center; padding: 3rem">
    <i class="pi pi-spin pi-spinner" style="font-size: 2rem" />
  </div>
  <div v-else-if="doc" class="document-view">
    <div class="doc-header">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h1>{{ doc.title }}</h1>
      <div class="doc-actions">
        <Button
          icon="pi pi-pencil"
          label="Edit"
          severity="secondary"
          outlined
          @click="router.push({ name: 'document-edit', params: { id: doc.id } })"
        />
        <Button
          icon="pi pi-trash"
          label="Delete"
          severity="danger"
          outlined
          @click="handleDelete"
        />
      </div>
    </div>

    <div class="doc-content">
      <Card class="doc-meta">
        <template #content>
          <div class="meta-grid">
            <div v-if="doc.description" class="meta-item full">
              <label>Description</label>
              <p>{{ doc.description }}</p>
            </div>
            <div v-if="doc.create_date" class="meta-item">
              <label>Created</label>
              <p>{{ formatDate(doc.create_date) }}</p>
            </div>
            <div v-if="doc.update_date" class="meta-item">
              <label>Updated</label>
              <p>{{ formatDate(doc.update_date) }}</p>
            </div>
            <div v-if="doc.language" class="meta-item">
              <label>Language</label>
              <p>{{ doc.language }}</p>
            </div>
            <div v-if="doc.creator" class="meta-item">
              <label>Creator</label>
              <p>{{ doc.creator }}</p>
            </div>
            <div v-if="doc.subject" class="meta-item">
              <label>Subject</label>
              <p>{{ doc.subject }}</p>
            </div>
            <div v-if="doc.type" class="meta-item">
              <label>Type</label>
              <p>{{ doc.type }}</p>
            </div>
          </div>
          <div v-if="doc.tags?.length" class="meta-tags">
            <Tag
              v-for="tag in doc.tags"
              :key="tag.id"
              :value="tag.name"
              :style="{ background: tag.color, color: '#fff' }"
              rounded
            />
          </div>
        </template>
      </Card>

      <Card v-if="doc.files?.length" class="doc-files">
        <template #title>Files</template>
        <template #content>
          <div v-for="file in doc.files" :key="file.id" class="file-item">
            <div class="file-info">
              <i class="pi pi-file" />
              <a :href="getFileUrl(file.id)" target="_blank">{{ file.name }}</a>
              <span class="file-size">{{ formatSize(file.size) }}</span>
            </div>
            <div v-if="isImage(file.mimetype)" class="file-preview">
              <img :src="getFileUrl(file.id, 'web')" :alt="file.name" />
            </div>
            <div v-else-if="isPdf(file.mimetype)" class="file-preview">
              <iframe :src="getFileUrl(file.id)" />
            </div>
          </div>
        </template>
      </Card>
    </div>
  </div>
</template>

<style scoped>
.document-view {
  max-width: 900px;
}
.doc-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1.5rem;
}
.doc-header h1 {
  flex: 1;
  margin: 0;
  font-size: 1.5rem;
}
.doc-actions {
  display: flex;
  gap: 0.5rem;
}
.doc-content {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}
.meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}
.meta-item.full {
  grid-column: 1 / -1;
}
.meta-item label {
  display: block;
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--p-text-muted-color);
  margin-bottom: 0.25rem;
}
.meta-item p {
  margin: 0;
}
.meta-tags {
  display: flex;
  gap: 0.375rem;
  margin-top: 1rem;
}
.file-item {
  padding: 0.75rem 0;
  border-bottom: 1px solid var(--p-surface-200);
}
.file-item:last-child {
  border-bottom: none;
}
.file-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.file-info a {
  font-weight: 500;
  color: var(--p-primary-color);
  text-decoration: none;
}
.file-info a:hover {
  text-decoration: underline;
}
.file-size {
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
}
.file-preview {
  margin-top: 0.75rem;
}
.file-preview img {
  max-width: 100%;
  border-radius: 6px;
}
.file-preview iframe {
  width: 100%;
  height: 600px;
  border: 1px solid var(--p-surface-200);
  border-radius: 6px;
}
</style>
