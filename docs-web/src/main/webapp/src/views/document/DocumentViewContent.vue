<script setup lang="ts">
import { inject, computed, ref, type Ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import DOMPurify from 'dompurify'
import { type DocumentDetail } from '../../api/document'
import { getFileUrl, deleteFile, renameFile } from '../../api/file'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'

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

function formatSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
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
          <span class="file-size">{{ formatSize(file.size) }}</span>

          <div class="file-actions">
            <template v-if="renamingId === file.id">
              <Button icon="pi pi-check" text rounded size="small" severity="success" @click="commitRename(file.id)" />
              <Button icon="pi pi-times" text rounded size="small" severity="secondary" @click="cancelRename" />
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

    <div v-if="!doc.files?.length" class="teedy-empty mt-4">
      <i class="pi pi-file" />
      <p>No files attached to this document</p>
      <Button
        label="Edit document to add files"
        text
        size="small"
        icon="pi pi-pencil"
        @click="$router.push({ name: 'document-edit', params: { id: doc.id } })"
      />
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

.file-table {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid #f3f4f6;
  transition: background 0.1s;
}
.file-row:last-child {
  border-bottom: none;
}
.file-row:hover {
  background: #f9fafb;
}

.file-type-icon {
  color: #6b7280;
  font-size: 0.9rem;
  flex-shrink: 0;
}

.file-name-cell {
  flex: 1;
  min-width: 0;
}

.file-link {
  font-size: 0.875rem;
  color: #111827;
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
  color: #9ca3af;
  flex-shrink: 0;
  width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  font-size: 0.75rem;
  color: #9ca3af;
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

@media (max-width: 600px) {
  .file-mime {
    display: none;
  }
}
</style>
