import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ConfirmationService from 'primevue/confirmationservice'
import ToastService from 'primevue/toastservice'
import type { DocumentDetail } from '../../api/document'
import { DocumentKey } from './documentKey'

// The view derives its per-user file-view-mode key from the auth store, so every mount needs an
// active pinia (no user → anonymous username → grid default, where the per-file action menus live).
beforeEach(() => setActivePinia(createPinia()))

// #174 — explicit per-document cover. The API modules are DEPENDENCIES (mocked); the unit under test
// is DocumentViewContent's wiring: the per-file "set as cover" / "remove as cover" actions call
// setDocumentCover / clearDocumentCover on THIS document and invalidate both the document detail and
// the documents list (the list thumbnails read the served file_id, which the cover change moves).
const setCoverMock = vi.fn(() => Promise.resolve({ data: { status: 'ok' } }))
const clearCoverMock = vi.fn(() => Promise.resolve({ data: { status: 'ok' } }))
const invalidateMock = vi.fn()

vi.mock('../../api/file', () => ({
  getFileUrl: (id: string) => `/api/file/${id}/data`,
  setRotation: vi.fn(),
  deleteFile: vi.fn(),
  renameFile: vi.fn(),
  uploadFile: vi.fn(),
  reorderFiles: vi.fn(),
}))
vi.mock('../../api/document', () => ({
  listDocuments: vi.fn(),
  updateDocument: vi.fn(),
  setDocumentCover: (...a: unknown[]) => setCoverMock(...a),
  clearDocumentCover: (...a: unknown[]) => clearCoverMock(...a),
  buildRelationsParams: vi.fn(() => new URLSearchParams()),
}))
vi.mock('vue-i18n', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (k: string) => k }),
}))
vi.mock('../../components/PdfViewer.vue', () => ({
  default: { name: 'PdfViewer', render: () => null },
}))
vi.mock('@tanstack/vue-query', () => ({
  useQueryClient: () => ({ invalidateQueries: invalidateMock }),
}))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))
vi.stubGlobal('URL', {
  ...URL,
  createObjectURL: () => 'blob:test',
  revokeObjectURL: () => {},
})
vi.mock('../../composables/usePreviewQueue', () => ({
  usePreviewQueue: () => ({
    enqueue: () => Promise.resolve(new Blob(['x'])),
    cancel: () => {},
    reprioritize: () => {},
  }),
}))

import DocumentViewContent from './DocumentViewContent.vue'

function makeDoc(coverId: string | null): DocumentDetail {
  return {
    id: 'doc-1',
    title: 'Doc',
    writable: true,
    file_id_cover: coverId,
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
        FileExtraActions: true,
        Button: {
          props: ['icon', 'ariaLabel', 'disabled'],
          template: '<button :aria-label="ariaLabel" :disabled="disabled"></button>',
        },
      },
      directives: { tooltip: {} },
    },
  })
}

describe('DocumentViewContent explicit cover', () => {
  beforeEach(() => {
    setCoverMock.mockClear().mockResolvedValue({ data: { status: 'ok' } })
    clearCoverMock.mockClear().mockResolvedValue({ data: { status: 'ok' } })
    invalidateMock.mockClear()
  })

  it('sets a non-cover file as the cover and invalidates the document AND the documents list', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc(null))
    const wrapper = mountContent(docRef)
    await flushPromises()

    const cards = wrapper.findAll('.file-preview-card')
    expect(cards.length).toBe(2)
    expect(cards[0].find('[aria-label="ui.set_as_cover"]').exists()).toBe(true)
    expect(cards[1].find('[aria-label="ui.set_as_cover"]').exists()).toBe(true)

    await cards[1].get('[aria-label="ui.set_as_cover"]').trigger('click')
    await flushPromises()

    expect(setCoverMock).toHaveBeenCalledTimes(1)
    expect(setCoverMock).toHaveBeenLastCalledWith('doc-1', 'img-b')
    const keys = invalidateMock.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey)
    expect(keys).toContainEqual(['document', 'doc-1'])
    expect(keys).toContainEqual(['documents'])
  })

  it('offers remove-as-cover on the current cover file and clears it', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc('img-a'))
    const wrapper = mountContent(docRef)
    await flushPromises()

    const cards = wrapper.findAll('.file-preview-card')
    expect(cards[0].find('[aria-label="ui.remove_as_cover"]').exists()).toBe(true)
    expect(cards[0].find('[aria-label="ui.set_as_cover"]').exists()).toBe(false)
    expect(cards[1].find('[aria-label="ui.set_as_cover"]').exists()).toBe(true)

    await cards[0].get('[aria-label="ui.remove_as_cover"]').trigger('click')
    await flushPromises()

    expect(clearCoverMock).toHaveBeenCalledTimes(1)
    expect(clearCoverMock).toHaveBeenLastCalledWith('doc-1')
    const keys = invalidateMock.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey)
    expect(keys).toContainEqual(['document', 'doc-1'])
    expect(keys).toContainEqual(['documents'])
  })

  it('read-only document: no cover action is offered', async () => {
    const docRef = ref<DocumentDetail | undefined>({
      ...makeDoc(null),
      writable: false,
    } as DocumentDetail)
    const wrapper = mountContent(docRef)
    await flushPromises()

    const labels = wrapper.findAll('button').map((b) => b.attributes('aria-label'))
    expect(labels).not.toContain('ui.set_as_cover')
    expect(labels).not.toContain('ui.remove_as_cover')
  })
})
