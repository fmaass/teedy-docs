<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import * as pdfjsLib from 'pdfjs-dist'
import Button from 'primevue/button'

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

let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null
let renderTask: { cancel: () => void; promise: Promise<unknown> } | null = null

interface PdfRenderError {
  name?: string
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

  try {
    const page = await pdfDoc.getPage(pageNum)
    const container = containerRef.value

    const containerWidth = container.clientWidth || 600
    const viewport = page.getViewport({ scale: 1 })
    const fitScale = containerWidth / viewport.width
    const scaledViewport = page.getViewport({ scale: fitScale * scale.value })

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
      <span>Could not load PDF</span>
      <a :href="src" target="_blank" class="pdf-fallback-link">Open in new tab</a>
    </div>
    <template v-else>
      <div ref="containerRef" class="pdf-canvas-container" />
      <div v-if="totalPages > 1" class="pdf-nav">
        <Button icon="pi pi-chevron-left" text size="small" :disabled="currentPage <= 1" @click="prevPage" aria-label="Previous page" />
        <span class="pdf-page-info">{{ currentPage }} / {{ totalPages }}</span>
        <Button icon="pi pi-chevron-right" text size="small" :disabled="currentPage >= totalPages" @click="nextPage" aria-label="Next page" />
        <a :href="src" target="_blank" class="pdf-open-btn" title="Open in new tab">
          <i class="pi pi-external-link" />
        </a>
      </div>
      <div v-else class="pdf-nav">
        <a :href="src" target="_blank" class="pdf-open-btn" title="Open in new tab">
          <i class="pi pi-external-link" />
          <span>Open in new tab</span>
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
.pdf-error i { color: var(--teedy-disabled-color); }

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
