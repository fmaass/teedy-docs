<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import type { PDFDocumentProxy } from 'pdfjs-dist'
import Button from 'primevue/button'

// One reorderable page card in the PDF organizer: a lazily-rendered pdf.js thumbnail of a
// single source page plus its per-page controls (move earlier/later, rotate, remove). The
// parent owns the page ORDER and the manifest; this component only renders one page and
// emits intent — it never mutates the order itself. Keyed by a stable id in the parent, so
// a reorder moves the DOM node (and keeps this already-rendered canvas) instead of
// re-rendering it.

const props = defineProps<{
  // The shared, already-loaded document. Every card renders its own page from it.
  pdfDoc: PDFDocumentProxy
  // 0-based index of the page to render from the CURRENT pdf (immutable per card).
  source: number
  // ABSOLUTE clockwise rotation to preview with (0/90/180/270). This is the single source of
  // truth for orientation: it already folds in the page's intrinsic rotation (the parent seeds
  // it from pdf.js `page.rotate`), so the render must NOT add `page.rotate` again — the previewed
  // angle equals the value the parent posts to the backend, which sets it absolutely.
  rotate: number
  // 1-based position in the CURRENT order (drives the visible number + accessible names).
  position: number
  canMoveBackward: boolean
  canMoveForward: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  'move-backward': []
  'move-forward': []
  'rotate-left': []
  'rotate-right': []
  remove: []
}>()

const { t } = useI18n()

// Fixed thumbnail width (CSS scales the canvas to the grid cell); the intrinsic canvas is
// rendered at this device-independent width so text stays legible without rendering the
// full page raster.
const THUMB_WIDTH = 220

const rootRef = ref<HTMLElement>()
const canvasRef = ref<HTMLCanvasElement>()
const rendering = ref(true)
const failed = ref(false)

// Monotonic render generation: a rotate re-render that races an in-flight render must not
// let the stale one paint over the newer canvas (same guard pattern as PdfViewer).
let generation = 0
let observer: IntersectionObserver | null = null

async function render() {
  const canvas = canvasRef.value
  if (!canvas) return
  const myGen = ++generation
  rendering.value = true
  failed.value = false
  try {
    const page = await props.pdfDoc.getPage(props.source + 1)
    if (myGen !== generation) return
    // `props.rotate` is the ABSOLUTE orientation (intrinsic already folded in by the parent).
    // pdf.js REPLACES the page's intrinsic rotation when an explicit rotation is given, so passing
    // the absolute value renders exactly what the backend's setRotation(absolute) will produce.
    const rotation = ((props.rotate % 360) + 360) % 360
    const base = page.getViewport({ scale: 1, rotation })
    const scale = THUMB_WIDTH / base.width
    const viewport = page.getViewport({ scale, rotation })
    canvas.width = viewport.width
    canvas.height = viewport.height
    const ctx = canvas.getContext('2d')!
    const task = page.render({ canvasContext: ctx, viewport, canvas })
    await task.promise
    if (myGen !== generation) return
    rendering.value = false
  } catch {
    if (myGen !== generation) return
    failed.value = true
    rendering.value = false
  }
}

onMounted(() => {
  // Lazy-render on scroll so a large PDF only rasterizes the thumbnails in view. When
  // IntersectionObserver is unavailable (jsdom under test, very old browsers) fall back to
  // rendering eagerly so the card is never left blank.
  if (typeof IntersectionObserver === 'undefined') {
    render()
    return
  }
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        observer?.disconnect()
        observer = null
        render()
      }
    }
  })
  if (rootRef.value) observer.observe(rootRef.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  // Abandon any in-flight render so a late resolution can't touch a torn-down canvas.
  generation++
})

// Re-render on rotation change (only meaningful once the card has rendered at least once;
// if it hasn't been scrolled into view yet the lazy render will pick up the new rotation).
watch(
  () => props.rotate,
  () => {
    if (!observer) render()
  },
)
</script>

<template>
  <div
    ref="rootRef"
    class="pdf-page-card"
    :class="{ 'is-disabled': disabled }"
    role="group"
    :aria-label="t('ui.pdf_organizer.page_label', { n: position })"
  >
    <div class="pdf-page-thumb">
      <div v-if="rendering" class="pdf-page-state" role="status">
        <i class="pi pi-spin pi-spinner" aria-hidden="true" />
      </div>
      <div v-else-if="failed" class="pdf-page-state pdf-page-state-error">
        <i class="pi pi-exclamation-triangle" aria-hidden="true" />
      </div>
      <canvas ref="canvasRef" class="pdf-page-canvas" :class="{ 'is-hidden': rendering || failed }" />
      <span class="pdf-page-number" aria-hidden="true">{{ position }}</span>
    </div>

    <div class="pdf-page-controls">
      <Button
        icon="pi pi-arrow-left"
        text
        rounded
        size="small"
        severity="secondary"
        :disabled="disabled || !canMoveBackward"
        data-dir="backward"
        @click="emit('move-backward')"
        v-tooltip.bottom="t('ui.pdf_organizer.move_backward', { n: position })"
        :aria-label="t('ui.pdf_organizer.move_backward', { n: position })"
      />
      <Button
        icon="pi pi-replay"
        text
        rounded
        size="small"
        severity="secondary"
        :disabled="disabled"
        @click="emit('rotate-left')"
        v-tooltip.bottom="t('ui.pdf_organizer.rotate_left', { n: position })"
        :aria-label="t('ui.pdf_organizer.rotate_left', { n: position })"
      />
      <Button
        icon="pi pi-refresh"
        text
        rounded
        size="small"
        severity="secondary"
        :disabled="disabled"
        @click="emit('rotate-right')"
        v-tooltip.bottom="t('ui.pdf_organizer.rotate_right', { n: position })"
        :aria-label="t('ui.pdf_organizer.rotate_right', { n: position })"
      />
      <Button
        icon="pi pi-trash"
        text
        rounded
        size="small"
        severity="danger"
        :disabled="disabled"
        @click="emit('remove')"
        v-tooltip.bottom="t('ui.pdf_organizer.delete_page', { n: position })"
        :aria-label="t('ui.pdf_organizer.delete_page', { n: position })"
      />
      <Button
        icon="pi pi-arrow-right"
        text
        rounded
        size="small"
        severity="secondary"
        :disabled="disabled || !canMoveForward"
        data-dir="forward"
        @click="emit('move-forward')"
        v-tooltip.bottom="t('ui.pdf_organizer.move_forward', { n: position })"
        :aria-label="t('ui.pdf_organizer.move_forward', { n: position })"
      />
    </div>
  </div>
</template>

<style scoped>
.pdf-page-card {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  padding: 0.5rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 6px);
  background: var(--p-content-background);
}
.pdf-page-card.is-disabled {
  opacity: 0.6;
}

.pdf-page-thumb {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  background: var(--teedy-pdf-chrome);
  border-radius: 4px;
  overflow: hidden;
}
.pdf-page-canvas {
  display: block;
  max-width: 100%;
  height: auto;
}
.pdf-page-canvas.is-hidden {
  visibility: hidden;
}
.pdf-page-state {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--p-text-muted-color);
  font-size: 1.25rem;
}
.pdf-page-state-error i {
  color: var(--teedy-danger);
}
.pdf-page-number {
  position: absolute;
  bottom: 0.25rem;
  right: 0.25rem;
  min-width: 1.25rem;
  padding: 0 0.35rem;
  border-radius: 999px;
  background: var(--p-primary-color);
  color: var(--p-primary-contrast-color);
  font-size: 0.75rem;
  line-height: 1.25rem;
  text-align: center;
  font-weight: 600;
}

.pdf-page-controls {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.125rem;
}
</style>
