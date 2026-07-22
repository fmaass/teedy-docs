<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import { getFileUrl, getFileContent } from '../api/file'
import { displayName } from '../utils/fileName'
import { createGeneration } from '../utils/staleGuard'

// In-app file preview (#144). The ORIGINAL file URL (getFileUrl without a size) is served
// by the backend as an attachment (Content-Disposition) under a locked-down CSP sandbox —
// a stored-XSS control — so opening it in the browser only ever triggers a download. This
// dialog therefore never embeds that URL; it renders a SAFE, derived representation per
// type and reserves the original URL for one explicit, labelled Download control:
//   image → the size=web raster; PDF → the pdf.js viewer (fetches bytes to a canvas);
//   text  → the extracted size=content text; anything else → a "preview unavailable"
//   state whose only action is Download.
// pdf.js is heavy, so the viewer is loaded on demand into its own chunk.
const PdfViewer = defineAsyncComponent(() => import('./PdfViewer.vue'))

export interface PreviewFile {
  id: string
  name: string | null
  mimetype: string
  rotation?: number
}

const props = defineProps<{
  file: PreviewFile | null
  // Threaded through every preview + Download URL so an anonymous share visitor's reads
  // carry the share credential (?share=<id>).
  shareId?: string
}>()

const visible = defineModel<boolean>('visible', { required: true })

const { t } = useI18n()

type PreviewKind = 'image' | 'pdf' | 'text' | 'unsupported'
function kindOf(mime: string): PreviewKind {
  if (mime.startsWith('image/')) return 'image'
  if (mime === 'application/pdf') return 'pdf'
  if (mime.startsWith('text/')) return 'text'
  return 'unsupported'
}
const kind = computed<PreviewKind>(() => (props.file ? kindOf(props.file.mimetype) : 'unsupported'))

const headerText = computed(() => (props.file ? displayName(props.file.name, t) : ''))

// The original attachment URL — ONLY ever a Download target, never embedded.
const downloadUrl = computed(() => (props.file ? getFileUrl(props.file.id, undefined, props.shareId) : ''))
const imageUrl = computed(() =>
  props.file ? getFileUrl(props.file.id, 'web', props.shareId, props.file.rotation) : '',
)
const pdfUrl = computed(() => (props.file ? getFileUrl(props.file.id, undefined, props.shareId) : ''))

// Every failure mode degrades to the SAME "preview unavailable + Download" state, so no
// preview path can ever leave a broken raster, a partial viewer, or an unlabelled
// original-URL control on screen.
//   - text: extracted content (size=content); a failed/forbidden fetch → unavailable.
//   - image: the size=web raster; an <img> load error → unavailable.
//   - pdf : the pdf.js viewer; a load/render error (emitted by PdfViewer) → unavailable.
const textContent = ref<string | null>(null)
const textFailed = ref(false)
const imageFailed = ref(false)
const pdfFailed = ref(false)

const textGen = createGeneration()
let activeAbort: AbortController | null = null
let currentImageObjectUrl: string | null = null

type PreviewMode = 'image' | 'pdf' | 'text-loading' | 'text' | 'unavailable'
const previewMode = computed<PreviewMode>(() => {
  if (!props.file) return 'unavailable'
  switch (kind.value) {
    case 'image':
      return imageFailed.value ? 'unavailable' : 'image'
    case 'pdf':
      return pdfFailed.value ? 'unavailable' : 'pdf'
    case 'text':
      if (textFailed.value) return 'unavailable'
      return textContent.value === null ? 'text-loading' : 'text'
    default:
      return 'unavailable'
  }
})

async function loadText(file: PreviewFile) {
  const gen = textGen.next()
  activeAbort?.abort()
  const controller = new AbortController()
  activeAbort = controller
  try {
    const content = await getFileContent(file.id, props.shareId)
    if (!textGen.isCurrent(gen)) return
    textContent.value = content
  } catch {
    if (!textGen.isCurrent(gen)) return
    textFailed.value = true
  } finally {
    if (activeAbort === controller) activeAbort = null
  }
}

function revokeImageUrl() {
  if (currentImageObjectUrl) {
    URL.revokeObjectURL(currentImageObjectUrl)
    currentImageObjectUrl = null
  }
}

function resetPreviewState() {
  textGen.next()
  activeAbort?.abort()
  activeAbort = null
  revokeImageUrl()
  textContent.value = null
  textFailed.value = false
  imageFailed.value = false
  pdfFailed.value = false
}

watch(
  () => [visible.value, props.file?.id] as const,
  ([open]) => {
    resetPreviewState()
    if (open && props.file && kind.value === 'text') loadText(props.file)
  },
  { immediate: true },
)

onUnmounted(() => {
  resetPreviewState()
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    dismissableMask
    :header="headerText"
    class="file-preview-dialog"
    :style="{ width: '92vw', maxWidth: '960px' }"
  >
    <div v-if="file" class="file-preview-body">
      <div v-if="previewMode === 'image'" class="file-preview-image-stage">
        <img :src="imageUrl" :alt="headerText" class="file-preview-image" @error="imageFailed = true" />
      </div>

      <!-- downloadable=false: the viewer must NOT expose its own original-URL control; this
           dialog's footer Download is the single Download affordance, and a viewer error is
           degraded to the unavailable state below. -->
      <PdfViewer
        v-else-if="previewMode === 'pdf'"
        :src="pdfUrl"
        :persistable="false"
        :downloadable="false"
        @error="pdfFailed = true"
      />

      <div v-else-if="previewMode === 'text-loading'" class="file-preview-status" role="status">
        <i class="pi pi-spin pi-spinner" aria-hidden="true" />
        <span>{{ t('loading') }}</span>
      </div>
      <pre v-else-if="previewMode === 'text'" class="file-preview-text">{{ textContent }}</pre>

      <!-- Any other case — unsupported type, a failed text/image/pdf load: nothing safe to
           render inline, so the only action offered is an explicit Download of the original. -->
      <div v-else class="file-preview-status file-preview-unavailable">
        <i class="pi pi-file" aria-hidden="true" />
        <span>{{ t('ui.file_view.preview_unavailable') }}</span>
        <a class="file-preview-download file-preview-download-inline" :href="downloadUrl" :download="file.name ?? ''">
          <i class="pi pi-download" aria-hidden="true" />
          <span>{{ t('download') }}</span>
        </a>
      </div>
    </div>

    <template #footer>
      <a
        v-if="file"
        class="file-preview-download"
        :href="downloadUrl"
        :download="file.name ?? ''"
        :aria-label="t('download')"
      >
        <i class="pi pi-download" aria-hidden="true" />
        <span>{{ t('download') }}</span>
      </a>
    </template>
  </Dialog>
</template>

<style scoped>
.file-preview-body {
  display: flex;
  flex-direction: column;
  min-height: 12rem;
}

.file-preview-image-stage {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--p-content-hover-background);
  border-radius: var(--p-content-border-radius, 6px);
  max-height: 70vh;
  overflow: auto;
}
.file-preview-image {
  display: block;
  max-width: 100%;
  max-height: 70vh;
  object-fit: contain;
}

.file-preview-text {
  margin: 0;
  padding: 1rem;
  max-height: 70vh;
  overflow: auto;
  background: var(--p-content-hover-background);
  border-radius: var(--p-content-border-radius, 6px);
  font-size: 0.8125rem;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.file-preview-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding: 2.5rem 1rem;
  color: var(--p-text-muted-color);
  text-align: center;
}
.file-preview-status i {
  font-size: 2rem;
}

/* Download control: the sole affordance that targets the original attachment URL. Styled
   to read as a secondary button in both the footer and the unavailable state. */
.file-preview-download {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.45rem 0.9rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--p-text-color);
  text-decoration: none;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 6px);
  background: var(--p-content-background);
  transition: border-color 0.12s, color 0.12s, background 0.12s;
}
.file-preview-download:hover {
  border-color: var(--p-primary-color);
  color: var(--p-primary-color);
  background: var(--p-content-hover-background);
}
.file-preview-download-inline {
  margin-top: 0.25rem;
}
</style>
