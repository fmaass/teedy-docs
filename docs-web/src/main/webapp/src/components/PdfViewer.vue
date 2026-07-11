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

const props = defineProps<{
  src: string
  maxHeight?: number
}>()

const containerRef = ref<HTMLDivElement>()
const currentPage = ref(1)
const totalPages = ref(0)
const loading = ref(true)
const error = ref(false)
const scale = ref(1)
// User-applied rotation (0/90/180/270), document-wide within this viewer: it
// persists across page navigation and resets only when `src` changes.
const rotation = ref(0)

let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null
let renderTask: { cancel: () => void; promise: Promise<unknown> } | null = null
// Monotonic render generation. Every renderPage bumps this and captures the
// value; a stale getPage/render resolution (e.g. a rotation-triggered re-render
// racing an in-flight page nav) checks its captured id and bails without
// clobbering the newer render's canvas.
let renderGeneration = 0

interface PdfRenderError {
  name?: string
}

function normalizedRotation(pageRotate: number): number {
  // pdf.js: an explicit `rotation` in getViewport REPLACES page.rotate (it does
  // not add), so we must fold the page's intrinsic rotation in ourselves.
  return (((pageRotate + rotation.value) % 360) + 360) % 360
}

async function loadPdf() {
  loading.value = true
  error.value = false

  try {
    if (pdfDoc) {
      pdfDoc.destroy()
      pdfDoc = null
    }

    const loadingTask = pdfjsLib.getDocument(props.src)
    pdfDoc = await loadingTask.promise
    totalPages.value = pdfDoc.numPages
    currentPage.value = 1
    rotation.value = 0
    loading.value = false
    await nextTick()
    await renderPage(1)
  } catch (e) {
    console.error('PDF load error:', e)
    error.value = true
    loading.value = false
  }
}

async function renderPage(pageNum: number) {
  if (!pdfDoc || !containerRef.value) return

  if (renderTask) {
    renderTask.cancel()
    renderTask = null
  }

  const generation = ++renderGeneration

  try {
    const page = await pdfDoc.getPage(pageNum)
    // A newer renderPage started while getPage was in flight (page nav or a
    // rotation re-render): abandon this stale one before it touches the canvas.
    if (generation !== renderGeneration) return
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
    if (generation !== renderGeneration) return
    renderTask = null
  } catch (renderError: unknown) {
    if ((renderError as PdfRenderError)?.name !== 'RenderingCancelledException') {
      console.error('PDF render error:', renderError)
      error.value = true
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
}

function rotateRight() {
  rotation.value = (rotation.value + 90) % 360
  renderPage(currentPage.value)
}

watch(() => props.src, () => {
  loadPdf()
})

onMounted(() => {
  loadPdf()
})

onUnmounted(() => {
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
      <a :href="src" target="_blank" rel="noopener" class="pdf-fallback-link">{{ t('ui.open_new_tab') }}</a>
    </div>
    <template v-else>
      <div ref="containerRef" class="pdf-canvas-container" />
      <div class="pdf-nav">
        <template v-if="totalPages > 1">
          <Button icon="pi pi-chevron-left" text size="small" :disabled="currentPage <= 1" @click="prevPage" :aria-label="t('ui.previous_page')" />
          <span class="pdf-page-info">{{ currentPage }} / {{ totalPages }}</span>
          <Button icon="pi pi-chevron-right" text size="small" :disabled="currentPage >= totalPages" @click="nextPage" :aria-label="t('ui.next_page')" />
        </template>
        <Button icon="pi pi-replay" text size="small" class="pdf-rotate-btn" @click="rotateLeft" :aria-label="t('ui.rotate_left')" />
        <Button icon="pi pi-refresh" text size="small" class="pdf-rotate-btn" @click="rotateRight" :aria-label="t('ui.rotate_right')" />
        <a :href="src" target="_blank" rel="noopener" class="pdf-open-btn" :title="t('ui.open_new_tab')">
          <i class="pi pi-external-link" />
          <span v-if="totalPages <= 1">{{ t('ui.open_new_tab') }}</span>
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
