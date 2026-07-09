import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
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

describe('useResizablePanel pointer drag', () => {
  const KEY = 'teedy_tag_panel_width'

  beforeEach(() => localStorage.clear())
  afterEach(() => {
    localStorage.clear()
    document.body.style.removeProperty('cursor')
    document.body.style.removeProperty('user-select')
  })

  function pointerEvent(type: string, init: Partial<PointerEvent> = {}): PointerEvent {
    // jsdom lacks a PointerEvent ctor; forge one on a MouseEvent whose read-only
    // button/isPrimary getters are overridden to the requested values.
    const e = new MouseEvent(type, { clientX: init.clientX ?? 0, button: init.button ?? 0 })
    Object.defineProperty(e, 'isPrimary', { value: init.isPrimary ?? true, configurable: true })
    Object.defineProperty(e, 'button', { value: init.button ?? 0, configurable: true })
    return e as unknown as PointerEvent
  }

  it('ignores a non-primary (right/middle) button press', () => {
    const { width, startDrag } = useResizablePanel({ viewportWidth: () => 2000 })
    const before = width.value
    startDrag(pointerEvent('pointerdown', { button: 2, clientX: 100 }))
    // No move handler should be armed: a subsequent pointermove must not resize.
    window.dispatchEvent(pointerEvent('pointermove', { clientX: 400 }))
    expect(width.value).toBe(before)
  })

  it('ignores a non-primary pointer (isPrimary false)', () => {
    const { width, startDrag } = useResizablePanel({ viewportWidth: () => 2000 })
    const before = width.value
    startDrag(pointerEvent('pointerdown', { isPrimary: false, clientX: 100 }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: 400 }))
    expect(width.value).toBe(before)
  })

  it('resizes on a primary-button drag and persists on pointerup', () => {
    const { width, startDrag } = useResizablePanel({ viewportWidth: () => 2000 })
    startDrag(pointerEvent('pointerdown', { clientX: 100 }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: 150 }))
    expect(width.value).toBe(300) // 250 + 50
    expect(document.body.style.cursor).toBe('col-resize')
    window.dispatchEvent(pointerEvent('pointerup', { clientX: 150 }))
    expect(localStorage.getItem(KEY)).toBe('300')
    // Listeners removed: another move must not resize.
    window.dispatchEvent(pointerEvent('pointermove', { clientX: 400 }))
    expect(width.value).toBe(300)
  })

  it('pointercancel ends the drag (restores cursor/user-select, removes listeners)', () => {
    const { width, startDrag } = useResizablePanel({ viewportWidth: () => 2000 })
    startDrag(pointerEvent('pointerdown', { clientX: 100 }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: 150 }))
    expect(width.value).toBe(300)
    window.dispatchEvent(pointerEvent('pointercancel', { clientX: 150 }))
    expect(document.body.style.cursor).toBe('')
    expect(document.body.style.userSelect).toBe('')
    // Move handler gone: further movement is ignored.
    window.dispatchEvent(pointerEvent('pointermove', { clientX: 400 }))
    expect(width.value).toBe(300)
  })
})

describe('useResizablePanel window resize re-clamp', () => {
  const KEY = 'teedy_tag_panel_width'
  let viewport = 2000

  beforeEach(() => {
    localStorage.clear()
    viewport = 2000
  })
  afterEach(() => localStorage.clear())

  it('re-clamps an oversized width when the viewport shrinks (no persist)', () => {
    localStorage.setItem(KEY, '460')
    // Mount inside a component so the resize listener registers (getCurrentInstance()).
    let panel!: ReturnType<typeof useResizablePanel>
    const wrapper = mount({
      template: '<div />',
      setup() {
        panel = useResizablePanel({ viewportWidth: () => viewport })
        return {}
      },
    })
    expect(panel.width.value).toBe(460)
    // Viewport shrinks: 1000 * 0.4 = 400 becomes the binding cap.
    viewport = 1000
    window.dispatchEvent(new Event('resize'))
    expect(panel.width.value).toBe(400)
    // Re-clamp must NOT persist the shrunk value — the user's preference stays.
    expect(localStorage.getItem(KEY)).toBe('460')
    wrapper.unmount()
    // After unmount the listener is gone: a further resize does nothing.
    viewport = 3000
    window.dispatchEvent(new Event('resize'))
    expect(panel.width.value).toBe(400)
  })
})
