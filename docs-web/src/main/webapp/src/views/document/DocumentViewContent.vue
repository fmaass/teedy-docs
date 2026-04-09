<script setup lang="ts">
import { inject, computed, ref, type Ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import DOMPurify from 'dompurify'
import { type DocumentDetail } from '../../api/document'
import { getFileUrl, deleteFile, renameFile, uploadFile } from '../../api/file'
import PdfViewer from '../../components/PdfViewer.vue'
import EmptyState from '../../components/EmptyState.vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import { formatFileSize } from '../../composables/useFormatters'

const doc = inject<Ref<DocumentDetail | null>>('document')!
const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

const sanitizedDescription = computed(() => {
  if (!doc.value?.description) return ''
  return DOMPurify.sanitize(doc.value.description)
})

const renamingId = ref<string | null>(null)
const renameValue = ref('')
const isDragging = ref(false)
const uploading = ref(false)

async function onDropUpload(e: DragEvent) {
  isDragging.value = false
  if (!e.dataTransfer?.files?.length || !doc.value) return
  uploading.value = true
  try {
    for (const file of Array.from(e.dataTransfer.files)) {
      await uploadFile(doc.value.id, file)
    }
    queryClient.invalidateQueries({ queryKey: ['document', doc.value.id] })
    toast.add({ severity: 'success', summary: 'Files uploaded', life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: 'Upload failed', life: 3000 })
  } finally {
    uploading.value = false
  }
}

function isImage(mime: string) {
  return mime.startsWith('image/')
}

function fileIcon(mime: string) {
  if (mime.startsWith('image/')) return 'pi pi-image'
  if (mime === 'application/pdf') return 'pi pi-file-pdf'
  return 'pi pi-file'
}

function startRename(file: { id: string; name: string }) {
  renamingId.value = file.id
  renameValue.value = file.name
}

function cancelRename() {
  renamingId.value = null
  renameValue.value = ''
}

async function commitRename(fileId: string) {
  const name = renameValue.value.trim()
  if (!name) return cancelRename()
  try {
    await renameFile(fileId, name)
    queryClient.invalidateQueries({ queryKey: ['document', doc.value?.id] })
    toast.add({ severity: 'success', summary: 'File renamed', life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to rename file', life: 3000 })
  } finally {
    cancelRename()
  }
}

function confirmDelete(file: { id: string; name: string }) {
  confirm.require({
    message: `Remove "${file.name}" from this document?`,
    header: 'Remove file',
    icon: 'pi pi-trash',
    acceptClass: 'p-button-danger',
    accept: async () => {
      try {
        await deleteFile(file.id)
        queryClient.invalidateQueries({ queryKey: ['document', doc.value?.id] })
        toast.add({ severity: 'success', summary: 'File removed', life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: 'Failed to remove file', life: 3000 })
      }
    },
  })
}

</script>

<template>
  <div v-if="doc">
    <!-- Description -->
    <div v-if="doc.description" class="doc-description" v-html="sanitizedDescription" />

    <!-- File previews -->
    <div v-if="doc.files?.length" class="file-preview-grid">
      <template v-for="file in doc.files" :key="file.id">
        <div v-if="isImage(file.mimetype)" class="file-preview-card">
          <img :src="getFileUrl(file.id, 'web')" :alt="file.name" loading="lazy" />
          <div class="file-preview-label">{{ file.name }}</div>
        </div>
        <div v-else-if="file.mimetype === 'application/pdf'" class="file-preview-card">
          <PdfViewer :src="getFileUrl(file.id)" />
          <div class="file-preview-label">{{ file.name }}</div>
        </div>
      </template>
    </div>

    <!-- File list -->
    <div v-if="doc.files?.length" class="file-list-section">
      <h3>Files ({{ doc.files.length }})</h3>
      <div class="file-table">
        <div v-for="file in doc.files" :key="file.id" class="file-row">
          <i :class="fileIcon(file.mimetype)" class="file-type-icon" />

          <!-- Name: either link or rename input -->
          <div class="file-name-cell">
            <template v-if="renamingId === file.id">
              <InputText
                v-model="renameValue"
                size="small"
                class="rename-input"
                @keyup.enter="commitRename(file.id)"
                @keyup.escape="cancelRename"
                autofocus
              />
            </template>
            <a v-else :href="getFileUrl(file.id)" target="_blank" class="file-link">
              {{ file.name }}
            </a>
          </div>

          <span class="file-mime">{{ file.mimetype }}</span>
          <span class="file-size">{{ formatFileSize(file.size) }}</span>

          <div class="file-actions">
            <template v-if="renamingId === file.id">
              <Button icon="pi pi-check" text rounded size="small" severity="success" @click="commitRename(file.id)" aria-label="Confirm rename" />
              <Button icon="pi pi-times" text rounded size="small" severity="secondary" @click="cancelRename" aria-label="Cancel rename" />
            </template>
            <template v-else>
              <Button
                icon="pi pi-pencil"
                text
                rounded
                size="small"
                severity="secondary"
                @click="startRename(file)"
                v-tooltip="'Rename'"
              />
              <Button
                icon="pi pi-trash"
                text
                rounded
                size="small"
                severity="danger"
                @click="confirmDelete(file)"
                v-tooltip="'Remove'"
              />
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Drop zone for immediate upload -->
    <div
      class="view-drop-zone"
      :class="{ 'view-drop-zone--active': isDragging, 'view-drop-zone--uploading': uploading }"
      @dragover.prevent="isDragging = true"
      @dragenter.prevent="isDragging = true"
      @dragleave.prevent="isDragging = false"
      @drop.prevent="onDropUpload"
    >
      <i class="pi pi-cloud-upload" />
      <span v-if="uploading">Uploading...</span>
      <span v-else-if="isDragging">Drop files to upload</span>
      <span v-else>Drag files here to upload</span>
    </div>

    <EmptyState
      v-if="!doc.files?.length"
      icon="pi pi-file"
      message="No files attached to this document"
      action-label="Edit document to add files"
      @action="$router.push({ name: 'document-edit', params: { id: doc.id } })"
    />
  </div>
</template>

<style scoped>
.doc-description {
  margin: 0 0 1.5rem;
  color: var(--p-text-color);
  line-height: 1.6;
}

.file-preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.file-preview-card {
  overflow: hidden;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 6px);
  background: var(--p-content-background);
}
.file-preview-card img {
  width: 100%;
  display: block;
  max-height: 400px;
  object-fit: contain;
  background: var(--p-content-hover-background);
}

.file-preview-label {
  padding: 0.375rem 0.625rem;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  border-top: 1px solid var(--p-content-border-color);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-list-section {
  margin-top: 1rem;
}
.file-list-section h3 {
  margin: 0 0 0.75rem;
  font-size: 1rem;
  font-weight: 600;
}

.file-table {
  border: 1px solid var(--p-content-border-color);
  border-radius: 8px;
  overflow: hidden;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--p-content-border-color);
  transition: background 0.1s;
}
.file-row:last-child {
  border-bottom: none;
}
.file-row:hover {
  background: var(--p-content-hover-background);
}

.file-type-icon {
  color: var(--p-text-muted-color);
  font-size: 0.9rem;
  flex-shrink: 0;
}

.file-name-cell {
  flex: 1;
  min-width: 0;
}

.file-link {
  font-size: 0.875rem;
  color: var(--p-text-color);
  text-decoration: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: block;
}
.file-link:hover {
  color: var(--teedy-brand);
  text-decoration: underline;
}

.rename-input {
  width: 100%;
  font-size: 0.875rem;
}

.file-mime {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  flex-shrink: 0;
  width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  flex-shrink: 0;
  width: 70px;
  text-align: right;
}

.file-actions {
  display: flex;
  gap: 0.125rem;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s;
}
.file-row:hover .file-actions {
  opacity: 1;
}

.view-drop-zone {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  margin-top: 1rem;
  padding: 0.75rem;
  border: 2px dashed var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 6px);
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  transition: border-color 0.15s, background 0.15s;
}
.view-drop-zone--active {
  border-color: var(--p-primary-color);
  color: var(--p-primary-color);
  background: color-mix(in srgb, var(--p-primary-color) 5%, transparent);
}
.view-drop-zone--uploading {
  opacity: 0.6;
  pointer-events: none;
}

@media (max-width: 600px) {
  .file-mime {
    display: none;
  }
}
</style>
