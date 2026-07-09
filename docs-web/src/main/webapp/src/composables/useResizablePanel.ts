import { ref, getCurrentInstance, onBeforeUnmount, type Ref } from 'vue'

export interface ResizablePanelOptions {
  /** Default width applied when nothing valid is persisted, and on reset. */
  defaultWidth?: number
  minWidth?: number
  /** Upper bound in px. The effective max is `min(maxWidth, maxViewportFraction * innerWidth)`. */
  maxWidth?: number
  /** Fraction of the viewport width the panel may never exceed (e.g. 0.4 = 40vw). */
  maxViewportFraction?: number
  /** localStorage key used to persist the chosen width. */
  storageKey?: string
  /** Injectable viewport width source (defaults to window.innerWidth); keeps clampWidth testable. */
  viewportWidth?: () => number
}

const DEFAULTS = {
  defaultWidth: 250,
  minWidth: 200,
  maxWidth: 480,
  maxViewportFraction: 0.4,
  storageKey: 'teedy_tag_panel_width',
}

/**
 * Clamp a requested panel width into [minWidth, min(maxWidth, fraction*viewport)].
 * Pure and viewport-injected so it is unit-testable without a DOM. A non-finite
 * request (NaN from a bad localStorage value) falls back to the default width.
 */
export function clampWidth(
  requested: number,
  opts: Required<Pick<ResizablePanelOptions, 'defaultWidth' | 'minWidth' | 'maxWidth' | 'maxViewportFraction'>>,
  viewportWidth: number,
): number {
  const value = Number.isFinite(requested) ? requested : opts.defaultWidth
  const viewportCap = Math.floor(viewportWidth * opts.maxViewportFraction)
  // Guard against a 0/absent viewport (SSR / test): fall back to the fixed max.
  const upper = viewportCap > opts.minWidth ? Math.min(opts.maxWidth, viewportCap) : opts.maxWidth
  return Math.round(Math.min(Math.max(value, opts.minWidth), upper))
}

export function useResizablePanel(options: ResizablePanelOptions = {}): {
  width: Ref<number>
  startDrag: (e: PointerEvent) => void
  onKeydown: (e: KeyboardEvent) => void
  reset: () => void
} {
  const cfg = { ...DEFAULTS, ...Object.fromEntries(Object.entries(options).filter(([, v]) => v !== undefined)) }
  const viewport = options.viewportWidth ?? (() => (typeof window !== 'undefined' ? window.innerWidth : 0))

  const clampCfg = {
    defaultWidth: cfg.defaultWidth,
    minWidth: cfg.minWidth,
    maxWidth: cfg.maxWidth,
    maxViewportFraction: cfg.maxViewportFraction,
  }

  function apply(requested: number): number {
    return clampWidth(requested, clampCfg, viewport())
  }

  function load(): number {
    let stored = NaN
    try {
      const raw = localStorage.getItem(cfg.storageKey)
      if (raw != null) stored = Number.parseInt(raw, 10)
    } catch {
      // localStorage unavailable (private mode / SSR) — use default.
    }
    return apply(Number.isFinite(stored) ? stored : cfg.defaultWidth)
  }

  const width = ref(load())

  function persist() {
    try {
      localStorage.setItem(cfg.storageKey, String(width.value))
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

  function endDrag() {
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', endDrag)
    document.body.style.removeProperty('cursor')
    document.body.style.removeProperty('user-select')
    persist()
  }

  function startDrag(e: PointerEvent) {
    e.preventDefault()
    dragStartX = e.clientX
    dragStartWidth = width.value
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', endDrag)
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
      setWidth(cfg.minWidth)
      persist()
      e.preventDefault()
    } else if (e.key === 'End') {
      setWidth(cfg.maxWidth)
      persist()
      e.preventDefault()
    }
  }

  function reset() {
    setWidth(cfg.defaultWidth)
    persist()
  }

  // Only register the lifecycle hook inside a component setup (skips it in unit tests).
  if (getCurrentInstance()) onBeforeUnmount(endDrag)

  return { width, startDrag, onKeydown, reset }
}
