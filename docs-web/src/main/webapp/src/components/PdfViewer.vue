<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import * as pdfjsLib from 'pdfjs-dist'
import Button from 'primevue/button'

const { t } = useI18n()

pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).toString()

const props = withDefaults(
  defineProps<{
    src: string
    maxHeight?: number
    // The persisted rotation to render with initially (the PDF original is NOT baked; pdf.js
    // applies this to the viewport). Seeded from the file's stored rotation by the parent.
    initialRotation?: number
    // When true, the rotate controls emit a `rotate` event the parent persists. When false
    // (read-only/share), the controls are hidden.
    persistable?: boolean
    // Whether to render this viewer's own Download control for the original file. The `src`
    // is the original file URL, which the backend serves as an attachment — so this control
    // is always labelled Download, never "open" (#144). A parent that provides its own
    // explicit Download (e.g. the preview dialog) sets this false to avoid a duplicate.
    downloadable?: boolean
  }>(),
  { initialRotation: 0, persistable: false, downloadable: true },
)

// `error` lets a parent degrade to its own fallback (e.g. the preview dialog's
// "preview unavailable + Download" state) instead of showing this viewer's error UI.
const emit = defineEmits<{ rotate: [degrees: number]; error: [] }>()

const containerRef = ref<HTMLDivElement>()
const currentPage = ref(1)
const totalPages = ref(0)
const loading = ref(true)
const error = ref(false)
const scale = ref(1)
// User-applied rotation (0/90/180/270), document-wide within this viewer: it persists across page
// navigation and resets to the persisted `initialRotation` only when `src` changes.
const rotation = ref(props.initialRotation)

let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null
let renderTask: { cancel: () => void; promise: Promise<unknown> } | null = null
// ONE monotonic generation for the whole viewer. Every async operation — a load AND every
// render — claims it at entry and re-validates it after each await; a new load (src change),
// a new render (page nav / rotation), or unmount bumps it. A superseded operation therefore
// bails without touching state or the canvas, even when its await REJECTS because we
// destroyed its document at supersession — the reject lands in a guarded catch that stays
// silent. RenderingCancelledException is always silent; only the CURRENT generation's own
// failure may set the error state or emit `error` (which would otherwise replace a valid
// newer preview with the failure UI).
let generation = 0

interface PdfRenderError {
  name?: string
}

function normalizedRotation(pageRotate: number): number {
  // pdf.js: an explicit `rotation` in getViewport REPLACES page.rotate (it does
  // not add), so we must fold the page's intrinsic rotation in ourselves.
  return (((pageRotate + rotation.value) % 360) + 360) % 360
}

async function loadPdf() {
  const gen = ++generation
  loading.value = true
  error.value = false

  try {
    if (pdfDoc) {
      pdfDoc.destroy()
      pdfDoc = null
    }

    const loadingTask = pdfjsLib.getDocument(props.src)
    const doc = await loadingTask.promise
    // A newer load started (src changed) or the viewer unmounted while getDocument was in
    // flight: abandon this stale one — destroy its document and touch no state.
    if (gen !== generation) {
      doc.destroy()
      return
    }
    pdfDoc = doc
    totalPages.value = pdfDoc.numPages
    currentPage.value = 1
    rotation.value = props.initialRotation
    loading.value = false
    await nextTick()
    if (gen !== generation) return
    await renderPage(1)
  } catch (e) {
    // A stale load's rejection must not report an error against the current src.
    if (gen !== generation) return
    console.error('PDF load error:', e)
    error.value = true
    loading.value = false
    emit('error')
  }
}

async function renderPage(pageNum: number) {
  if (!pdfDoc || !containerRef.value) return

  if (renderTask) {
    renderTask.cancel()
    renderTask = null
  }

  // Claim the shared generation so this render supersedes any prior render AND is itself
  // superseded by a later render, a new load, or unmount.
  const gen = ++generation

  try {
    const page = await pdfDoc.getPage(pageNum)
    // A newer render/load/unmount claimed the generation while getPage was in flight (page
    // nav, rotation, a fresh src, or teardown — the last may have destroyed this document,
    // rejecting the await): abandon this stale one before it touches the canvas.
    if (gen !== generation) return
    const container = containerRef.value
    if (!container) return

    const rotate = normalizedRotation(page.rotate)
    const containerWidth = container.clientWidth || 600
    // Fit-to-width against the ROTATED base viewport: at 90/270 the base width is
    // the page's intrinsic height, so the scale must be computed from the rotated
    // viewport's width, not the unrotated one.
    const baseViewport = page.getViewport({ scale: 1, rotation: rotate })
    const fitScale = containerWidth / baseViewport.width
    const scaledViewport = page.getViewport({ scale: fitScale * scale.value, rotation: rotate })

    let canvas = container.querySelector<HTMLCanvasElement>('canvas')
    if (!canvas) {
      canvas = document.createElement('canvas')
      container.innerHTML = ''
      container.appendChild(canvas)
    }

    canvas.width = scaledViewport.width
    canvas.height = scaledViewport.height
    canvas.style.width = '100%'
    canvas.style.height = 'auto'

    const ctx = canvas.getContext('2d')!
    renderTask = page.render({ canvasContext: ctx, viewport: scaledViewport, canvas })
    await renderTask.promise
    if (gen !== generation) return
    renderTask = null
  } catch (renderError: unknown) {
    // Ignore a superseded render (a newer render/load/unmount claimed a later generation) and
    // the benign cancellation of one — neither should surface an error against the current view.
    if (
      gen === generation &&
      (renderError as PdfRenderError)?.name !== 'RenderingCancelledException'
    ) {
      console.error('PDF render error:', renderError)
      error.value = true
      emit('error')
    }
  }
}

function prevPage() {
  if (currentPage.value > 1) {
    currentPage.value--
    renderPage(currentPage.value)
  }
}

function nextPage() {
  if (currentPage.value < totalPages.value) {
    currentPage.value++
    renderPage(currentPage.value)
  }
}

function rotateLeft() {
  rotation.value = (rotation.value + 270) % 360
  renderPage(currentPage.value)
  if (props.persistable) emit('rotate', rotation.value)
}

function rotateRight() {
  rotation.value = (rotation.value + 90) % 360
  renderPage(currentPage.value)
  if (props.persistable) emit('rotate', rotation.value)
}

watch(() => props.src, () => {
  loadPdf()
})

onMounted(() => {
  loadPdf()
})

onUnmounted(() => {
  // Invalidate any in-flight load OR render so a late resolve/reject cannot land on a dead
  // instance (write the canvas, flip error state, or emit).
  generation++
  if (renderTask) renderTask.cancel()
  if (pdfDoc) pdfDoc.destroy()
})
</script>

<template>
  <div class="pdf-viewer">
    <div v-if="loading" class="pdf-loading">
      <i class="pi pi-spin pi-spinner" />
    </div>
    <div v-else-if="error" class="pdf-error">
      <i class="pi pi-exclamation-triangle" />
      <span>{{ t('ui.could_not_load_pdf') }}</span>
      <!-- `src` is the original attachment URL — offer it ONLY as a Download, never an
           unlabelled "open" (which would download all the same, #144). -->
      <a v-if="downloadable" :href="src" download rel="noopener" class="pdf-fallback-link" :aria-label="t('download')">{{ t('download') }}</a>
    </div>
    <template v-else>
      <div ref="containerRef" class="pdf-canvas-container" />
      <div class="pdf-nav">
        <template v-if="totalPages > 1">
          <Button icon="pi pi-chevron-left" text size="small" :disabled="currentPage <= 1" @click="prevPage" :aria-label="t('ui.previous_page')" />
          <span class="pdf-page-info">{{ currentPage }} / {{ totalPages }}</span>
          <Button icon="pi pi-chevron-right" text size="small" :disabled="currentPage >= totalPages" @click="nextPage" :aria-label="t('ui.next_page')" />
        </template>
        <!-- Rotation persists, so only WRITE users see the controls (READ-only/share must not). -->
        <template v-if="persistable">
          <Button icon="pi pi-replay" text size="small" class="pdf-rotate-btn" @click="rotateLeft" :aria-label="t('ui.rotate_left')" />
          <Button icon="pi pi-refresh" text size="small" class="pdf-rotate-btn" @click="rotateRight" :aria-label="t('ui.rotate_right')" />
        </template>
        <a v-if="downloadable" :href="src" download rel="noopener" class="pdf-open-btn" :title="t('download')" :aria-label="t('download')">
          <i class="pi pi-download" />
          <span v-if="totalPages <= 1">{{ t('download') }}</span>
        </a>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pdf-viewer {
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 6px);
  overflow: hidden;
  background: var(--p-content-hover-background);
}

.pdf-canvas-container {
  display: flex;
  justify-content: center;
  background: var(--teedy-pdf-chrome);
  min-height: 200px;
}
.pdf-canvas-container canvas {
  display: block;
}

.pdf-loading,
.pdf-error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 2rem;
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
}
.pdf-loading i { font-size: 1.5rem; }
.pdf-error i { color: var(--teedy-danger); }

.pdf-fallback-link {
  color: var(--p-primary-color);
  text-decoration: none;
  font-weight: 500;
}
.pdf-fallback-link:hover { text-decoration: underline; }

.pdf-nav {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 0.375rem 0.75rem;
  background: var(--p-content-background);
  border-top: 1px solid var(--p-content-border-color);
}

.pdf-page-info {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  min-width: 3rem;
  text-align: center;
}

.pdf-open-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  color: var(--p-text-muted-color);
  text-decoration: none;
  font-size: 0.75rem;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  margin-left: auto;
  transition: color 0.12s, background 0.12s;
}
.pdf-open-btn:hover {
  color: var(--p-primary-color);
  background: var(--p-content-hover-background);
}
</style>
