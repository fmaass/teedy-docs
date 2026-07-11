import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

// #35 — view-only PDF rotation. pdf.js is a DEPENDENCY and is mocked at the
// module boundary ONLY. The mock faithfully models the parts of pdf.js the
// component's rotation logic depends on:
//
//   - page.rotate: the page's INTRINSIC rotation (the mock uses a non-zero value
//     so a "compose intrinsic + user rotation" bug is observable).
//   - getViewport({ scale, rotation }): pdf.js REPLACES page.rotate with the
//     explicit `rotation` (does not add). The mock records every call and models
//     the width/height swap at 90/270 so the component's fit-scale + canvas sizing
//     runs against realistic geometry.
//   - page.render(...).cancel(): the render cancellation the component uses to
//     drop a superseded render.
//
// The component (PdfViewer.vue) is the unit under test. Nothing here constructs
// the component's expected output and asserts it against itself.

const INTRINSIC_ROTATE = 90 // non-zero intrinsic page rotation
const BASE_W = 200
const BASE_H = 300

// Every getViewport call, in order, with the args the component passed.
const viewportCalls: Array<{ scale: number; rotation?: number }> = []
// Render invocations, so we can assert cancellation of superseded renders.
const renderCancels: number[] = []
let renderResolvers: Array<() => void> = []

function makeViewport(opts: { scale: number; rotation?: number }) {
  viewportCalls.push({ scale: opts.scale, rotation: opts.rotation })
  const rot = ((opts.rotation ?? 0) % 360 + 360) % 360
  // At 90/270 the on-screen dimensions swap. Intrinsic rotate is REPLACED by the
  // explicit rotation, exactly like pdf.js.
  const sideways = rot === 90 || rot === 270
  const w = (sideways ? BASE_H : BASE_W) * opts.scale
  const h = (sideways ? BASE_W : BASE_H) * opts.scale
  return { width: w, height: h }
}

function makePage() {
  return {
    rotate: INTRINSIC_ROTATE,
    getViewport: (opts: { scale: number; rotation?: number }) => makeViewport(opts),
    render: () => {
      let cancelled = false
      const idx = renderCancels.length
      renderCancels.push(0)
      const promise = new Promise<void>((resolve, reject) => {
        renderResolvers.push(() => {
          if (cancelled) reject({ name: 'RenderingCancelledException' })
          else resolve()
        })
      })
      return {
        promise,
        cancel: () => {
          cancelled = true
          renderCancels[idx] = 1
        },
      }
    },
  }
}

const getPageMock = vi.fn(async () => makePage())

const getDocumentMock = vi.fn(() => ({
  promise: Promise.resolve({
    numPages: 3,
    getPage: getPageMock,
    destroy: vi.fn(),
  }),
}))

vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: {},
  getDocument: (...args: unknown[]) => getDocumentMock(...(args as [])),
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

import PdfViewer from './PdfViewer.vue'

function mountViewer(src = 'blob:doc-1') {
  return mount(PdfViewer, {
    props: { src },
    global: {
      plugins: [PrimeVue],
      stubs: {
        // Render PrimeVue Button as a real button so aria-label + click work,
        // without pulling the full component tree.
        // No @click in the stub: the parent's `@click="rotateRight"` falls
        // through to this button root as a native listener. Emitting a `click`
        // here too would double-fire the handler (native fallthrough + emit).
        Button: {
          props: ['icon', 'ariaLabel', 'disabled'],
          template: '<button :aria-label="ariaLabel" :disabled="disabled"></button>',
        },
      },
    },
  })
}

// Drain the queued render promises so awaited renders settle.
async function settleRenders() {
  const pending = renderResolvers
  renderResolvers = []
  pending.forEach((r) => r())
  await flushPromises()
}

beforeEach(() => {
  viewportCalls.length = 0
  renderCancels.length = 0
  renderResolvers = []
  getPageMock.mockClear()
  getDocumentMock.mockClear()
})

// Normalized rotation the component must pass: (intrinsic + user) % 360.
const normalized = (user: number) => ((INTRINSIC_ROTATE + user) % 360 + 360) % 360

describe('PdfViewer rotation (#35)', () => {
  it('composes intrinsic page.rotate with user rotation on BOTH getViewport calls', async () => {
    const wrapper = mountViewer()
    await flushPromises()
    await settleRenders()

    // Initial render: user rotation 0 → both getViewport calls carry the intrinsic 90.
    const initial = viewportCalls.slice()
    expect(initial.length).toBeGreaterThanOrEqual(2)
    for (const call of initial) {
      expect(call.rotation).toBe(normalized(0))
    }

    viewportCalls.length = 0
    // Rotate right (user +90). Both getViewport calls must now carry (90 + 90) = 180.
    await wrapper.get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()
    await settleRenders()

    expect(viewportCalls.length).toBeGreaterThanOrEqual(2)
    for (const call of viewportCalls) {
      expect(call.rotation).toBe(normalized(90))
    }
  })

  it('cycles rotation through 90/180/270/0 with rotate-right', async () => {
    const wrapper = mountViewer()
    await flushPromises()
    await settleRenders()

    const seen: number[] = []
    for (let i = 0; i < 4; i++) {
      viewportCalls.length = 0
      await wrapper.get('[aria-label="ui.rotate_right"]').trigger('click')
      await flushPromises()
      await settleRenders()
      seen.push(viewportCalls[0].rotation!)
    }
    // After 4 right-rotations the user rotation returns to 0 → back to intrinsic 90.
    expect(seen).toEqual([
      normalized(90),
      normalized(180),
      normalized(270),
      normalized(0),
    ])
  })

  it('rotate-left steps the opposite direction', async () => {
    const wrapper = mountViewer()
    await flushPromises()
    await settleRenders()

    viewportCalls.length = 0
    await wrapper.get('[aria-label="ui.rotate_left"]').trigger('click')
    await flushPromises()
    await settleRenders()
    // rotate-left = user -90 = +270 → (90 + 270) % 360 = 0.
    expect(viewportCalls[0].rotation).toBe(normalized(270))
  })

  it('canvas dimensions follow the ROTATED viewport (sideways swap)', async () => {
    const wrapper = mountViewer()
    await flushPromises()
    await settleRenders()
    const canvas = wrapper.find('.pdf-canvas-container canvas').element as HTMLCanvasElement

    // Intrinsic 90 → already sideways: canvas width derives from BASE_H, height from BASE_W.
    const initialLandscape = canvas.width > canvas.height
    expect(initialLandscape).toBe(true) // BASE_H(300) > BASE_W(200) after swap

    // Rotate +90 → total 180 → NOT sideways → dimensions return to portrait.
    await wrapper.get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()
    await settleRenders()
    const after = wrapper.find('.pdf-canvas-container canvas').element as HTMLCanvasElement
    expect(after.width < after.height).toBe(true)
  })

  it('persists user rotation across page navigation', async () => {
    const wrapper = mountViewer()
    await flushPromises()
    await settleRenders()

    // Rotate to user +90.
    await wrapper.get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()
    await settleRenders()

    viewportCalls.length = 0
    // Navigate to the next page — the rotation must still be applied.
    await wrapper.get('[aria-label="ui.next_page"]').trigger('click')
    await flushPromises()
    await settleRenders()

    expect(viewportCalls.length).toBeGreaterThanOrEqual(2)
    for (const call of viewportCalls) {
      expect(call.rotation).toBe(normalized(90))
    }
  })

  it('resets user rotation to 0 when src changes', async () => {
    const wrapper = mountViewer()
    await flushPromises()
    await settleRenders()

    await wrapper.get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()
    await settleRenders()

    viewportCalls.length = 0
    await wrapper.setProps({ src: 'blob:doc-2' })
    await flushPromises()
    await settleRenders()

    // Fresh doc → user rotation reset → back to the intrinsic-only baseline.
    expect(viewportCalls.length).toBeGreaterThanOrEqual(2)
    for (const call of viewportCalls) {
      expect(call.rotation).toBe(normalized(0))
    }
  })

  it('cancels a superseded render when rotation triggers a re-render mid-flight', async () => {
    const wrapper = mountViewer()
    await flushPromises()
    await settleRenders()

    // Start a render and rotate before it settles: the first render must be cancelled.
    renderCancels.length = 0
    renderResolvers = []
    await wrapper.get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()
    // First render is in flight (unresolved). Rotate again → supersede it.
    await wrapper.get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()

    // At least one of the started renders was cancelled (the superseded one).
    expect(renderCancels.some((c) => c === 1)).toBe(true)
    await settleRenders()
  })
})
