<script setup lang="ts">
import { inject, computed, type Ref } from 'vue'
import DOMPurify from 'dompurify'
import { type DocumentDetail } from '../../api/document'
import { getFileUrl } from '../../api/file'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'

const doc = inject<Ref<DocumentDetail | null>>('document')!

const sanitizedDescription = computed(() => {
  if (!doc.value?.description) return ''
  return DOMPurify.sanitize(doc.value.description)
})

function formatSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}

function isPreviewable(mime: string) {
  return mime.startsWith('image/') || mime === 'application/pdf'
}

function isImage(mime: string) {
  return mime.startsWith('image/')
}
</script>

<template>
  <div v-if="doc">
    <!-- Description -->
    <div v-if="doc.description" class="doc-description" v-html="sanitizedDescription" />

    <!-- Primary file preview -->
    <div v-if="doc.files?.length" class="file-preview-section">
      <template v-for="file in doc.files" :key="file.id">
        <div v-if="isImage(file.mimetype)" class="file-preview teedy-card">
          <img :src="getFileUrl(file.id, 'web')" :alt="file.name" loading="lazy" />
        </div>
        <div v-else-if="file.mimetype === 'application/pdf'" class="file-preview teedy-card">
          <iframe :src="getFileUrl(file.id)" :title="file.name" />
        </div>
      </template>
    </div>

    <!-- File list -->
    <div v-if="doc.files?.length" class="file-list-section">
      <h3>Files ({{ doc.files.length }})</h3>
      <DataTable :value="doc.files" size="small" stripedRows>
        <Column header="Name">
          <template #body="{ data }">
            <a :href="getFileUrl(data.id)" target="_blank" class="flex items-center gap-2">
              <i :class="isImage(data.mimetype) ? 'pi pi-image' : data.mimetype === 'application/pdf' ? 'pi pi-file-pdf' : 'pi pi-file'" />
              {{ data.name }}
            </a>
          </template>
        </Column>
        <Column field="mimetype" header="Type" style="width: 150px">
          <template #body="{ data }">
            <span class="text-xs text-muted">{{ data.mimetype }}</span>
          </template>
        </Column>
        <Column header="Size" style="width: 100px">
          <template #body="{ data }">
            <span class="text-xs text-muted">{{ formatSize(data.size) }}</span>
          </template>
        </Column>
      </DataTable>
    </div>

    <div v-if="!doc.files?.length" class="teedy-empty mt-4">
      <i class="pi pi-file" />
      <p>No files attached to this document</p>
    </div>
  </div>
</template>

<style scoped>
.doc-description {
  margin: 0 0 1.5rem;
  color: #374151;
  line-height: 1.6;
}

.file-preview-section {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.file-preview {
  overflow: hidden;
}
.file-preview img {
  width: 100%;
  display: block;
  border-radius: var(--teedy-card-radius);
}
.file-preview iframe {
  width: 100%;
  height: 500px;
  border: none;
  border-radius: var(--teedy-card-radius);
}

.file-list-section {
  margin-top: 1rem;
}
.file-list-section h3 {
  margin: 0 0 0.75rem;
  font-size: 1rem;
  font-weight: 600;
}
</style>
