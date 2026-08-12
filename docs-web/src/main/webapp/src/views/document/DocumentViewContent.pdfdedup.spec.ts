import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import type { DocumentDetail } from '../../api/document'
import { DocumentKey } from './documentKey'

// #247 — the document view can mount TWO PdfViewer instances for the SAME pdf file at once: the
// file-grid tile (always rendered for a PDF file) and the preview dialog (opened here via the
// `?file=` deep link). Each viewer loads the body through getPdfBytes, whose module-level cache
// shares ONE fetch between concurrent callers of the same src. Before the fix each viewer ran its
// own pdf.js {url} GET of the same large body and the two raced the disk cache (ERR_CACHE_WRITE_FAILURE).
//
// The measurable invariant is observable ONLY at this app-level boundary now: the fix moved the
// double-fetch out of pdf.js's internals and onto the deduped getPdfBytes → fetch boundary. So the
// PdfViewer is REAL here (not stubbed), pdf.js's getDocument is mocked at its module boundary, and
// the body fetch (global.fetch, which getPdfBytes calls) is mocked and COUNTED. Two live viewers of
// one file must produce EXACTLY ONE body fetch; remove the dedup and this fetch fires twice.

beforeEach(() => setActivePinia(createPinia()))

vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))

// Reactive router stand-in (mirrors DocumentViewContent.deeplink.spec): `?file=f1` is set before
// mount so the {immediate:true} hydrate watcher auto-opens the preview on f1 once files resolve.
interface RouteStub {
  name: string
  params: Record<string, string>
  query: Record<string, string | string[] | undefined>
}
const router = vi.hoisted(() => ({
  route: { name: 'document-view-content', params: { id: 'doc-1' }, query: {} } as RouteStub,
  replace: vi.fn(),
}))
vi.mock('vue-router', async () => {
  const { reactive } = await vi.importActual<typeof import('vue')>('vue')
  router.route = reactive<RouteStub>({
    name: 'document-view-content',
    params: { id: 'doc-1' },
    query: {},
  })
  return { useRoute: () => router.route, useRouter: () => ({ replace: router.replace }) }
})

// getFileUrl is the SAME for both viewers — the byte-identical src that must be fetched once.
vi.mock('../../api/file', () => ({
  getFileUrl: (id: string) => `/api/file/${id}/data`,
  buildFileLink: (d: string, f: string) => `https://app/#/document/view/${d}/content?file=${f}`,
  setRotation: vi.fn(),
  deleteFile: vi.fn(),
  renameFile: vi.fn(),
  uploadFile: vi.fn(),
  reorderFiles: vi.fn(),
  moveFile: vi.fn(),
  getFileList: vi.fn(() => Promise.resolve([])),
}))
vi.mock('vue-i18n', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (k: string) => k }),
}))

// pdf.js is the DEPENDENCY, mocked at the module boundary — a resolved document with one page so
// each real PdfViewer completes its load without a worker. It records nothing; the fetch mock is
// what carries the invariant. (NB: PdfViewer.vue is deliberately NOT stubbed here.)
function makePage() {
  return {
    rotate: 0,
    getViewport: (opts: { scale: number; rotation?: number }) => ({
      width: 200 * opts.scale,
      height: 300 * opts.scale,
    }),
    render: () => ({ promise: Promise.resolve(), cancel: () => {} }),
  }
}
vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: {},
  getDocument: () => ({
    promise: Promise.resolve({ numPages: 1, getPage: async () => makePage() }),
    destroy: vi.fn(),
  }),
}))

vi.mock('@tanstack/vue-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn(), getQueryData: () => undefined }),
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
import FilePreviewDialog from '../../components/FilePreviewDialog.vue'
// The REAL viewer. Both mount sites load it via defineAsyncComponent; that async boundary does
// not resolve under vitest's module loader, so we swap it for the real SYNCHRONOUS component by
// its `PdfViewer` stub key (the same key DocumentViewContent.deeplink.spec stubs out, which
// replaces BOTH boundaries at once). This is the REAL component's real load path — getPdfBytes →
// fetch — not a behavioural stub; only the async wrapper is bypassed.
import PdfViewer from '../../components/PdfViewer.vue'

// One PDF file, so the grid renders exactly one PDF tile (one viewer) and the deep link opens the
// preview on it (the second viewer) — both bound to getFileUrl('f1').
function makeDoc(): DocumentDetail {
  return {
    id: 'doc-1',
    title: 'Doc',
    writable: true,
    relations: [],
    metadata: [],
    files: [
      { id: 'f1', name: 'report.pdf', mimetype: 'application/pdf', size: 6700000, create_date: 1, creator: 'admin', version: 0 },
    ],
  } as unknown as DocumentDetail
}

function mountView() {
  const docRef = ref<DocumentDetail | undefined>(makeDoc())
  return mount(DocumentViewContent, {
    global: {
      plugins: [PrimeVue],
      provide: { [DocumentKey as symbol]: docRef },
      stubs: {
        // PdfViewer is the REAL component (swapped in for its async boundary, see the import
        // note) and FilePreviewDialog is real too — they are the reproduction. Everything else is
        // stubbed exactly as the sibling DocumentViewContent specs do.
        PdfViewer,
        EmptyState: true,
        FileVersionsDialog: true,
        CameraCaptureButton: true,
        UploadProgressList: true,
        FileUpload: { name: 'FileUpload', template: '<div />', methods: { clear() {} } },
        RouterLink: { template: '<a><slot /></a>' },
      },
      directives: { tooltip: {} },
    },
  })
}

describe('DocumentViewContent — two PdfViewers share one body fetch (#247)', () => {
  beforeEach(() => {
    router.replace.mockReset()
    router.route.query = { file: 'f1' }
  })

  it('fetches the PDF body EXACTLY ONCE with both the grid tile and the preview open', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      arrayBuffer: async () => new ArrayBuffer(8),
    }))
    global.fetch = fetchMock as unknown as typeof fetch

    const wrapper = mountView()
    // Drain: document resolves → deep link opens the preview → both viewers run onMounted →
    // getPdfBytes → fetch.
    await flushPromises()

    // Both consumers must actually be live, or the single-fetch assertion would pass vacuously:
    // the preview is open AND two PdfViewer instances are mounted (the grid tile + the dialog).
    expect(wrapper.findComponent(FilePreviewDialog).props('visible')).toBe(true)
    expect(wrapper.findAllComponents({ name: 'PdfViewer' }).length).toBe(2)

    // The invariant: two viewers of one file → ONE body fetch. Remove getPdfBytes' dedup and this
    // is 2 (each viewer fetches its own copy of the body).
    const url = '/api/file/f1/data'
    const bodyFetches = fetchMock.mock.calls.filter((c) => c[0] === url)
    expect(bodyFetches.length).toBe(1)
    expect(fetchMock).toHaveBeenCalledWith(url, { credentials: 'include' })
  })
})
