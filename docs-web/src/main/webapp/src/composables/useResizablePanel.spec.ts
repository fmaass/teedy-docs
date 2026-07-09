import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { clampWidth, useResizablePanel } from './useResizablePanel'

const CFG = { defaultWidth: 250, minWidth: 200, maxWidth: 480, maxViewportFraction: 0.4 }

describe('clampWidth (pure)', () => {
  // Viewport wide enough (2000px * 0.4 = 800) that maxWidth (480) is the binding cap.
  const WIDE = 2000

  it('returns a value inside the range unchanged', () => {
    expect(clampWidth(300, CFG, WIDE)).toBe(300)
  })

  it('clamps below the minimum up to minWidth', () => {
    expect(clampWidth(50, CFG, WIDE)).toBe(200)
  })

  it('clamps above the maximum down to maxWidth', () => {
    expect(clampWidth(999, CFG, WIDE)).toBe(480)
  })

  it('respects the viewport fraction when it is the tighter cap', () => {
    // 1000 * 0.4 = 400, tighter than maxWidth 480.
    expect(clampWidth(999, CFG, 1000)).toBe(400)
  })

  it('falls back to the fixed maxWidth when the viewport is absent (0)', () => {
    expect(clampWidth(999, CFG, 0)).toBe(480)
  })

  it('falls back to defaultWidth for a non-finite request', () => {
    expect(clampWidth(Number.NaN, CFG, WIDE)).toBe(250)
  })
})

describe('useResizablePanel persistence', () => {
  const KEY = 'teedy_tag_panel_width'

  beforeEach(() => localStorage.clear())
  afterEach(() => localStorage.clear())

  it('restores a persisted width (clamped) on init', () => {
    localStorage.setItem(KEY, '360')
    const { width } = useResizablePanel({ viewportWidth: () => 2000 })
    expect(width.value).toBe(360)
  })

  it('clamps an out-of-range persisted width on restore', () => {
    localStorage.setItem(KEY, '9999')
    const { width } = useResizablePanel({ viewportWidth: () => 2000 })
    expect(width.value).toBe(480)
  })

  it('uses the default width when nothing is persisted', () => {
    const { width } = useResizablePanel({ viewportWidth: () => 2000 })
    expect(width.value).toBe(250)
  })

  it('ignores a corrupt persisted value and uses the default', () => {
    localStorage.setItem(KEY, 'not-a-number')
    const { width } = useResizablePanel({ viewportWidth: () => 2000 })
    expect(width.value).toBe(250)
  })

  it('keyboard ArrowRight widens and persists (clamped)', () => {
    const { width, onKeydown } = useResizablePanel({ viewportWidth: () => 2000 })
    onKeydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    expect(width.value).toBe(266) // 250 + 16
    expect(localStorage.getItem(KEY)).toBe('266')
  })

  it('reset() returns to the default width and persists it', () => {
    localStorage.setItem(KEY, '400')
    const { width, reset } = useResizablePanel({ viewportWidth: () => 2000 })
    expect(width.value).toBe(400)
    reset()
    expect(width.value).toBe(250)
    expect(localStorage.getItem(KEY)).toBe('250')
  })
})
