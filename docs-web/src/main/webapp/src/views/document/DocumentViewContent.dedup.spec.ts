import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import type { DocumentDetail } from '../../api/document'
import { DocumentKey } from './documentKey'

// #119 content-hash duplicate detection — the CLIENT surface: runUploads reads duplicateKind/duplicateOfId
// off the PUT /file response and shows a single NON-BLOCKING informational toast pointing the user at the
// existing "upload new version" action. It is purely advisory (no server-side action) and never fires when
// the response omits the hint (feature off).

beforeEach(() => setActivePinia(createPinia()))

const toastAdd = vi.hoisted(() => vi.fn())
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: toastAdd }) }))

const uploadFileMock = vi.hoisted(() => vi.fn())
vi.mock('../../api/file', () => ({
  getFileUrl: (id: string) => `/api/file/${id}/data`,
  setRotation: vi.fn(),
  deleteFile: vi.fn(),
  renameFile: vi.fn(),
  uploadFile: (...a: unknown[]) => uploadFileMock(...a),
}))
vi.mock('vue-i18n', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (k: string) => k }),
}))
vi.mock('../../components/PdfViewer.vue', () => ({
  default: { name: 'PdfViewer', render: () => null },
}))
const invalidateMock = vi.fn()
vi.mock('@tanstack/vue-query', () => ({
  useQueryClient: () => ({ invalidateQueries: invalidateMock }),
}))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))
vi.mock('../../composables/usePreviewQueue', () => ({
  usePreviewQueue: () => ({
    enqueue: () => Promise.resolve(null),
    cancel: () => {},
    reprioritize: () => {},
  }),
}))

import DocumentViewContent from './DocumentViewContent.vue'

function makeDoc(): DocumentDetail {
  return {
    id: 'doc-1',
    title: 'Doc',
    writable: true,
    files: [{ id: 'existing-1', name: 'original.txt', mimetype: 'text/plain', size: 10 }],
  } as unknown as DocumentDetail
}

function mountView() {
  const docRef = ref<DocumentDetail | undefined>(makeDoc())
  return mount(DocumentViewContent, {
    global: {
      plugins: [PrimeVue],
      provide: { [DocumentKey as symbol]: docRef },
      stubs: {
        PdfViewer: true,
        EmptyState: true,
        FileVersionsDialog: true,
        CameraCaptureButton: true,
        UploadProgressList: true,
        // A real named stub so the test can emit the @uploader event the view binds handleUpload to;
        // clear() is a no-op the finally path calls on the FileUpload template ref.
        FileUpload: { name: 'FileUpload', template: '<div />', methods: { clear() {} } },
      },
      directives: { tooltip: {} },
    },
  })
}

async function triggerUpload(wrapper: ReturnType<typeof mountView>, fileName: string) {
  const file = new File(['identical bytes'], fileName, { type: 'text/plain' })
  await wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('uploader', { files: [file] })
  await flushPromises()
}

describe('DocumentViewContent — content-duplicate upload hint (#119)', () => {
  beforeEach(() => {
    toastAdd.mockReset()
    uploadFileMock.mockReset()
  })

  it('shows a non-blocking info toast when the upload response carries a content-duplicate hint', async () => {
    uploadFileMock.mockResolvedValue({
      data: { status: 'ok', id: 'new-1', size: 15, duplicateKind: 'content', duplicateOfId: 'existing-1' },
    })
    const wrapper = mountView()
    await triggerUpload(wrapper, 'renamed.txt')

    const info = toastAdd.mock.calls.map((c) => c[0]).find((a) => a.severity === 'info')
    expect(info).toBeTruthy()
    expect(info.summary).toBe('ui.duplicate_content_hint')
  })

  it('shows NO duplicate hint toast when the response omits duplicateKind (feature off)', async () => {
    uploadFileMock.mockResolvedValue({ data: { status: 'ok', id: 'new-1', size: 15 } })
    const wrapper = mountView()
    await triggerUpload(wrapper, 'renamed.txt')

    const info = toastAdd.mock.calls.map((c) => c[0]).find((a) => a.severity === 'info')
    expect(info).toBeFalsy()
    // the ordinary success toast still fires
    expect(toastAdd.mock.calls.some((c) => c[0].severity === 'success')).toBe(true)
  })
})
