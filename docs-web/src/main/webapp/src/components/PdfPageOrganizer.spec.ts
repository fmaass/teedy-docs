import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

// --- Module mocks (pdfjs, the file API, toast, query client, i18n) ------------
const getPageMock = vi.fn(async () => ({
  rotate: 0,
  getViewport: () => ({ width: 200, height: 200 }),
  render: () => ({ promise: Promise.resolve() }),
}))
const destroyMock = vi.fn()
const getDocumentMock = vi.fn((_src: string) => ({
  promise: Promise.resolve({ numPages: 3, getPage: getPageMock, destroy: destroyMock }),
}))
vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: {},
  getDocument: (...args: unknown[]) => getDocumentMock(...(args as [string])),
}))

const getFileVersionsMock = vi.fn(async () => [
  { id: 'f1', name: 'doc.pdf', version: 2, mimetype: 'application/pdf', create_date: 0 },
])
const applyMock = vi.fn(async () => ({ data: { status: 'ok', id: 'f2' } }))
vi.mock('../api/file', () => ({
  getFileUrl: (id: string) => `api/file/${id}/data`,
  getFileVersions: (...args: unknown[]) => getFileVersionsMock(...(args as [string])),
  applyPageOperations: (...args: unknown[]) => applyMock(...(args as [string, unknown])),
}))

const toastAdd = vi.fn()
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: toastAdd }) }))
const invalidateMock = vi.fn()
vi.mock('@tanstack/vue-query', () => ({ useQueryClient: () => ({ invalidateQueries: invalidateMock }) }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

import PdfPageOrganizer from './PdfPageOrganizer.vue'

// Presentational thumbnail stub: exposes the props the organizer computes and re-emits the
// reorder/rotate/remove intents, so the organizer's ORCHESTRATION (order, rotation, manifest,
// save, errors) is what these tests exercise — the real canvas render lives in its own spec.
const ThumbStub = {
  props: ['pdfDoc', 'source', 'rotate', 'position', 'canMoveBackward', 'canMoveForward', 'disabled'],
  emits: ['move-backward', 'move-forward', 'rotate-left', 'rotate-right', 'remove'],
  template: `<div class="thumb" :data-source="source" :data-rotate="rotate" :data-position="position">
    <button class="mb" :disabled="!canMoveBackward" @click="$emit('move-backward')"></button>
    <button class="mf" :disabled="!canMoveForward" @click="$emit('move-forward')"></button>
    <button class="rl" @click="$emit('rotate-left')"></button>
    <button class="rr" @click="$emit('rotate-right')"></button>
    <button class="rm" @click="$emit('remove')"></button>
  </div>`,
}

function mountOrg(): VueWrapper {
  return mount(PdfPageOrganizer, {
    props: { fileId: 'f1', fileName: 'doc.pdf' },
    global: {
      stubs: {
        PdfPageThumbnail: ThumbStub,
        Dialog: {
          props: ['visible', 'header'],
          template: '<div v-if="visible" class="dialog"><slot /><div class="footer"><slot name="footer" /></div></div>',
        },
        Button: {
          props: ['label', 'icon', 'ariaLabel', 'disabled', 'loading', 'severity'],
          template: '<button :aria-label="ariaLabel" :disabled="disabled">{{ label }}</button>',
        },
        Message: { template: '<div class="msg"><slot /></div>' },
        ProgressSpinner: { template: '<div class="spinner" />' },
        ErrorState: {
          props: ['message'],
          emits: ['retry'],
          template: '<div class="error-state"><button class="retry" @click="$emit(\'retry\')">{{ message }}</button></div>',
        },
        EmptyState: { props: ['icon', 'message'], template: '<div class="empty-state">{{ message }}</div>' },
      },
    },
  })
}

async function openDialog(wrapper: VueWrapper) {
  await wrapper.get('[aria-label="ui.pdf_organizer.edit_pages"]').trigger('click')
  await flushPromises()
}

function sources(wrapper: VueWrapper): string[] {
  return wrapper.findAll('.thumb').map((t) => t.attributes('data-source') as string)
}

async function mountAndOpen(): Promise<VueWrapper> {
  const wrapper = mountOrg()
  await openDialog(wrapper)
  return wrapper
}

const saveBtn = (w: VueWrapper) => w.get('[data-test="organizer-save"]').element as HTMLButtonElement

beforeEach(() => {
  vi.clearAllMocks()
  getPageMock.mockImplementation(async () => ({
    rotate: 0,
    getViewport: () => ({ width: 200, height: 200 }),
    render: () => ({ promise: Promise.resolve() }),
  }))
  getDocumentMock.mockImplementation((_src: string) => ({
    promise: Promise.resolve({ numPages: 3, getPage: getPageMock, destroy: destroyMock }),
  }))
  getFileVersionsMock.mockImplementation(async () => [
    { id: 'f1', name: 'doc.pdf', version: 2, mimetype: 'application/pdf', create_date: 0 },
  ])
  applyMock.mockImplementation(async () => ({ data: { status: 'ok', id: 'f2' } }))
})

describe('PdfPageOrganizer', () => {
  it('loads and lists every page of the current pdf when opened', async () => {
    const wrapper = mountOrg()
    // Nothing is rendered (and nothing fetched) until the trigger is clicked.
    expect(wrapper.find('[data-test="organizer-grid"]').exists()).toBe(false)
    expect(getDocumentMock).not.toHaveBeenCalled()

    await openDialog(wrapper)

    expect(getFileVersionsMock).toHaveBeenCalledWith('f1')
    expect(getDocumentMock).toHaveBeenCalledWith('api/file/f1/data')
    expect(sources(wrapper)).toEqual(['0', '1', '2'])
    expect(wrapper.findAll('.thumb').map((t) => t.attributes('data-position'))).toEqual(['1', '2', '3'])
  })

  it('reorders pages (keeping the source identity) via a move control', async () => {
    const wrapper = await mountAndOpen()
    await wrapper.findAll('.thumb')[0].get('.mf').trigger('click')
    expect(sources(wrapper)).toEqual(['1', '0', '2'])
    // Positions reflow to the new order.
    expect(wrapper.findAll('.thumb').map((t) => t.attributes('data-position'))).toEqual(['1', '2', '3'])
  })

  it('deletes a page from the working order', async () => {
    const wrapper = await mountAndOpen()
    await wrapper.findAll('.thumb')[1].get('.rm').trigger('click')
    expect(sources(wrapper)).toEqual(['0', '2'])
  })

  it('applies an absolute per-page rotation', async () => {
    const wrapper = await mountAndOpen()
    await wrapper.findAll('.thumb')[0].get('.rr').trigger('click')
    expect(wrapper.findAll('.thumb')[0].attributes('data-rotate')).toBe('90')
    // Rotating left three more times wraps back to 0 (absolute, mod 360).
    await wrapper.findAll('.thumb')[0].get('.rl').trigger('click')
    expect(wrapper.findAll('.thumb')[0].attributes('data-rotate')).toBe('0')
  })

  it('keeps Save disabled until an edit makes the order dirty', async () => {
    const wrapper = await mountAndOpen()
    expect(saveBtn(wrapper).disabled).toBe(true)
    await wrapper.findAll('.thumb')[0].get('.rr').trigger('click')
    expect(saveBtn(wrapper).disabled).toBe(false)
  })

  it('posts the manifest with the base version, refreshes and closes on save', async () => {
    const wrapper = await mountAndOpen()
    // Reorder + rotate + delete so the manifest exercises all three operations.
    await wrapper.findAll('.thumb')[0].get('.rr').trigger('click') // source 0 -> rotate 90
    await wrapper.findAll('.thumb')[2].get('.rm').trigger('click') // drop source 2
    await wrapper.findAll('.thumb')[0].get('.mf').trigger('click') // move source 0 after source 1

    await wrapper.get('[data-test="organizer-save"]').trigger('click')
    await flushPromises()

    expect(applyMock).toHaveBeenCalledWith('f1', {
      version: 1,
      baseVersion: 2,
      // Absolute rotation is posted for every kept page (the unchanged 0°-intrinsic page carries 0).
      pages: [
        { source: 1, rotate: 0 },
        { source: 0, rotate: 90 },
      ],
    })
    expect(toastAdd).toHaveBeenCalledWith(expect.objectContaining({ severity: 'success' }))
    expect(invalidateMock).toHaveBeenCalledWith({ queryKey: ['document'] })
    // The dialog closed on success.
    expect(wrapper.find('[data-test="organizer-grid"]').exists()).toBe(false)
  })

  it('seeds intrinsic rotation and posts the absolute (intrinsic + delta) value', async () => {
    // Page 2 (source 1) has an intrinsic 90° rotation (matching the fixture); the others are 0°.
    getPageMock.mockImplementation(async (n: number) => ({
      rotate: n === 2 ? 90 : 0,
      getViewport: () => ({ width: 200, height: 200 }),
      render: () => ({ promise: Promise.resolve() }),
    }))
    const wrapper = await mountAndOpen()
    const thumbs = () => wrapper.findAll('.thumb')
    // The intrinsic 90° page is previewed at 90° from the start — seeded, not reset to 0.
    expect(thumbs()[1].attributes('data-rotate')).toBe('90')

    // Rotate the 0°-intrinsic page once (→90) and the 90°-intrinsic page once (→180).
    await thumbs()[0].get('.rr').trigger('click')
    await thumbs()[1].get('.rr').trigger('click')
    // Preview reflects the ABSOLUTE angle (intrinsic + delta), which is exactly what is posted.
    expect(thumbs()[0].attributes('data-rotate')).toBe('90')
    expect(thumbs()[1].attributes('data-rotate')).toBe('180')

    await wrapper.get('[data-test="organizer-save"]').trigger('click')
    await flushPromises()
    expect(applyMock).toHaveBeenCalledWith('f1', {
      version: 1,
      baseVersion: 2,
      pages: [
        { source: 0, rotate: 90 },
        { source: 1, rotate: 180 },
        { source: 2, rotate: 0 },
      ],
    })
  })

  it('blocks saving an empty PDF and shows the keep-a-page hint', async () => {
    const wrapper = await mountAndOpen()
    for (let i = 0; i < 3; i++) await wrapper.findAll('.thumb')[0].get('.rm').trigger('click')
    expect(wrapper.findAll('.thumb')).toHaveLength(0)
    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(saveBtn(wrapper).disabled).toBe(true)
    expect(applyMock).not.toHaveBeenCalled()
  })

  it('shows a load-error state and reloads on retry', async () => {
    getDocumentMock.mockImplementationOnce(() => ({ promise: Promise.reject(new Error('boom')) }))
    const wrapper = await mountAndOpen()
    expect(wrapper.find('.error-state').exists()).toBe(true)
    expect(wrapper.find('[data-test="organizer-grid"]').exists()).toBe(false)

    await wrapper.get('.retry').trigger('click')
    await flushPromises()
    expect(sources(wrapper)).toEqual(['0', '1', '2'])
  })

  const errorCases: Array<[string, { response: { status: number; data: { type: string } } }, string]> = [
    ['409 conflict', { response: { status: 409, data: { type: 'VersionConflict' } } }, 'ui.pdf_organizer.error.stale'],
    ['400 base mismatch', { response: { status: 400, data: { type: 'BaseVersionMismatch' } } }, 'ui.pdf_organizer.error.stale'],
    ['400 too many pages', { response: { status: 400, data: { type: 'TooManyPages' } } }, 'ui.pdf_organizer.error.too_many_pages'],
    ['400 signed', { response: { status: 400, data: { type: 'SignedSource' } } }, 'ui.pdf_organizer.error.signed'],
    ['400 encrypted', { response: { status: 400, data: { type: 'EncryptedSource' } } }, 'ui.pdf_organizer.error.encrypted'],
    ['400 empty', { response: { status: 400, data: { type: 'EmptyResult' } } }, 'ui.pdf_organizer.error.empty'],
    ['400 too large', { response: { status: 400, data: { type: 'SourceTooLarge' } } }, 'ui.pdf_organizer.error.too_large'],
    ['429 busy', { response: { status: 429, data: { type: 'TooManyRequests' } } }, 'ui.pdf_organizer.error.busy'],
    ['500 server/quota', { response: { status: 500, data: { type: 'FileError' } } }, 'ui.pdf_organizer.error.generic'],
  ]

  it.each(errorCases)('surfaces the %s error distinctly and stays open', async (_label, err, expected) => {
    applyMock.mockRejectedValueOnce(err)
    const wrapper = await mountAndOpen()
    await wrapper.findAll('.thumb')[0].get('.rr').trigger('click') // dirty
    await wrapper.get('[data-test="organizer-save"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="organizer-error"]').text()).toBe(expected)
    // The dialog stays open so the user can adjust and retry.
    expect(wrapper.find('[data-test="organizer-grid"]').exists()).toBe(true)
  })
})
