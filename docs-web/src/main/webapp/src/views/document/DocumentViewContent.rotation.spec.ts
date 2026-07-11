import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import PrimeVue from 'primevue/config'
import ConfirmationService from 'primevue/confirmationservice'
import ToastService from 'primevue/toastservice'
import type { DocumentDetail } from '../../api/document'
import { DocumentKey } from './documentKey'

// #35 — view-only IMAGE rotation, per file card. The API modules are
// DEPENDENCIES (mocked); the unit under test is DocumentViewContent's image
// preview rotation behavior: per-card state, and reset on document change.
//
// getFileUrl is the only API used by the image path; it is mocked to a stable URL
// so the rendered <img> src is deterministic. TanStack Vue Query's mutation
// helpers are not exercised by the rotation controls.

vi.mock('../../api/file', () => ({
  getFileUrl: (id: string, kind?: string) => `/api/file/${id}/${kind ?? 'data'}`,
  deleteFile: vi.fn(),
  renameFile: vi.fn(),
  uploadFile: vi.fn(),
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))
// PdfViewer pulls in the real pdf.js (needs DOMMatrix/canvas, absent in jsdom).
// It's stubbed at render anyway; mock the module so its import never loads pdf.js.
vi.mock('../../components/PdfViewer.vue', () => ({
  default: { name: 'PdfViewer', render: () => null },
}))
vi.mock('@tanstack/vue-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))

import DocumentViewContent from './DocumentViewContent.vue'

function makeDoc(id: string): DocumentDetail {
  return {
    id,
    title: 'Doc',
    files: [
      { id: 'img-a', name: 'a.png', mimetype: 'image/png', size: 10 },
      { id: 'img-b', name: 'b.png', mimetype: 'image/png', size: 20 },
    ],
  } as unknown as DocumentDetail
}

function mountContent(docRef: ReturnType<typeof ref<DocumentDetail | undefined>>) {
  return mount(DocumentViewContent, {
    global: {
      plugins: [PrimeVue, ConfirmationService, ToastService],
      provide: { [DocumentKey as symbol]: docRef },
      stubs: {
        PdfViewer: true,
        EmptyState: true,
        FileVersionsDialog: true,
        FileUpload: true,
        CameraCaptureButton: true,
        UploadProgressList: true,
        InputText: true,
        // No @click emit: the parent's `@click` handler falls through natively.
        Button: {
          props: ['icon', 'ariaLabel'],
          template: '<button :aria-label="ariaLabel"></button>',
        },
      },
      directives: { tooltip: {} },
    },
  })
}

// Parse the deg value off an inline `transform: rotate(Ndeg)` style.
function rotationOf(img: Element): number {
  const style = img.getAttribute('style') ?? ''
  const m = style.match(/rotate\((-?\d+)deg\)/)
  return m ? Number(m[1]) : 0
}

describe('DocumentViewContent image rotation (#35)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('rotates a single card without affecting siblings', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc('doc-1'))
    const wrapper = mountContent(docRef)
    await flushPromises()

    const cards = wrapper.findAll('.file-preview-card')
    expect(cards.length).toBe(2)

    const imgs = () => wrapper.findAll('.rotatable-image').map((w) => w.element)
    expect(imgs().map(rotationOf)).toEqual([0, 0])

    // Rotate the FIRST card right. Only it changes.
    await cards[0].get('[aria-label="ui.rotate_right"]').trigger('click')
    expect(imgs().map(rotationOf)).toEqual([90, 0])

    // Rotate the SECOND card left (=+270). First unchanged.
    await cards[1].get('[aria-label="ui.rotate_left"]').trigger('click')
    expect(imgs().map(rotationOf)).toEqual([90, 270])
  })

  it('cycles a card through 90/180/270/0', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc('doc-1'))
    const wrapper = mountContent(docRef)
    await flushPromises()
    const card = wrapper.findAll('.file-preview-card')[0]
    const img = () => wrapper.findAll('.rotatable-image')[0].element

    const seen: number[] = []
    for (let i = 0; i < 4; i++) {
      await card.get('[aria-label="ui.rotate_right"]').trigger('click')
      seen.push(rotationOf(img()))
    }
    expect(seen).toEqual([90, 180, 270, 0])
  })

  it('adds the sideways sizing class only at 90/270', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc('doc-1'))
    const wrapper = mountContent(docRef)
    await flushPromises()
    const card = wrapper.findAll('.file-preview-card')[0]
    const stage = () => wrapper.findAll('.image-preview-stage')[0]

    expect(stage().classes()).not.toContain('is-sideways')
    await card.get('[aria-label="ui.rotate_right"]').trigger('click') // 90
    expect(stage().classes()).toContain('is-sideways')
    await card.get('[aria-label="ui.rotate_right"]').trigger('click') // 180
    expect(stage().classes()).not.toContain('is-sideways')
    await card.get('[aria-label="ui.rotate_right"]').trigger('click') // 270
    expect(stage().classes()).toContain('is-sideways')
  })

  it('derives sideways sizing caps from the MEASURED stage box, not a constant', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc('doc-1'))
    const wrapper = mountContent(docRef)
    await flushPromises()

    // jsdom does no layout: give the FIRST stage a concrete box. Both axes are
    // deliberately NOT 400 so a hardcoded 400px cap cannot pass this test — a
    // narrow preview column can be well under 400px wide, and a fixed cap lets
    // a near-square image clip at 90/270.
    const stageEl = wrapper.findAll('.image-preview-stage')[0].element as HTMLElement
    Object.defineProperty(stageEl, 'clientWidth', { value: 320, configurable: true })
    Object.defineProperty(stageEl, 'clientHeight', { value: 380, configurable: true })

    const card = wrapper.findAll('.file-preview-card')[0]
    await card.get('[aria-label="ui.rotate_right"]').trigger('click')

    const img = () => wrapper.findAll('.rotatable-image')[0]
    const style = () => img().attributes('style') ?? ''
    // Sideways geometry: the pre-rotation WIDTH paints as on-screen HEIGHT →
    // capped to the stage's measured height (380). The pre-rotation HEIGHT
    // paints as on-screen WIDTH → capped to the stage's measured width (320).
    expect(style()).toContain('max-width: 380px')
    expect(style()).toContain('max-height: 320px')

    // Sibling card is not constrained by the first card's measurement.
    const siblingStyle = wrapper.findAll('.rotatable-image')[1].attributes('style') ?? ''
    expect(siblingStyle).not.toContain('max-width: 380px')

    // Back upright (180): the measured pixel caps come OFF again (upright sizing
    // is the plain 100%/100% stylesheet rule, no inline caps).
    await card.get('[aria-label="ui.rotate_right"]').trigger('click')
    expect(style()).not.toContain('max-width:')
    expect(style()).not.toContain('max-height:')
  })

  it('resets rotation when the document changes', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc('doc-1'))
    const wrapper = mountContent(docRef)
    await flushPromises()

    const card = wrapper.findAll('.file-preview-card')[0]
    await card.get('[aria-label="ui.rotate_right"]').trigger('click')
    expect(rotationOf(wrapper.findAll('.rotatable-image')[0].element)).toBe(90)

    // Switch to a different document — rotation state must reset to 0.
    docRef.value = makeDoc('doc-2')
    await flushPromises()
    expect(rotationOf(wrapper.findAll('.rotatable-image')[0].element)).toBe(0)
  })
})
