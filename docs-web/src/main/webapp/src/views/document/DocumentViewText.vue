<script setup lang="ts">
import { inject, ref, watch, onUnmounted, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { type DocumentDetail } from '../../api/document'
import { getFileContent, getFileList, reprocessFile } from '../../api/file'
import { shouldPoll } from '../../utils/fileProcessing'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import ProgressSpinner from 'primevue/progressspinner'
import EmptyState from '../../components/EmptyState.vue'
import { useToast } from 'primevue/usetoast'

/** How often to re-poll /file/list while any file is still processing. */
const POLL_INTERVAL_MS = 2500

const { t } = useI18n()
const doc = inject<Ref<DocumentDetail | null>>('document')!
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

let pollTimer: ReturnType<typeof setTimeout> | null = null

function stopPolling() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

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
 * Poll GET /file/list once, update each file's live processing flag, reload the
 * content of any file that just finished processing, and re-arm the timer only
 * while something is still processing. This replaces the previous fixed 3s
 * setTimeout guess with the backend's real `processing` signal.
 */
async function pollProcessing() {
  const documentId = doc.value?.id
  if (!documentId) return

  let items
  try {
    items = await getFileList(documentId)
  } catch {
    // Transient failure — try again on the next tick if we were polling.
    if (shouldPoll(fileTexts.value)) armPoll()
    return
  }

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

  if (shouldPoll(fileTexts.value)) armPoll()
}

function armPoll() {
  stopPolling()
  pollTimer = setTimeout(pollProcessing, POLL_INTERVAL_MS)
}

/** Start polling now if any file is processing and no timer is armed. */
function ensurePolling() {
  if (pollTimer === null && shouldPoll(fileTexts.value)) armPoll()
}

watch(() => doc.value?.files, (files) => {
  stopPolling()
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
    // The document detail's files carry the same backend `processing` flag.
    processing: (f as { processing?: boolean }).processing === true,
  }))
  fileTexts.value.forEach(loadContent)
  ensurePolling()
}, { immediate: true })

onUnmounted(stopPolling)

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
            v-tooltip="t('ui.processing_tooltip')"
          >
            <ProgressSpinner class="processing-spinner" stroke-width="6" />
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

      <div v-if="ft.processing" class="file-text-processing">
        <ProgressSpinner class="processing-spinner-lg" stroke-width="5" />
        <span>{{ t('ui.processing') }}</span>
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
.processing-spinner {
  width: 0.85rem;
  height: 0.85rem;
}
.processing-spinner :deep(.p-progressspinner-circle) {
  stroke: var(--p-primary-color);
  animation-duration: 1.4s;
}

.file-text-processing {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding: 1.5rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
.processing-spinner-lg {
  width: 1.5rem;
  height: 1.5rem;
}
.processing-spinner-lg :deep(.p-progressspinner-circle) {
  stroke: var(--p-primary-color);
  animation-duration: 1.4s;
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
