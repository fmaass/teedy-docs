import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

// The thumbnail imports `type PDFDocumentProxy` (erased at runtime) and receives the doc as a
// prop, so no pdfjs mock is needed — a hand-rolled page/doc double drives the render path.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

import PdfPageThumbnail from './PdfPageThumbnail.vue'

// jsdom has no 2D canvas; a stub context lets the render path run to completion so the
// `rendering` flag clears exactly as it does in a real browser.
beforeAll(() => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({})) as any
})

function makePage(rotate = 0) {
  return {
    rotate,
    getViewport: vi.fn(({ rotation }: { scale: number; rotation: number }) => ({
      width: 200,
      height: 200,
      rotation,
    })),
    render: vi.fn(() => ({ promise: Promise.resolve() })),
  }
}

let getPage: ReturnType<typeof vi.fn>

function mountThumb(props: Record<string, unknown> = {}, intrinsic = 0) {
  getPage = vi.fn(async () => makePage(intrinsic))
  const pdfDoc = { getPage }
  return mount(PdfPageThumbnail, {
    props: {
      pdfDoc,
      source: 2,
      rotate: 0,
      position: 3,
      canMoveBackward: true,
      canMoveForward: true,
      ...props,
    },
    global: {
      stubs: {
        // Render each PrimeVue Button as a real <button> so aria-label, disabled and native
        // click-fallthrough work without the full component tree (mirrors PdfViewer.spec).
        Button: {
          props: ['icon', 'ariaLabel', 'disabled'],
          template: '<button :aria-label="ariaLabel" :disabled="disabled"></button>',
        },
      },
    },
  })
}

describe('PdfPageThumbnail', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders the 1-based source page from the shared document', async () => {
    const wrapper = mountThumb({ source: 2 })
    await flushPromises()
    // IntersectionObserver is undefined in jsdom, so the eager fallback renders immediately.
    expect(getPage).toHaveBeenCalledWith(3)
    expect(wrapper.find('canvas').exists()).toBe(true)
    // The visible position badge shows the 1-based position.
    expect(wrapper.find('.pdf-page-number').text()).toBe('3')
  })

  it('emits the reorder/rotate/remove intents from its controls', async () => {
    const wrapper = mountThumb()
    await flushPromises()
    await wrapper.get('[aria-label="ui.pdf_organizer.move_backward"]').trigger('click')
    await wrapper.get('[aria-label="ui.pdf_organizer.move_forward"]').trigger('click')
    await wrapper.get('[aria-label="ui.pdf_organizer.rotate_left"]').trigger('click')
    await wrapper.get('[aria-label="ui.pdf_organizer.rotate_right"]').trigger('click')
    await wrapper.get('[aria-label="ui.pdf_organizer.delete_page"]').trigger('click')
    expect(wrapper.emitted('move-backward')).toHaveLength(1)
    expect(wrapper.emitted('move-forward')).toHaveLength(1)
    expect(wrapper.emitted('rotate-left')).toHaveLength(1)
    expect(wrapper.emitted('rotate-right')).toHaveLength(1)
    expect(wrapper.emitted('remove')).toHaveLength(1)
  })

  it('disables the move controls at the ends of the order', async () => {
    const wrapper = mountThumb({ canMoveBackward: false, canMoveForward: true })
    await flushPromises()
    expect(
      (wrapper.get('[aria-label="ui.pdf_organizer.move_backward"]').element as HTMLButtonElement).disabled,
    ).toBe(true)
    expect(
      (wrapper.get('[aria-label="ui.pdf_organizer.move_forward"]').element as HTMLButtonElement).disabled,
    ).toBe(false)
  })

  it('re-renders when the rotation changes', async () => {
    const wrapper = mountThumb({ rotate: 0 })
    await flushPromises()
    const callsAfterMount = getPage.mock.calls.length
    await wrapper.setProps({ rotate: 90 })
    await flushPromises()
    expect(getPage.mock.calls.length).toBeGreaterThan(callsAfterMount)
    const page = await getPage.mock.results[getPage.mock.results.length - 1].value
    expect(page.getViewport).toHaveBeenCalledWith(expect.objectContaining({ rotation: 90 }))
  })

  it('renders the ABSOLUTE rotation directly, without double-counting the intrinsic angle', async () => {
    // Source page with intrinsic 90°; the parent passes the absolute 180 (intrinsic already folded).
    mountThumb({ rotate: 180 }, 90)
    await flushPromises()
    const page = await getPage.mock.results[getPage.mock.results.length - 1].value
    // The viewport uses the absolute 180 the parent gave — NOT 90 + 180 = 270.
    expect(page.getViewport).toHaveBeenCalledWith(expect.objectContaining({ rotation: 180 }))
    expect(page.getViewport).not.toHaveBeenCalledWith(expect.objectContaining({ rotation: 270 }))
  })
})
