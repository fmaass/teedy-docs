import { ref, getCurrentInstance, onBeforeUnmount, onMounted, type Ref } from 'vue'

// The panel's sizing envelope. Fixed constants: the resizable panel is the tag
// sidebar and nothing else, so these never varied per call. Only the storage key
// (and the test-only viewport source) are parameters.
const DEFAULT_WIDTH = 250
const MIN_WIDTH = 200
const MAX_WIDTH = 480
/** Fraction of the viewport width the panel may never exceed (0.4 = 40vw). */
const MAX_VIEWPORT_FRACTION = 0.4
/** Default localStorage key used to persist the chosen width. */
const DEFAULT_STORAGE_KEY = 'teedy_tag_panel_width'

// The clamp envelope as a single object, reused by clampWidth and the composable.
const CLAMP_CFG: ClampCfg = {
  defaultWidth: DEFAULT_WIDTH,
  minWidth: MIN_WIDTH,
  maxWidth: MAX_WIDTH,
  maxViewportFraction: MAX_VIEWPORT_FRACTION,
}

export interface ClampCfg {
  defaultWidth: number
  minWidth: number
  maxWidth: number
  maxViewportFraction: number
}

/**
 * Clamp a requested panel width into [minWidth, min(maxWidth, fraction*viewport)].
 * Pure and viewport-injected so it is unit-testable without a DOM. A non-finite
 * request (NaN from a bad localStorage value) falls back to the default width.
 */
export function clampWidth(requested: number, opts: ClampCfg, viewportWidth: number): number {
  const value = Number.isFinite(requested) ? requested : opts.defaultWidth
  const viewportCap = Math.floor(viewportWidth * opts.maxViewportFraction)
  // Guard against a 0/absent viewport (SSR / test): fall back to the fixed max.
  const upper = viewportCap > opts.minWidth ? Math.min(opts.maxWidth, viewportCap) : opts.maxWidth
  return Math.round(Math.min(Math.max(value, opts.minWidth), upper))
}

export interface ResizablePanelOptions {
  /** localStorage key used to persist the chosen width. Defaults to the tag-panel key. */
  storageKey?: string
  /** Injectable viewport width source (defaults to window.innerWidth); keeps clampWidth testable. */
  viewportWidth?: () => number
}

export function useResizablePanel(options: ResizablePanelOptions = {}): {
  width: Ref<number>
  startDrag: (e: PointerEvent) => void
  onKeydown: (e: KeyboardEvent) => void
  reset: () => void
} {
  const storageKey = options.storageKey ?? DEFAULT_STORAGE_KEY
  const viewport = options.viewportWidth ?? (() => (typeof window !== 'undefined' ? window.innerWidth : 0))

  function apply(requested: number): number {
    return clampWidth(requested, CLAMP_CFG, viewport())
  }

  function load(): number {
    let stored = NaN
    try {
      const raw = localStorage.getItem(storageKey)
      if (raw != null) stored = Number.parseInt(raw, 10)
    } catch {
      // localStorage unavailable (private mode / SSR) — use default.
    }
    return apply(Number.isFinite(stored) ? stored : DEFAULT_WIDTH)
  }

  const width = ref(load())

  function persist() {
    try {
      localStorage.setItem(storageKey, String(width.value))
    } catch {
      // Ignore persistence failures — width still applies for the session.
    }
  }

  function setWidth(next: number) {
    width.value = apply(next)
  }

  // --- Pointer drag ---

  let dragStartX = 0
  let dragStartWidth = 0

  function onPointerMove(e: PointerEvent) {
    setWidth(dragStartWidth + (e.clientX - dragStartX))
  }

  // Idempotent: safe to call for pointerup, pointercancel, and unmount alike.
  function endDrag() {
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', endDrag)
    window.removeEventListener('pointercancel', endDrag)
    document.body.style.removeProperty('cursor')
    document.body.style.removeProperty('user-select')
    persist()
  }

  function startDrag(e: PointerEvent) {
    // Left/primary pointer only — ignore right/middle click and secondary pointers
    // (a two-finger touch or right-drag must not start a resize).
    if (e.button !== 0 || e.isPrimary === false) return
    e.preventDefault()
    dragStartX = e.clientX
    dragStartWidth = width.value
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', endDrag)
    window.addEventListener('pointercancel', endDrag)
  }

  // --- Keyboard (separator a11y) ---

  const KEY_STEP = 16

  function onKeydown(e: KeyboardEvent) {
    if (e.key === 'ArrowLeft') {
      setWidth(width.value - KEY_STEP)
      persist()
      e.preventDefault()
    } else if (e.key === 'ArrowRight') {
      setWidth(width.value + KEY_STEP)
      persist()
      e.preventDefault()
    } else if (e.key === 'Home') {
      setWidth(MIN_WIDTH)
      persist()
      e.preventDefault()
    } else if (e.key === 'End') {
      setWidth(MAX_WIDTH)
      persist()
      e.preventDefault()
    }
  }

  function reset() {
    setWidth(DEFAULT_WIDTH)
    persist()
  }

  // Re-clamp on viewport resize so a width that exceeds the new viewport cap is
  // pulled back in. No persist: the user's preferred width returns if the
  // viewport grows again (load() re-applies the stored, larger value).
  function onResize() {
    width.value = apply(width.value)
  }

  // Only register lifecycle hooks inside a component setup (skips them in unit tests).
  if (getCurrentInstance()) {
    onBeforeUnmount(endDrag)
    onMounted(() => window.addEventListener('resize', onResize))
    onBeforeUnmount(() => window.removeEventListener('resize', onResize))
  }

  return { width, startDrag, onKeydown, reset }
}
