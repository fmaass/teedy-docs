import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { DocumentKey } from './documentKey'
import type { DocumentDetail } from '../../api/document'
import { POLL_INTERVAL_MS } from '../../utils/fileProcessing'

// #176 — the processing poll on the document-view preview surface. A freshly uploaded
// image's web raster is generated asynchronously; until it exists the data endpoint answers
// a MISSING raster with HTTP 200 + a bundled placeholder, so the first preview fetch caches
// the placeholder blob. While any file is processing the view polls /file/list, and when a
// file flips processing -> done it RE-ENQUEUES that file so the real raster replaces the
// placeholder (revoking the stale object URL). The preview queue and the file API are
// dependencies (mocked); the unit under test is the poll + re-enqueue lifecycle.

const enqueueSpy = vi.hoisted(() => vi.fn())
const getFileListSpy = vi.hoisted(() => vi.fn())

vi.mock('../../composables/usePreviewQueue', () => ({
  usePreviewQueue: () => ({
    enqueue: (...a: unknown[]) => enqueueSpy(...a),
    cancel: () => {},
    reprioritize: () => {},
  }),
}))
vi.mock('../../api/file', () => ({
  getFileUrl: (id: string) => `/api/file/${id}/data`,
  getFileList: (...a: unknown[]) => getFileListSpy(...a),
  setRotation: vi.fn(() => Promise.resolve({ data: { status: 'ok', rotation: 0 } })),
  deleteFile: vi.fn(),
  renameFile: vi.fn(),
  uploadFile: vi.fn(),
  reorderFiles: vi.fn(),
}))
vi.mock('../../components/PdfViewer.vue', () => ({
  default: { name: 'PdfViewer', render: () => null },
}))
vi.mock('vue-i18n', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (k: string) => k }),
}))
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))
vi.mock('@tanstack/vue-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}))

// Distinct, ordered object URLs (blob:1, blob:2, …) so a re-enqueue's revoke can be asserted
// against the exact placeholder URL it replaces. Stubbed once for the file — jsdom has no
// URL.createObjectURL, and restoring it mid-run would break late blob microtasks.
let objectUrlCounter = 0
const createObjectURLSpy = vi.fn(() => `blob:${++objectUrlCounter}`)
const revokeObjectURLSpy = vi.fn()
vi.stubGlobal('URL', {
  ...URL,
  createObjectURL: (...a: unknown[]) => createObjectURLSpy(...a),
  revokeObjectURL: (...a: unknown[]) => revokeObjectURLSpy(...a),
})

import DocumentViewContent from './DocumentViewContent.vue'

interface DocFile {
  id: string
  name: string
  mimetype: string
  size: number
  processing?: boolean
}

function makeDoc(files: DocFile[], id = 'doc-1'): DocumentDetail {
  return {
    id,
    title: 'Doc',
    language: 'eng',
    writable: true,
    description: '',
    tags: [],
    relations: [],
    metadata: [],
    files,
  } as unknown as DocumentDetail
}

function mountView(doc: DocumentDetail) {
  const docRef = ref(doc)
  const wrapper = mount(DocumentViewContent, {
    global: {
      plugins: [PrimeVue],
      provide: { [DocumentKey as symbol]: docRef },
      stubs: {
        FileUpload: true,
        CameraCaptureButton: true,
        UploadProgressList: true,
        FileVersionsDialog: true,
        FileListTable: true,
        FileActionMenu: true,
        FileExtraActions: true,
        FileConflictDialog: true,
        FilePreviewDialog: true,
        PdfViewer: true,
        EmptyState: true,
        RouterLink: { template: '<a><slot /></a>' },
      },
      directives: { tooltip: {} },
    },
  })
  return { wrapper, docRef }
}

/** File ids passed to enqueue at the given priority (0 = the re-enqueue foreground priority). */
function enqueuedAtPriority(priority: number): string[] {
  return enqueueSpy.mock.calls.filter((c) => c[2] === priority).map((c) => c[0] as string)
}

describe('DocumentViewContent — processing poll (#176)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    enqueueSpy.mockReset()
    enqueueSpy.mockImplementation(() => Promise.resolve(new Blob(['raster'])))
    getFileListSpy.mockReset()
    objectUrlCounter = 0
    createObjectURLSpy.mockClear()
    revokeObjectURLSpy.mockClear()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('polls while a file is processing and re-enqueues EXACTLY the image that flipped to done, then stops', async () => {
    // img-1: image, still processing (its first fetch got the placeholder) -> must re-enqueue on flip.
    // img-2: image, already settled -> must NOT re-enqueue (no flip).
    // note-1: non-image, processing -> flips but carries no queued raster preview -> never re-enqueued.
    const doc = makeDoc([
      { id: 'img-1', name: 'a.jpg', mimetype: 'image/jpeg', size: 1, processing: true },
      { id: 'img-2', name: 'b.jpg', mimetype: 'image/jpeg', size: 1, processing: false },
      { id: 'note-1', name: 'c.txt', mimetype: 'text/plain', size: 1, processing: true },
    ])
    const { wrapper } = mountView(doc)
    await flushPromises()

    // Initial pass enqueues the two images at background priority (1); the poll is armed
    // because something is processing.
    expect(enqueuedAtPriority(1).sort()).toEqual(['img-1', 'img-2'])
    expect(enqueuedAtPriority(0)).toEqual([])
    expect(vi.getTimerCount()).toBe(1)

    // The next poll reports every file finished.
    getFileListSpy.mockResolvedValue([
      { id: 'img-1', processing: false, name: 'a.jpg', mimetype: 'image/jpeg', version: 0, document_id: 'doc-1', create_date: 0, size: 1 },
      { id: 'img-2', processing: false, name: 'b.jpg', mimetype: 'image/jpeg', version: 0, document_id: 'doc-1', create_date: 0, size: 1 },
      { id: 'note-1', processing: false, name: 'c.txt', mimetype: 'text/plain', version: 0, document_id: 'doc-1', create_date: 0, size: 1 },
    ])

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    await flushPromises()

    expect(getFileListSpy).toHaveBeenCalledWith('doc-1')
    // Only the image that flipped processing->done is re-enqueued, at foreground priority.
    expect(enqueuedAtPriority(0)).toEqual(['img-1'])
    // The stale placeholder blob for img-1 (blob:1, the first createObjectURL) is revoked before replacement.
    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:1')
    // Nothing left processing -> the loop stops with no residual timer.
    expect(vi.getTimerCount()).toBe(0)
    wrapper.unmount()
  })

  it('keeps polling across an interval where nothing has flipped yet (no premature re-enqueue)', async () => {
    const doc = makeDoc([
      { id: 'img-1', name: 'a.jpg', mimetype: 'image/jpeg', size: 1, processing: true },
    ])
    const { wrapper } = mountView(doc)
    await flushPromises()
    expect(vi.getTimerCount()).toBe(1)

    // First poll: still processing.
    getFileListSpy.mockResolvedValueOnce([
      { id: 'img-1', processing: true, name: 'a.jpg', mimetype: 'image/jpeg', version: 0, document_id: 'doc-1', create_date: 0, size: 1 },
    ])
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    await flushPromises()
    expect(getFileListSpy).toHaveBeenCalledTimes(1)
    expect(enqueuedAtPriority(0)).toEqual([]) // no flip -> no re-enqueue
    expect(vi.getTimerCount()).toBe(1) // still polling

    // Second poll: done.
    getFileListSpy.mockResolvedValueOnce([
      { id: 'img-1', processing: false, name: 'a.jpg', mimetype: 'image/jpeg', version: 0, document_id: 'doc-1', create_date: 0, size: 1 },
    ])
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    await flushPromises()
    expect(getFileListSpy).toHaveBeenCalledTimes(2)
    expect(enqueuedAtPriority(0)).toEqual(['img-1'])
    expect(vi.getTimerCount()).toBe(0)
    wrapper.unmount()
  })

  it('does NOT start polling when no file is processing', async () => {
    const doc = makeDoc([
      { id: 'img-1', name: 'a.jpg', mimetype: 'image/jpeg', size: 1, processing: false },
    ])
    const { wrapper } = mountView(doc)
    await flushPromises()
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3)
    expect(getFileListSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('stops polling on unmount — no residual timer and no further poll mid-processing', async () => {
    const doc = makeDoc([
      { id: 'img-1', name: 'a.jpg', mimetype: 'image/jpeg', size: 1, processing: true },
    ])
    const { wrapper } = mountView(doc)
    await flushPromises()
    expect(vi.getTimerCount()).toBe(1)

    // One poll runs while still processing (the loop re-arms).
    getFileListSpy.mockResolvedValue([
      { id: 'img-1', processing: true, name: 'a.jpg', mimetype: 'image/jpeg', version: 0, document_id: 'doc-1', create_date: 0, size: 1 },
    ])
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    await flushPromises()
    expect(getFileListSpy).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(1)

    // Unmounting must dispose the poller.
    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)

    // No further polls after unmount, even though the file was still processing.
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3)
    expect(getFileListSpy).toHaveBeenCalledTimes(1)
  })

  it('cancels the armed poll when navigating to a fully-settled file set — no trailing fetch', async () => {
    // Arm the poll from a processing file, then swap to another document whose files are all
    // settled BEFORE the interval elapses. The armed timer must be cancelled at once, or one
    // stray /file/list poll fires against the settled set (the bounded-polling contract break).
    const { wrapper, docRef } = mountView(
      makeDoc([{ id: 'img-1', name: 'a.jpg', mimetype: 'image/jpeg', size: 1, processing: true }]),
    )
    await flushPromises()
    expect(vi.getTimerCount()).toBe(1)

    docRef.value = makeDoc(
      [{ id: 'img-9', name: 'z.jpg', mimetype: 'image/jpeg', size: 1, processing: false }],
      'doc-2',
    )
    await flushPromises()

    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3)
    expect(getFileListSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
