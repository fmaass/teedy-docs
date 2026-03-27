<script setup lang="ts">
import { inject, ref, watch, type Ref } from 'vue'
import { type DocumentDetail } from '../../api/document'
import { getFileContent, reprocessFile } from '../../api/file'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import { useToast } from 'primevue/usetoast'

const doc = inject<Ref<DocumentDetail | null>>('document')!
const toast = useToast()

interface FileText {
  fileId: string
  fileName: string
  mimetype: string
  content: string | null
  loading: boolean
}

const fileTexts = ref<FileText[]>([])
const reprocessingId = ref<string | null>(null)

async function loadContent(ft: FileText) {
  ft.loading = true
  try {
    ft.content = await getFileContent(ft.fileId)
  } catch {
    ft.content = null
  } finally {
    ft.loading = false
  }
}

watch(() => doc.value?.files, (files) => {
  if (!files?.length) {
    fileTexts.value = []
    return
  }
  fileTexts.value = files.map((f) => ({
    fileId: f.id,
    fileName: f.name,
    mimetype: f.mimetype,
    content: null,
    loading: true,
  }))
  fileTexts.value.forEach(loadContent)
}, { immediate: true })

async function handleReprocess(ft: FileText) {
  reprocessingId.value = ft.fileId
  try {
    await reprocessFile(ft.fileId)
    toast.add({ severity: 'info', summary: `"${ft.fileName}" queued for reprocessing. Refresh in a few seconds.`, life: 4000 })
    setTimeout(() => loadContent(ft), 3000)
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to reprocess file', life: 3000 })
  } finally {
    reprocessingId.value = null
  }
}

function hasContent(ft: FileText): boolean {
  return !!ft.content?.trim()
}

function fileIcon(mime: string) {
  if (mime.startsWith('image/')) return 'pi pi-image'
  if (mime === 'application/pdf') return 'pi pi-file-pdf'
  return 'pi pi-file'
}
</script>

<template>
  <div v-if="doc" class="text-view">
    <p class="text-view-hint">
      Text extracted from each file via OCR or direct text parsing. This is what powers full-text search.
    </p>

    <div v-if="!doc.files?.length" class="teedy-empty mt-4">
      <i class="pi pi-file" />
      <p>No files attached to this document</p>
    </div>

    <div v-for="ft in fileTexts" :key="ft.fileId" class="file-text-block">
      <div class="file-text-header">
        <div class="file-text-info">
          <i :class="fileIcon(ft.mimetype)" class="file-text-icon" />
          <span class="file-text-name">{{ ft.fileName }}</span>
          <span
            v-if="!ft.loading"
            class="status-badge"
            :class="hasContent(ft) ? 'status-ok' : 'status-empty'"
          >
            {{ hasContent(ft) ? 'Text extracted' : 'No text' }}
          </span>
        </div>
        <Button
          icon="pi pi-sync"
          label="Reprocess"
          text
          size="small"
          severity="secondary"
          :loading="reprocessingId === ft.fileId"
          @click="handleReprocess(ft)"
          v-tooltip="'Re-run OCR / text extraction'"
        />
      </div>

      <div v-if="ft.loading" class="file-text-loading">
        <Skeleton height="1rem" class="mb-2" />
        <Skeleton height="1rem" width="80%" class="mb-2" />
        <Skeleton height="1rem" width="60%" />
      </div>
      <pre v-else-if="hasContent(ft)" class="file-text-content">{{ ft.content }}</pre>
      <div v-else class="file-text-empty">
        <i class="pi pi-info-circle" />
        <span>No text was extracted from this file. Check that the document language matches the file content and try reprocessing.</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.text-view {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.text-view-hint {
  margin: 0;
  font-size: 0.8125rem;
  color: #6b7280;
}

.file-text-block {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.file-text-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.625rem 0.875rem;
  background: #f9fafb;
  border-bottom: 1px solid #e5e7eb;
}

.file-text-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-width: 0;
}

.file-text-icon {
  color: #6b7280;
  font-size: 0.875rem;
  flex-shrink: 0;
}

.file-text-name {
  font-size: 0.875rem;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-badge {
  font-size: 0.6875rem;
  font-weight: 600;
  padding: 0.1rem 0.5rem;
  border-radius: 999px;
  flex-shrink: 0;
  white-space: nowrap;
}
.status-ok {
  background: #d1fae5;
  color: #065f46;
}
.status-empty {
  background: #fef3c7;
  color: #92400e;
}

.file-text-loading {
  padding: 1.5rem;
  text-align: center;
  font-size: 0.8125rem;
  color: #9ca3af;
}

.file-text-content {
  margin: 0;
  padding: 0.875rem;
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
  font-size: 0.8125rem;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 400px;
  overflow-y: auto;
  color: #1f2937;
  background: #fafafa;
}

.file-text-empty {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 1rem 0.875rem;
  font-size: 0.8125rem;
  color: #92400e;
  background: #fffbeb;
}
.file-text-empty i {
  flex-shrink: 0;
  margin-top: 0.1rem;
}
</style>
