<script setup lang="ts">
import { inject, computed, ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQueryClient } from '@tanstack/vue-query'
import DOMPurify from 'dompurify'
import { type DocumentDetail } from '../../api/document'
import { getFileUrl, deleteFile, renameFile, uploadFile } from '../../api/file'
import PdfViewer from '../../components/PdfViewer.vue'
import EmptyState from '../../components/EmptyState.vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import FileUpload, { type FileUploadUploaderEvent } from 'primevue/fileupload'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import { formatFileSize } from '../../composables/useFormatters'

const doc = inject<Ref<DocumentDetail | null>>('document')!
const { t } = useI18n()
const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

const sanitizedDescription = computed(() => {
  if (!doc.value?.description) return ''
  return DOMPurify.sanitize(doc.value.description)
})

const renamingId = ref<string | null>(null)
const renameValue = ref('')
const uploading = ref(false)
const fileUploadRef = ref()

async function handleUpload(event: FileUploadUploaderEvent) {
  if (!doc.value) return
  uploading.value = true
  try {
    const files = Array.isArray(event.files) ? event.files : [event.files]
    for (const file of files) {
      await uploadFile(doc.value.id, file)
    }
    queryClient.invalidateQueries({ queryKey: ['document', doc.value.id] })
    toast.add({ severity: 'success', summary: t('ui.files_uploaded'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.upload_failed'), life: 3000 })
  } finally {
    uploading.value = false
    fileUploadRef.value?.clear()
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
    toast.add({ severity: 'success', summary: t('ui.file_renamed'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.failed_rename_file'), life: 3000 })
  } finally {
    cancelRename()
  }
}

function confirmDelete(file: { id: string; name: string }) {
  confirm.require({
    message: t('ui.remove_file_confirm', { name: file.name }),
    header: t('ui.remove_file'),
    icon: 'pi pi-trash',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
    accept: async () => {
      try {
        await deleteFile(file.id)
        queryClient.invalidateQueries({ queryKey: ['document', doc.value?.id] })
        toast.add({ severity: 'success', summary: t('ui.file_removed'), life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.failed_remove_file'), life: 3000 })
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
      <h3>{{ t('ui.files_count', { count: doc.files.length }) }}</h3>
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
              <Button icon="pi pi-check" text rounded size="small" severity="success" @click="commitRename(file.id)" :aria-label="t('ui.confirm_rename')" />
              <Button icon="pi pi-times" text rounded size="small" severity="secondary" @click="cancelRename" :aria-label="t('ui.cancel_rename')" />
            </template>
            <template v-else>
              <Button
                icon="pi pi-pencil"
                text
                rounded
                size="small"
                severity="secondary"
                @click="startRename(file)"
                v-tooltip="t('rename')"
                :aria-label="t('rename')"
              />
              <Button
                icon="pi pi-trash"
                text
                rounded
                size="small"
                severity="danger"
                @click="confirmDelete(file)"
                v-tooltip="t('ui.remove_file')"
                :aria-label="t('ui.remove_file')"
              />
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Upload zone -->
    <FileUpload
      ref="fileUploadRef"
      mode="advanced"
      multiple
      customUpload
      auto
      :showUploadButton="false"
      :showCancelButton="false"
      :disabled="uploading"
      @uploader="handleUpload"
      class="view-file-upload"
    >
      <template #empty>
        <div class="file-upload-empty">
          <i class="pi pi-cloud-upload" aria-hidden="true" />
          <span v-if="uploading">{{ t('ui.uploading') }}</span>
          <span v-else>{{ t('ui.drag_or_choose_upload') }}</span>
        </div>
      </template>
    </FileUpload>

    <EmptyState
      v-if="!doc.files?.length"
      icon="pi pi-file"
      :message="t('ui.no_files')"
      :action-label="t('ui.edit_to_add_files')"
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
  border-bottom: 1px solid var(--p-content-border-color);
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
.file-row:hover .file-actions,
.file-row:focus-within .file-actions {
  opacity: 1;
}

.view-file-upload {
  margin-top: 1rem;
}

.file-upload-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 0.75rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
.file-upload-empty i {
  font-size: 1.25rem;
}

@media (max-width: 600px) {
  .file-mime {
    display: none;
  }
}
</style>
