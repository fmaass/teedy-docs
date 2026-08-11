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
    // CONTINUOUS mode (#237): render the whole document as one vertically scrollable
    // column of per-page canvases instead of a single canvas the page controls swap in
    // place. DEFAULT FALSE — it is opt-in per call site (today: the preview dialog while
    // it is maximized, where the dialog IS the screen and one page at a time is the wrong
    // shape). Every other consumer keeps the single-page viewer unchanged.
    continuous?: boolean
  }>(),
  { initialRotation: 0, persistable: false, downloadable: true, continuous: false },
)

// `error` lets a parent degrade to its own fallback (e.g. the preview dialog's
// "preview unavailable + Download" state) instead of showing this viewer's error UI.
const emit = defineEmits<{ rotate: [degrees: number]; error: [] }>()

const containerRef = ref<HTMLDivElement>()
const pagesRef = ref<HTMLDivElement>()
const currentPage = ref(1)
const totalPages = ref(0)
const loading = ref(true)
const error = ref(false)
const scale = ref(1)
// User-applied rotation (0/90/180/270), document-wide within this viewer: it persists across page
// navigation and resets to the persisted `initialRotation` only when `src` changes.
const rotation = ref(props.initialRotation)

// Continuous mode only. Each page box reserves its page's shape BEFORE anything is
// rendered, so the column has a stable scroll height and the observer sees real
// geometry — without it every page box would be 0px tall, they would all intersect at
// once, and the "lazy" rendering would render the entire document immediately.
// Unmeasured pages borrow the first page's shape (documents are near-always uniform);
// each page's own ratio replaces it the moment that page is measured.
const DEFAULT_PAGE_ASPECT = '1 / 1.414'
const defaultAspect = ref(DEFAULT_PAGE_ASPECT)
const pageAspects = ref(new Map<number, string>())

// How far outside the scroll viewport a page starts rendering, and how many page canvases
// may be alive at once. A page canvas is width-of-the-viewer sized (megabytes at a
// fullscreen width), so an unbounded column of them is a memory wall on a long document.
//
// The cap counts a page from the moment its render STARTS, not from the moment it
// finishes: the canvas is allocated up front (see renderPageInto), so counting only
// completed pages would let a scrollbar dragged through a long document start an unbounded
// number of renders whose canvases are all already allocated. A page over the cap is
// deferred instead, and the most-visible deferred page goes first when a slot frees.
const RENDER_MARGIN_PX = 300
const MAX_LIVE_PAGES = 6
// Scrolling moves a page at a time, so the page under the viewport centre is almost always
// within a few of the current one; a full scan is the fallback for a jump or a resize.
const SCAN_RADIUS = 4

let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null
// pdf.js 6 removed PDFDocumentProxy.destroy(); teardown goes through the loading task, whose
// destroy() tears down the document and its worker. Tracked alongside pdfDoc for that purpose.
let pdfLoadingTask: pdfjsLib.PDFDocumentLoadingTask | null = null

// DOCUMENT-lifecycle generation: bumped by a load (src change) and by unmount, NOT by an
// individual render. Every async step re-validates it after an await, so an operation
// belonging to a destroyed/replaced document bails without touching state or a canvas —
// including when its await REJECTS because we destroyed its document underneath it.
let docGeneration = 0

// PER-PAGE render bookkeeping. Continuous mode renders several pages concurrently, so a
// single shared render task cannot work: each new render would cancel the one before it and
// pages would race each other to a blank canvas. Every page therefore owns its render task
// plus a token that supersedes an earlier render OF THE SAME PAGE (a rotation, a re-render
// after eviction). A stale render is silent; only the current token of the current document
// may surface an error.
const renderTasks = new Map<number, { cancel: () => void; promise: Promise<unknown> }>()
const renderTokens = new Map<number, number>()
let renderTokenSeq = 0
// Pages whose render is in flight, and pages that finished and still hold a live canvas.
const inFlight = new Set<number>()
const renderedPages = new Set<number>()
// Pages occupying one of the MAX_LIVE_PAGES slots: joined at render START and left only
// when the page's canvas is released (abandoned mid-render, or evicted once complete).
// `inFlight` and `renderedPages` are both subsets of it.
const claimed = new Set<number>()
// Pages that are in render range but found no free slot — kept so the most visible of them
// can start as soon as one frees.
const pendingPages = new Set<number>()
// The page boxes of the continuous column, by page number.
const pageEls = new Map<number, HTMLElement>()

let renderObserver: IntersectionObserver | null = null
let scrollFrame = 0

interface PdfRenderError {
  name?: string
}

function cancelRender(pageNum: number) {
  const task = renderTasks.get(pageNum)
  if (task) {
    task.cancel()
    renderTasks.delete(pageNum)
  }
}

function cancelAllRenders() {
  for (const pageNum of [...renderTasks.keys()]) cancelRender(pageNum)
  renderTokens.clear()
  inFlight.clear()
}

/** Zero and detach a page's canvas. Zeroing is what releases the backing store. */
function removePageCanvas(pageNum: number) {
  const canvas = pageEls.get(pageNum)?.querySelector('canvas')
  if (canvas) {
    canvas.width = 0
    canvas.height = 0
    canvas.remove()
  }
}

function normalizedRotation(pageRotate: number): number {
  // pdf.js: an explicit `rotation` in getViewport REPLACES page.rotate (it does
  // not add), so we must fold the page's intrinsic rotation in ourselves.
  return (((pageRotate + rotation.value) % 360) + 360) % 360
}

async function loadPdf() {
  const gen = ++docGeneration
  cancelAllRenders()
  teardownContinuous()
  loading.value = true
  error.value = false
  pageAspects.value = new Map()
  defaultAspect.value = DEFAULT_PAGE_ASPECT

  try {
    if (pdfLoadingTask) {
      pdfLoadingTask.destroy()
      pdfLoadingTask = null
      pdfDoc = null
    }

    const loadingTask = pdfjsLib.getDocument({ url: props.src })
    const doc = await loadingTask.promise
    // A newer load started (src changed) or the viewer unmounted while getDocument was in
    // flight: abandon this stale one — destroy its loading task (tearing down the document)
    // and touch no state.
    if (gen !== docGeneration) {
      loadingTask.destroy()
      return
    }
    pdfDoc = doc
    pdfLoadingTask = loadingTask
    totalPages.value = pdfDoc.numPages
    currentPage.value = 1
    rotation.value = props.initialRotation
    loading.value = false
    await nextTick()
    if (gen !== docGeneration) return
    if (props.continuous) await startContinuous()
    else await renderPage(1)
  } catch (e) {
    // A stale load's rejection must not report an error against the current src.
    if (gen !== docGeneration) return
    console.error('PDF load error:', e)
    error.value = true
    loading.value = false
    emit('error')
  }
}

/**
 * Render one page into one target box. `intoPageBox` distinguishes the continuous column's
 * per-page box (which already reserves the page's shape, so the canvas fills it) from the
 * windowed single-canvas container (which takes its height FROM the canvas).
 */
async function renderPageInto(pageNum: number, target: HTMLElement, intoPageBox: boolean) {
  if (!pdfDoc) return

  const gen = docGeneration
  const token = ++renderTokenSeq
  // Supersede any earlier render OF THIS PAGE — a concurrent render of ANOTHER page is
  // deliberately left running (that is the whole point of per-page tasks).
  cancelRender(pageNum)
  renderTokens.set(pageNum, token)
  inFlight.add(pageNum)

  try {
    const page = await pdfDoc.getPage(pageNum)
    // The document was replaced/destroyed, or this page was asked to re-render, while
    // getPage was in flight: abandon this one before it touches a canvas.
    if (gen !== docGeneration || renderTokens.get(pageNum) !== token) return

    const rotate = normalizedRotation(page.rotate)
    // Fit-to-width against the ROTATED base viewport: at 90/270 the base width is
    // the page's intrinsic height, so the scale must be computed from the rotated
    // viewport's width, not the unrotated one.
    const baseViewport = page.getViewport({ scale: 1, rotation: rotate })
    if (intoPageBox) {
      // Now that this page's true shape is known, its box (and, from page 1, every
      // not-yet-measured box) stops guessing.
      pageAspects.value.set(pageNum, `${baseViewport.width} / ${baseViewport.height}`)
      if (pageNum === 1) defaultAspect.value = `${baseViewport.width} / ${baseViewport.height}`
    }
    const targetWidth = target.clientWidth || 600
    const fitScale = targetWidth / baseViewport.width
    const scaledViewport = page.getViewport({ scale: fitScale * scale.value, rotation: rotate })

    let canvas = target.querySelector<HTMLCanvasElement>('canvas')
    if (!canvas) {
      canvas = document.createElement('canvas')
      target.innerHTML = ''
      target.appendChild(canvas)
    }

    canvas.width = scaledViewport.width
    canvas.height = scaledViewport.height
    // Set on the element: this canvas is created imperatively, so it carries no scoped
    // style attribute and the stylesheet's own rules would not reach it.
    canvas.style.display = 'block'
    canvas.style.width = '100%'
    canvas.style.height = intoPageBox ? '100%' : 'auto'

    const ctx = canvas.getContext('2d')!
    const task = page.render({ canvasContext: ctx, viewport: scaledViewport, canvas })
    renderTasks.set(pageNum, task)
    await task.promise
    if (gen !== docGeneration || renderTokens.get(pageNum) !== token) return
    renderTasks.delete(pageNum)
    // Keeps its slot: it now holds a completed canvas, released only when a page nearer the
    // reader needs the slot (reclaimSlotFrom) or the column is torn down.
    if (intoPageBox) renderedPages.add(pageNum)
  } catch (renderError: unknown) {
    // Ignore a superseded render (a newer document, or a newer render of this same page)
    // and the benign cancellation of one — neither should surface an error against the
    // current view.
    if (
      gen === docGeneration &&
      renderTokens.get(pageNum) === token &&
      (renderError as PdfRenderError)?.name !== 'RenderingCancelledException'
    ) {
      console.error('PDF render error:', renderError)
      error.value = true
      emit('error')
    }
  } finally {
    // Only the render that still OWNS this page's token may clean up after it: a superseded
    // render must not release the bookkeeping (or the canvas) of the one that replaced it.
    if (renderTokens.get(pageNum) === token) {
      inFlight.delete(pageNum)
      // Never completed — cancelled, superseded or failed — so its slot and the canvas it
      // had already allocated go back to the pool.
      if (intoPageBox && !renderedPages.has(pageNum)) {
        claimed.delete(pageNum)
        removePageCanvas(pageNum)
      }
    }
    if (intoPageBox) pumpPendingRenders()
  }
}

/** Windowed mode: the single canvas, re-rendered in place — unchanged behaviour. */
async function renderPage(pageNum: number) {
  const container = containerRef.value
  if (!container) return
  // ONE canvas here, so a render of ANY page is targeting the same surface and must be
  // superseded outright — per-page cancellation would let the outgoing page finish
  // painting over the incoming one. (In continuous mode every page owns its own box,
  // which is exactly why cancellation is per page there.)
  cancelAllRenders()
  await renderPageInto(pageNum, container, false)
}

// --- continuous mode ---------------------------------------------------------------

function setPageRef(pageNum: number, el: HTMLElement | null) {
  const previous = pageEls.get(pageNum)
  if (previous && previous !== el) {
    renderObserver?.unobserve(previous)
    // Its canvas went with the element, so its slot goes back too.
    renderedPages.delete(pageNum)
    claimed.delete(pageNum)
    pendingPages.delete(pageNum)
  }
  if (el) {
    pageEls.set(pageNum, el)
    renderObserver?.observe(el)
  } else {
    pageEls.delete(pageNum)
  }
}

function inRenderRange(el: HTMLElement): boolean {
  const root = pagesRef.value
  if (!root) return false
  const viewport = root.getBoundingClientRect()
  const box = el.getBoundingClientRect()
  return box.bottom > viewport.top - RENDER_MARGIN_PX && box.top < viewport.bottom + RENDER_MARGIN_PX
}

function startRender(pageNum: number, el: HTMLElement) {
  pendingPages.delete(pageNum)
  // Claim the slot BEFORE the render begins — the canvas is allocated inside it.
  claimed.add(pageNum)
  void renderPageInto(pageNum, el, true)
}

function ensureRendered(pageNum: number) {
  if (!pdfDoc || !props.continuous) return
  if (claimed.has(pageNum)) return // already rendering, or already rendered
  const el = pageEls.get(pageNum)
  if (!el) return
  if (claimed.size >= MAX_LIVE_PAGES && !reclaimSlotFrom(pageNum)) {
    pendingPages.add(pageNum)
    return
  }
  startRender(pageNum, el)
}

/** How many pixels of this page the reader can actually see (<= 0 when off screen). */
function visiblePixels(el: HTMLElement): number {
  const root = pagesRef.value
  if (!root) return 0
  const viewport = root.getBoundingClientRect()
  const box = el.getBoundingClientRect()
  return Math.min(box.bottom, viewport.bottom) - Math.max(box.top, viewport.top)
}

function isOnScreen(pageNum: number): boolean {
  const el = pageEls.get(pageNum)
  return !!el && visiblePixels(el) > 0
}

function evictForSlot(victim: number): boolean {
  dropPageCanvas(victim)
  // Still in range, so queue it to come back when a slot frees: the observer only reports
  // CHANGES and this page never left, so nothing else would ever ask for it again and
  // scrolling back to it would find it permanently blank.
  const el = pageEls.get(victim)
  if (el && inRenderRange(el)) pendingPages.add(victim)
  return true
}

/**
 * Free a slot for `pageNum` by evicting a COMPLETED page (an in-flight render is never the
 * victim — cancelling one to start another is how a drag would thrash).
 *
 * Priority is by VISIBILITY first, then distance. Distance alone starves: page indices only
 * track what is on screen while the pages are uniform, so in a mixed-size document the pages
 * just above the reader stay index-NEAR while scrolling off screen, and a pure distance rule
 * then refuses to give their slots to the further-indexed pages the reader is actually
 * looking at — leaving blank pages in the viewport for as long as they sit still.
 */
function reclaimSlotFrom(pageNum: number): boolean {
  const wantOnScreen = isOnScreen(pageNum)

  // A page being LOOKED AT outranks any completed page that is off screen, however near
  // that one is: an off-screen slot-holder is a prefetch, and a prefetch must never outlive
  // a blank page in the viewport.
  if (wantOnScreen) {
    let victim = 0
    let furthest = -1
    for (const candidate of renderedPages) {
      if (isOnScreen(candidate)) continue
      const distance = Math.abs(candidate - currentPage.value)
      if (distance > furthest) {
        furthest = distance
        victim = candidate
      }
    }
    if (victim) return evictForSlot(victim)
  }

  // Competitor on screen too (or this page is itself only a prefetch): the distance rule
  // decides, and the furthest completed page gives way only to a page nearer the reader.
  // A page that is NOT on screen may never blank one that is.
  let victim = 0
  let furthest = Math.abs(pageNum - currentPage.value)
  for (const candidate of renderedPages) {
    if (!wantOnScreen && isOnScreen(candidate)) continue
    const distance = Math.abs(candidate - currentPage.value)
    if (distance > furthest) {
      furthest = distance
      victim = candidate
    }
  }
  if (!victim) return false
  return evictForSlot(victim)
}

/** Start deferred pages, most-visible first, for as long as slots can be found. */
function pumpPendingRenders() {
  if (!props.continuous || !pagesRef.value) return
  for (;;) {
    const next = mostVisiblePending()
    if (!next) return
    const el = pageEls.get(next)
    // A deferred page that has since scrolled out of range must not spend a slot.
    if (!el || !inRenderRange(el)) {
      pendingPages.delete(next)
      continue
    }
    if (claimed.size >= MAX_LIVE_PAGES && !reclaimSlotFrom(next)) return
    startRender(next, el)
  }
}

/** The deferred page showing the most pixels; ties go to the one nearest the reader. */
function mostVisiblePending(): number {
  if (!pagesRef.value) return 0
  let best = 0
  let bestVisible = Number.NEGATIVE_INFINITY
  let bestDistance = Number.POSITIVE_INFINITY
  for (const pageNum of pendingPages) {
    const el = pageEls.get(pageNum)
    if (!el) continue
    const visible = visiblePixels(el)
    const distance = Math.abs(pageNum - currentPage.value)
    if (visible > bestVisible || (visible === bestVisible && distance < bestDistance)) {
      best = pageNum
      bestVisible = visible
      bestDistance = distance
    }
  }
  return best
}

/**
 * Give up an IN-FLIGHT render whose page has left the render range: cancel the task, free
 * the canvas it already allocated, and hand its slot back. A page that has FINISHED keeps
 * its canvas — those are governed by the distance-based eviction, so scrolling back a
 * little does not repaint everything.
 */
function abandonRender(pageNum: number) {
  pendingPages.delete(pageNum)
  if (!inFlight.has(pageNum)) return
  cancelRender(pageNum)
  // Invalidates the in-flight render's token, so its continuation bails and its `finally`
  // leaves this cleanup alone.
  renderTokens.delete(pageNum)
  inFlight.delete(pageNum)
  claimed.delete(pageNum)
  removePageCanvas(pageNum)
  pumpPendingRenders()
}

/** Render whatever is currently within render range — the observer only reports CHANGES. */
function renderPagesInRange() {
  if (!pagesRef.value) return
  for (const [pageNum, el] of pageEls) {
    if (inRenderRange(el)) ensureRendered(pageNum)
  }
}

/** Drop a rendered page back to its (same-sized) placeholder box and free its bitmap. */
function dropPageCanvas(pageNum: number) {
  cancelRender(pageNum)
  // Also invalidates an in-flight render of this page: its token no longer matches.
  renderTokens.delete(pageNum)
  inFlight.delete(pageNum)
  renderedPages.delete(pageNum)
  claimed.delete(pageNum)
  removePageCanvas(pageNum)
}

function scrollToPage(pageNum: number) {
  const root = pagesRef.value
  const el = pageEls.get(pageNum)
  if (!root || !el) return
  root.scrollTop += el.getBoundingClientRect().top - root.getBoundingClientRect().top
}

/** The page under the scroll viewport's centre line — the one the reader is looking at. */
function pageAtViewportCentre(candidates: number[]): number {
  const root = pagesRef.value
  if (!root) return 0
  const viewport = root.getBoundingClientRect()
  const centre = viewport.top + viewport.height / 2
  for (const pageNum of candidates) {
    const el = pageEls.get(pageNum)
    if (!el) continue
    const box = el.getBoundingClientRect()
    if (box.top <= centre && box.bottom >= centre) return pageNum
  }
  return 0
}

function updateCurrentPageFromScroll() {
  if (!props.continuous || !pagesRef.value) return
  const near: number[] = []
  for (
    let pageNum = Math.max(1, currentPage.value - SCAN_RADIUS);
    pageNum <= Math.min(totalPages.value, currentPage.value + SCAN_RADIUS);
    pageNum++
  ) {
    near.push(pageNum)
  }
  let found = pageAtViewportCentre(near)
  if (!found) {
    const all: number[] = []
    for (let pageNum = 1; pageNum <= totalPages.value; pageNum++) all.push(pageNum)
    found = pageAtViewportCentre(all)
  }
  // Nothing under the centre line: the column is mid-teardown (a mode switch collapses
  // every box to zero) or the dialog is animating. Keep the page the reader was on —
  // falling back to page 1 here is exactly how a maximize/restore would lose their place.
  if (!found) return
  if (found === currentPage.value) return
  currentPage.value = found
  // Distance from the reader is what decides both eviction and deferral priority, so a page
  // change can make a slot reclaimable for something that was queued when it was not.
  pumpPendingRenders()
}

function onPagesScroll() {
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    updateCurrentPageFromScroll()
  })
}

async function startContinuous() {
  await nextTick()
  const root = pagesRef.value
  if (!root) return
  teardownContinuous()
  renderObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const pageNum = Number((entry.target as HTMLElement).dataset.page)
        // Leaving the range is as significant as entering it: a page dragged past mid-render
        // would otherwise keep painting a canvas nobody will look at, holding its memory and
        // its slot for the whole render.
        if (entry.isIntersecting) ensureRendered(pageNum)
        else abandonRender(pageNum)
      }
    },
    { root, rootMargin: `${RENDER_MARGIN_PX}px 0px` },
  )
  for (const el of pageEls.values()) renderObserver.observe(el)
  root.addEventListener('scroll', onPagesScroll, { passive: true })
  // Enter the column where the reader already was, then paint what that shows. The
  // observer fires on its own too; both paths are idempotent.
  scrollToPage(currentPage.value)
  renderPagesInRange()
}

function teardownContinuous() {
  if (scrollFrame) {
    cancelAnimationFrame(scrollFrame)
    scrollFrame = 0
  }
  pagesRef.value?.removeEventListener('scroll', onPagesScroll)
  renderObserver?.disconnect()
  renderObserver = null
  pendingPages.clear()
  for (const pageNum of [...claimed]) dropPageCanvas(pageNum)
  renderedPages.clear()
  claimed.clear()
}

// --- controls ----------------------------------------------------------------------

function goToPage(pageNum: number) {
  if (pageNum < 1 || pageNum > totalPages.value) return
  currentPage.value = pageNum
  if (props.continuous) {
    // In the column, prev/next are jumps within one continuous document, not a swap of
    // what the single canvas holds.
    ensureRendered(pageNum)
    scrollToPage(pageNum)
  } else {
    void renderPage(pageNum)
  }
}

function prevPage() {
  goToPage(currentPage.value - 1)
}

function nextPage() {
  goToPage(currentPage.value + 1)
}

function applyRotation() {
  if (props.continuous) {
    // Every page's geometry changed, so every live canvas is stale — in-flight ones
    // included: drop them all and repaint what is on screen (their boxes re-shape as each
    // one is re-measured).
    for (const pageNum of [...claimed]) dropPageCanvas(pageNum)
    pendingPages.clear()
    renderPagesInRange()
  } else {
    void renderPage(currentPage.value)
  }
}

function rotateLeft() {
  rotation.value = (rotation.value + 270) % 360
  applyRotation()
  if (props.persistable) emit('rotate', rotation.value)
}

function rotateRight() {
  rotation.value = (rotation.value + 90) % 360
  applyRotation()
  if (props.persistable) emit('rotate', rotation.value)
}

watch(() => props.src, () => {
  loadPdf()
})

// Switching modes with the document already open (the preview dialog being maximized or
// restored) keeps the page the reader is on and rebuilds only the rendering surface. The
// watcher runs BEFORE the DOM updates, so the outgoing surface is still there to clean up.
watch(() => props.continuous, async (isContinuous) => {
  if (loading.value || error.value || !pdfDoc) return
  const page = currentPage.value
  if (isContinuous) {
    // The single canvas is about to be unmounted; nothing may still be painting into it.
    cancelAllRenders()
    await startContinuous()
  } else {
    teardownContinuous()
    await nextTick()
    await renderPage(page)
  }
})

onMounted(() => {
  loadPdf()
})

onUnmounted(() => {
  // Invalidate any in-flight load OR render so a late resolve/reject cannot land on a dead
  // instance (write the canvas, flip error state, or emit).
  docGeneration++
  cancelAllRenders()
  teardownContinuous()
  if (pdfLoadingTask) pdfLoadingTask.destroy()
})
</script>

<template>
  <div class="pdf-viewer" :class="{ 'pdf-viewer--continuous': continuous }">
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
      <!-- CONTINUOUS (#237): one box per page, each reserving that page's shape so the
           column scrolls at its true length before anything is painted. Canvases are
           created into these boxes as they come into range and dropped again once far
           enough behind — the box, and therefore the scroll position, never moves. -->
      <div v-if="continuous" ref="pagesRef" class="pdf-pages">
        <div
          v-for="p in totalPages"
          :key="p"
          :ref="(el) => setPageRef(p, el as HTMLElement | null)"
          class="pdf-page"
          :data-page="p"
          :style="{ aspectRatio: pageAspects.get(p) ?? defaultAspect }"
        />
      </div>
      <div v-else ref="containerRef" class="pdf-canvas-container" />
      <div class="pdf-nav">
        <template v-if="totalPages > 1">
          <Button icon="pi pi-chevron-left" text size="small" severity="secondary" :disabled="currentPage <= 1" @click="prevPage" :aria-label="t('ui.previous_page')" />
          <span class="pdf-page-info">{{ currentPage }} / {{ totalPages }}</span>
          <Button icon="pi pi-chevron-right" text size="small" severity="secondary" :disabled="currentPage >= totalPages" @click="nextPage" :aria-label="t('ui.next_page')" />
        </template>
        <!-- Rotation persists, so only WRITE users see the controls (READ-only/share must not). -->
        <template v-if="persistable">
          <Button icon="pi pi-replay" text size="small" severity="secondary" class="pdf-rotate-btn" @click="rotateLeft" :aria-label="t('ui.rotate_left')" />
          <Button icon="pi pi-refresh" text size="small" severity="secondary" class="pdf-rotate-btn" @click="rotateRight" :aria-label="t('ui.rotate_right')" />
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

/* CONTINUOUS mode (#237). The viewer becomes a column: a scrollable page list that takes
   all the height its parent gives it, with the nav bar pinned below. `min-height: 0` on
   both is what lets the list actually shrink to the parent and scroll INSIDE itself
   instead of growing the dialog. */
.pdf-viewer--continuous {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.pdf-pages {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  /* A flick past the last page scrolls the page list, never the view behind the dialog. */
  overscroll-behavior: contain;
  background: var(--teedy-pdf-chrome);
}
.pdf-page {
  width: 100%;
  /* An unpainted page reads as a blank sheet rather than a hole in the column. */
  background: var(--p-content-background);
  /* The canvas is sized from the same viewport the box's ratio comes from, so it fits to
     the pixel — this only absorbs the sub-pixel rounding of that division. */
  overflow: hidden;
}
/* Gap BETWEEN pages only: the first page starts flush at scrollTop 0. */
.pdf-page + .pdf-page {
  margin-top: 0.5rem;
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
