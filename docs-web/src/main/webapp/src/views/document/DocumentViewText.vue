<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { getFileContent, getFileList, reprocessFile } from '../../api/file'
import { shouldPoll, createProcessingPoller } from '../../utils/fileProcessing'
import { displayName } from '../../utils/fileName'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import EmptyState from '../../components/EmptyState.vue'
import { useToast } from 'primevue/usetoast'
import { injectDocument } from './documentKey'

const { t } = useI18n()
const doc = injectDocument()
const toast = useToast()

interface FileText {
  fileId: string
  fileName: string
  mimetype: string
  content: string | null
  loading: boolean
  processing: boolean
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

/**
 * One poll: read the live processing flags from GET /file/list, update each
 * file, reload the content of any file that just finished processing, and report
 * whether another poll is warranted. This replaces the previous fixed 3s
 * setTimeout guess with the backend's real `processing` signal.
 */
const poller = createProcessingPoller(async (isDisposed) => {
  const documentId = doc.value?.id
  if (!documentId) return false

  let items
  try {
    items = await getFileList(documentId)
  } catch {
    // Transient failure — keep polling while local state still says processing.
    return shouldPoll(fileTexts.value)
  }

  // The await above may have resolved after unmount; bail before touching state.
  if (isDisposed()) return false

  const byId = new Map(items.map((f) => [f.id, f]))
  for (const ft of fileTexts.value) {
    const next = byId.get(ft.fileId)
    if (!next) continue
    const wasProcessing = ft.processing
    ft.processing = next.processing === true
    // Reload content when a file transitions from processing -> done.
    if (wasProcessing && !ft.processing) {
      loadContent(ft)
    }
  }

  return shouldPoll(fileTexts.value)
})

/** Start polling now if any file is processing and no timer is armed. */
function ensurePolling() {
  poller.ensurePolling(shouldPoll(fileTexts.value))
}

watch(() => doc.value?.files, (files) => {
  poller.stop()
  if (!files?.length) {
    fileTexts.value = []
    return
  }
  fileTexts.value = files.map((f) => ({
    fileId: f.id,
    // A file name is serialized nullable; render the stable localized fallback so a null-name file
    // shows a label (not a blank) in both the header and the reprocess toast.
    fileName: displayName(f.name, t),
    mimetype: f.mimetype,
    content: null,
    loading: true,
    // The document detail's files carry the same backend `processing` flag.
    processing: (f as { processing?: boolean }).processing === true,
  }))
  fileTexts.value.forEach(loadContent)
  ensurePolling()
}, { immediate: true })

onUnmounted(() => {
  poller.dispose()
})

async function handleReprocess(ft: FileText) {
  reprocessingId.value = ft.fileId
  try {
    await reprocessFile(ft.fileId)
    // The backend now reports this file as processing; reflect it immediately
    // and let the poller clear it and reload content when extraction finishes.
    ft.processing = true
    toast.add({ severity: 'info', summary: t('ui.reprocess_queued', { name: ft.fileName }), life: 4000 })
    ensurePolling()
  } catch {
    toast.add({ severity: 'error', summary: t('ui.reprocess_failed'), life: 3000 })
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
      {{ t('ui.text_hint') }}
    </p>

    <EmptyState v-if="!doc.files?.length" icon="pi pi-file" :message="t('ui.no_files')" />

    <div v-for="ft in fileTexts" :key="ft.fileId" class="file-text-block">
      <div class="file-text-header">
        <div class="file-text-info">
          <i :class="fileIcon(ft.mimetype)" class="file-text-icon" />
          <span class="file-text-name">{{ ft.fileName }}</span>
          <span
            v-if="ft.processing"
            class="status-badge status-processing"
            role="status"
            aria-live="polite"
            v-tooltip="t('ui.processing_tooltip')"
          >
            <i class="pi pi-spin pi-spinner processing-icon" aria-hidden="true" />
            {{ t('ui.processing') }}
          </span>
          <span
            v-else-if="!ft.loading"
            class="status-badge"
            :class="hasContent(ft) ? 'status-ok' : 'status-empty'"
          >
            {{ hasContent(ft) ? t('ui.text_extracted') : t('ui.no_text') }}
          </span>
        </div>
        <Button
          icon="pi pi-sync"
          :label="t('ui.reprocess')"
          text
          size="small"
          severity="secondary"
          :loading="reprocessingId === ft.fileId"
          :disabled="ft.processing"
          @click="handleReprocess(ft)"
          v-tooltip="t('ui.reprocess_tooltip')"
        />
      </div>

      <div v-if="ft.processing" class="file-text-processing" role="status" aria-live="polite">
        <span class="file-text-processing-label">
          <i class="pi pi-spin pi-spinner processing-icon" aria-hidden="true" />
          {{ t('ui.processing') }}
        </span>
        <Skeleton height="1rem" class="mb-2" />
        <Skeleton height="1rem" width="80%" class="mb-2" />
        <Skeleton height="1rem" width="60%" />
      </div>
      <div v-else-if="ft.loading" class="file-text-loading">
        <Skeleton height="1rem" class="mb-2" />
        <Skeleton height="1rem" width="80%" class="mb-2" />
        <Skeleton height="1rem" width="60%" />
      </div>
      <pre v-else-if="hasContent(ft)" class="file-text-content">{{ ft.content }}</pre>
      <div v-else class="file-text-empty">
        <i class="pi pi-info-circle" />
        <span>{{ t('ui.no_text_hint') }}</span>
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
  color: var(--p-text-muted-color);
}

.file-text-block {
  border: 1px solid var(--p-content-border-color);
  border-radius: 8px;
  overflow: hidden;
}

.file-text-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.625rem 0.875rem;
  background: var(--p-content-hover-background);
  border-bottom: 1px solid var(--p-content-border-color);
}

.file-text-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-width: 0;
}

.file-text-icon {
  color: var(--p-text-muted-color);
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
  background: var(--teedy-success-bg);
  color: var(--teedy-success-text);
}
.status-empty {
  background: var(--teedy-warning-bg);
  color: var(--teedy-warning-text);
}
.status-processing {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  background: var(--p-primary-100, var(--p-content-hover-background));
  color: var(--p-primary-color);
}
.processing-icon {
  font-size: 0.75rem;
  color: var(--p-primary-color);
}

.file-text-processing {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 1.5rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
.file-text-processing-label {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  color: var(--p-primary-color);
  font-weight: 500;
}

.file-text-loading {
  padding: 1.5rem;
  text-align: center;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
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
  color: var(--p-text-color);
  background: var(--p-content-hover-background);
}

.file-text-empty {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 1rem 0.875rem;
  font-size: 0.8125rem;
  color: var(--teedy-warning-text);
  background: var(--teedy-warning-bg);
}
.file-text-empty i {
  flex-shrink: 0;
  margin-top: 0.1rem;
}
</style>
