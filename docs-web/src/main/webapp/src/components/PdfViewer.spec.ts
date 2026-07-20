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

function mountViewer(src = 'blob:doc-1', extraProps: Record<string, unknown> = {}) {
  return mount(PdfViewer, {
    // Default to persistable so the rotate controls are present for the rotation-LOGIC tests
    // (viewport composition, cycling, page-nav persistence). The visibility gating itself has its
    // own dedicated tests that set persistable explicitly.
    props: { src, persistable: true, ...extraProps },
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

  it('resets user rotation to the initialRotation prop when src changes', async () => {
    // initialRotation is the PERSISTED rotation (the PDF original is not baked; pdf.js applies it).
    // On a src change the viewer re-seeds from initialRotation, NOT hardcoded 0.
    const wrapper = mountViewer('blob:doc-1', { initialRotation: 90 })
    await flushPromises()
    await settleRenders()

    await wrapper.get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()
    await settleRenders()

    viewportCalls.length = 0
    await wrapper.setProps({ src: 'blob:doc-2' })
    await flushPromises()
    await settleRenders()

    // Fresh doc → user rotation re-seeded to initialRotation(90) → (intrinsic 90 + 90) = 180.
    expect(viewportCalls.length).toBeGreaterThanOrEqual(2)
    for (const call of viewportCalls) {
      expect(call.rotation).toBe(normalized(90))
    }
  })

  it('seeds the initial rotation from the initialRotation prop', async () => {
    // A viewer opened on an already-rotated file must render at that rotation from the first frame,
    // not upright: (intrinsic 90 + initialRotation 180) = 270 on the very first getViewport calls.
    const wrapper = mountViewer('blob:doc-1', { initialRotation: 180 })
    await flushPromises()
    await settleRenders()

    expect(viewportCalls.length).toBeGreaterThanOrEqual(2)
    for (const call of viewportCalls) {
      expect(call.rotation).toBe(normalized(180))
    }
    wrapper.unmount()
  })

  it('emits a rotate event with the new absolute rotation when persistable', async () => {
    const wrapper = mountViewer('blob:doc-1', { initialRotation: 0, persistable: true })
    await flushPromises()
    await settleRenders()

    await wrapper.get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()
    await settleRenders()

    const events = wrapper.emitted('rotate')
    expect(events).toBeTruthy()
    // First rotate-right from 0 → user rotation 90 (the value the parent persists).
    expect(events![0]).toEqual([90])
  })

  it('HIDES the rotate controls entirely when not persistable (read-only/share)', async () => {
    const wrapper = mountViewer('blob:doc-1', { persistable: false })
    await flushPromises()
    await settleRenders()

    // A READ-only/share viewer must not even see the rotate buttons (they persist).
    expect(wrapper.find('[aria-label="ui.rotate_right"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="ui.rotate_left"]').exists()).toBe(false)
    expect(wrapper.emitted('rotate')).toBeFalsy()
  })

  it('SHOWS the rotate controls when persistable (writable)', async () => {
    const wrapper = mountViewer('blob:doc-1', { persistable: true })
    await flushPromises()
    await settleRenders()

    expect(wrapper.find('[aria-label="ui.rotate_right"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="ui.rotate_left"]').exists()).toBe(true)
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

// #144 — `src` is the ORIGINAL file URL, which the backend serves as an attachment. The
// viewer must therefore expose it ONLY through a Download-labelled control (never an
// unlabelled "open in new tab" that downloads all the same), and must let a parent that
// owns its own Download opt the viewer's control out entirely (downloadable=false). t() is
// stubbed to the key, so 'download' is the reference key, not translated copy.
describe('PdfViewer — original-URL control is Download-only (#144)', () => {
  const originalAnchors = (wrapper: ReturnType<typeof mountViewer>, src: string) =>
    wrapper.findAll('a').filter((a) => a.attributes('href') === src)

  it('exposes the original URL only as a Download control, never an "open in new tab" link', async () => {
    const wrapper = mountViewer('blob:doc-1')
    await flushPromises()
    await settleRenders()
    const anchors = originalAnchors(wrapper, 'blob:doc-1')
    expect(anchors.length).toBeGreaterThan(0)
    for (const a of anchors) {
      expect(a.attributes('aria-label')).toBe('download')
      expect(a.attributes()).toHaveProperty('download')
    }
    // The old, invariant-violating label must be gone everywhere.
    expect(wrapper.html()).not.toContain('ui.open_new_tab')
  })

  it('downloadable=false renders NO original-URL anchor (the parent owns Download)', async () => {
    const wrapper = mountViewer('blob:doc-1', { downloadable: false })
    await flushPromises()
    await settleRenders()
    expect(originalAnchors(wrapper, 'blob:doc-1').length).toBe(0)
  })

  it('a load failure emits `error`; its fallback is a Download, never an unlabelled open link', async () => {
    getDocumentMock.mockReturnValueOnce({ promise: Promise.reject(new Error('boom')) })
    const wrapper = mountViewer('blob:doc-err')
    await flushPromises()
    expect(wrapper.emitted('error')).toBeTruthy()
    for (const a of originalAnchors(wrapper, 'blob:doc-err')) {
      expect(a.attributes('aria-label')).toBe('download')
    }
    expect(wrapper.html()).not.toContain('ui.open_new_tab')
  })

  it('a load failure with downloadable=false still emits `error` and exposes no original-URL anchor', async () => {
    getDocumentMock.mockReturnValueOnce({ promise: Promise.reject(new Error('boom')) })
    const wrapper = mountViewer('blob:doc-err2', { downloadable: false })
    await flushPromises()
    expect(wrapper.emitted('error')).toBeTruthy()
    expect(originalAnchors(wrapper, 'blob:doc-err2').length).toBe(0)
  })

  it('a stale load rejection from a superseded src does not error the current preview (load race)', async () => {
    // Open A (getDocument pending), switch to B (loads fine), THEN let A reject late. The
    // stale rejection must not flip the reused viewer to its error state or emit `error`,
    // which would replace B's valid preview with the failure UI.
    let rejectA!: (e: unknown) => void
    getDocumentMock
      .mockReturnValueOnce({ promise: new Promise((_resolve, reject) => (rejectA = reject)) })
      .mockReturnValueOnce({
        promise: Promise.resolve({ numPages: 2, getPage: getPageMock, destroy: vi.fn() }),
      })

    const wrapper = mountViewer('blob:A')
    await wrapper.setProps({ src: 'blob:B' })
    await flushPromises()
    await settleRenders()

    rejectA(new Error('A aborted'))
    await flushPromises()

    expect(wrapper.emitted('error')).toBeFalsy()
    expect(wrapper.find('.pdf-error').exists()).toBe(false)
  })
})

// #144 — the render path shares the ONE generation token, so a stale render (its document
// destroyed, its getPage resolving/rejecting late, or the viewer unmounted) can neither
// error the current preview nor write the canvas out from under a newer render.
describe('PdfViewer — one generation guards load AND render (#144)', () => {
  // A document whose getPage stays pending until the test resolves/rejects it. `rejectOnDestroy`
  // models pdf.js rejecting a still-pending getPage when the document is torn down; without it,
  // destroy() leaves getPage pending so a LATE RESOLUTION can be exercised.
  function controllableDoc(opts: { numPages?: number; rejectOnDestroy?: boolean } = {}) {
    let settle: { resolve: (p: unknown) => void; reject: (e: unknown) => void } | null = null
    const getPage = vi.fn(
      () => new Promise((resolve, reject) => (settle = { resolve, reject })),
    )
    const destroy = vi.fn(() => {
      if (opts.rejectOnDestroy) settle?.reject(new Error('document destroyed'))
    })
    return {
      doc: { numPages: opts.numPages ?? 1, getPage, destroy },
      getPage,
      resolvePage: (p: unknown) => settle?.resolve(p),
    }
  }

  it('a stale render whose getPage REJECTS on document destroy does not error the current preview', async () => {
    // A is mid-render at getPage; loading B destroys A's document, rejecting that getPage.
    const a = controllableDoc({ rejectOnDestroy: true })
    const bDoc = { numPages: 2, getPage: getPageMock, destroy: vi.fn() }
    getDocumentMock
      .mockReturnValueOnce({ promise: Promise.resolve(a.doc) })
      .mockReturnValueOnce({ promise: Promise.resolve(bDoc) })

    const wrapper = mountViewer('blob:A')
    await flushPromises()
    expect(a.getPage).toHaveBeenCalled() // A is suspended in renderPage at getPage

    await wrapper.setProps({ src: 'blob:B' })
    await flushPromises()
    await settleRenders()

    // A's stale getPage rejection lands in a superseded-generation catch → silent.
    expect(wrapper.emitted('error')).toBeFalsy()
    expect(wrapper.find('.pdf-error').exists()).toBe(false)
    expect(wrapper.find('.pdf-canvas-container').exists()).toBe(true) // B rendered
  })

  it('a late getPage RESOLUTION from a superseded render never touches the canvas', async () => {
    // destroy() leaves A's getPage pending, so it can resolve AFTER B claimed the generation.
    const a = controllableDoc()
    const bDoc = { numPages: 2, getPage: getPageMock, destroy: vi.fn() }
    getDocumentMock
      .mockReturnValueOnce({ promise: Promise.resolve(a.doc) })
      .mockReturnValueOnce({ promise: Promise.resolve(bDoc) })

    const wrapper = mountViewer('blob:A')
    await flushPromises() // A suspended at getPage
    await wrapper.setProps({ src: 'blob:B' })
    await flushPromises()
    await settleRenders() // B fully rendered (viewer still mounted → container is non-null)
    viewportCalls.length = 0

    // A's getPage resolves LATE — the generation moved on, so A returns before sizing/writing
    // the canvas (getViewport is the first canvas-touching step). The non-null container proves
    // it is the generation check, not the unmount container-null guard, that stops A.
    a.resolvePage(makePage())
    await flushPromises()

    expect(viewportCalls.length).toBe(0)
    expect(wrapper.emitted('error')).toBeFalsy()
  })

  it('unmount during an in-flight render writes no canvas and emits nothing', async () => {
    // Behavioural contract: a render in flight when the viewer unmounts must not write the
    // canvas or emit. (Redundantly guarded — the shared generation bump AND the template ref
    // going null on unmount both stop it — so this documents the behaviour rather than
    // isolating one guard.)
    const only = controllableDoc()
    getDocumentMock.mockReturnValueOnce({ promise: Promise.resolve(only.doc) })

    const wrapper = mountViewer('blob:only')
    await flushPromises()
    expect(only.getPage).toHaveBeenCalled()

    wrapper.unmount()
    viewportCalls.length = 0
    only.resolvePage(makePage()) // getPage resolves after unmount
    await flushPromises()

    expect(viewportCalls.length).toBe(0)
    expect(wrapper.emitted('error')).toBeFalsy()
  })

  it('unmount during an in-flight LOAD destroys the late-resolved document instead of leaking it', async () => {
    // The observable proof that unmount bumps the shared generation: a getDocument that
    // resolves AFTER unmount must have its document destroyed (it cannot be attached to a dead
    // viewer, which would leak it), and must not emit.
    const destroy = vi.fn()
    let resolveDoc!: (d: unknown) => void
    getDocumentMock.mockReturnValueOnce({ promise: new Promise((res) => (resolveDoc = res)) })

    const wrapper = mountViewer('blob:only')
    await flushPromises() // loadPdf suspended at getDocument

    wrapper.unmount() // bumps the generation
    resolveDoc({ numPages: 1, getPage: getPageMock, destroy })
    await flushPromises()

    expect(destroy).toHaveBeenCalled()
    expect(wrapper.emitted('error')).toBeFalsy()
  })
})
