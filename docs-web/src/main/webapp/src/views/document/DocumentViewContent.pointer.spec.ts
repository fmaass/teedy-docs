import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, provide, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { QueryClient, VueQueryPlugin, useQuery } from '@tanstack/vue-query'
import type { DocumentDetail } from '../../api/document'
import { DocumentKey } from './documentKey'

// #199 — the served-file pointer race. `file_id` is written by DocumentUpdatedAsyncListener
// AFTER PUT /file returns, so the single invalidation the upload used to fire could refetch a
// document that still carries `file_id: null` and cache that null as fresh for staleTime. The
// unit under test is the bounded re-invalidation in DocumentViewContent (settleServingPointer).
//
// The race is made DETERMINISTIC by the document endpoint's stub, not by timing: the response
// the FIRST post-upload refetch sees carries `file_id: null`, the next one carries the pointer.
// Against a one-shot invalidation the cached pointer therefore stays null forever, which is the
// red-first signal; the stub never depends on a real listener or a real clock.

beforeEach(() => setActivePinia(createPinia()))

vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))
const uploadFileMock = vi.hoisted(() => vi.fn())
// DocumentViewContent syncs the preview to a `?file=` deep link (#192), so it now resolves
// a route and a router. A static stand-in is enough here — this spec drives neither.
vi.mock('vue-router', () => ({
  useRoute: () => ({ name: 'document-view-content', params: { id: 'doc-1' }, query: {} }),
  useRouter: () => ({ replace: vi.fn() }),
}))
vi.mock('../../api/file', () => ({
  buildFileLink: (d: string, fid: string) => `https://app/#/document/view/${d}/content?file=${fid}`,
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

const DOC_ID = 'doc-ptr'

function makeDoc(overrides: Partial<DocumentDetail>): DocumentDetail {
  return {
    id: DOC_ID,
    title: 'Pointer Doc',
    writable: true,
    file_id: null,
    file_count: 0,
    files: [],
    relations: [],
    tags: [],
    metadata: [],
    ...overrides,
  } as unknown as DocumentDetail
}

/**
 * Mounts the view exactly as production wires it: the injected document IS the
 * ['document', id] query's data, so an invalidation really refetches and really updates
 * the cache the fix reads back. `getDocument` is the only stubbed boundary.
 */
function mountView(responses: DocumentDetail[]) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
  })
  const getDocument = vi.fn(() => {
    // The last response repeats once the scripted sequence is exhausted.
    const next = responses.length > 1 ? responses.shift()! : responses[0]
    return Promise.resolve(next)
  })
  // The QUERY OWNER is the host (production: DocumentView owns it, the tab child renders
  // inside). `childMounted` unmounts only the child — exactly what a tab switch does —
  // which leaves the query ACTIVE, so an invalidation from a leaked settle would still
  // refetch. Unmounting the whole tree instead would make the query inactive and
  // `refetchType: 'active'` alone would suppress the refetch, hiding the bug under test.
  const childMounted = ref(true)
  const Host = defineComponent({
    name: 'PointerHost',
    setup() {
      const { data } = useQuery({
        queryKey: ['document', DOC_ID],
        queryFn: getDocument,
      })
      provide(DocumentKey, data)
      return () => (childMounted.value ? h(DocumentViewContent) : h('div', { class: 'child-gone' }))
    },
  })
  const wrapper = mount(Host, {
    global: {
      plugins: [PrimeVue, [VueQueryPlugin, { queryClient }]],
      stubs: {
        PdfViewer: true,
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
  return { wrapper, queryClient, getDocument, childMounted }
}

async function triggerUpload(wrapper: ReturnType<typeof mountView>['wrapper']) {
  const file = new File(['bytes'], 'scan.png', { type: 'image/png' })
  await wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('uploader', { files: [file] })
  await flushPromises()
}

/** Run every pending timer-and-promise chain the bounded retry may be waiting on. */
async function settle() {
  await vi.advanceTimersByTimeAsync(3000)
  await flushPromises()
}

/**
 * Advance exactly one retry interval. The next delay is armed only AFTER the refetch this
 * step triggers resolves (at ~500 ms of virtual time), so it falls outside this window —
 * one call to `step()` therefore releases exactly one retry, which is what makes "one
 * refetch per step" an assertable property rather than a race.
 */
async function step() {
  await vi.advanceTimersByTimeAsync(600)
  await flushPromises()
}

describe('DocumentViewContent — post-upload served-pointer reconciliation (#199)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    uploadFileMock.mockReset().mockResolvedValue({ data: { status: 'ok', id: 'file-1', size: 5 } })
  })
  afterEach(() => vi.useRealTimers())

  it('re-invalidates until the served pointer appears when the first refetch still has none', async () => {
    // 1: cold load (no files yet) · 2: post-upload refetch, listener has NOT written the
    // pointer · 3: the listener has caught up.
    const { wrapper, queryClient, getDocument } = mountView([
      makeDoc({ file_count: 0, file_id: null }),
      makeDoc({ file_count: 1, file_id: null }),
      makeDoc({ file_count: 1, file_id: 'file-1' }),
    ])
    await settle()
    expect(getDocument).toHaveBeenCalledTimes(1)

    await triggerUpload(wrapper)
    await settle()

    // A one-shot invalidation stops at the null-pointer response and caches it as fresh.
    const cached = queryClient.getQueryData<DocumentDetail>(['document', DOC_ID])
    expect(cached?.file_id, 'served pointer reconciled after the upload').toBe('file-1')
    expect(getDocument).toHaveBeenCalledTimes(3)
  })

  it('stops at the first refetch when the pointer is already there (no extra requests)', async () => {
    const { wrapper, queryClient, getDocument } = mountView([
      makeDoc({ file_count: 0, file_id: null }),
      makeDoc({ file_count: 1, file_id: 'file-1' }),
    ])
    await settle()

    await triggerUpload(wrapper)
    await settle()

    expect(queryClient.getQueryData<DocumentDetail>(['document', DOC_ID])?.file_id).toBe('file-1')
    expect(getDocument).toHaveBeenCalledTimes(2)
  })

  it('does NOT loop when the upload failed outright (no files → a null pointer is the truth)', async () => {
    uploadFileMock.mockReset().mockRejectedValue({ response: { status: 500 } })
    const { wrapper, getDocument } = mountView([makeDoc({ file_count: 0, file_id: null })])
    await settle()

    await triggerUpload(wrapper)
    await settle()

    // Exactly one refetch: the unconditional post-batch invalidation, and no retry on top.
    expect(getDocument).toHaveBeenCalledTimes(2)
  })

  it('gives up after a bounded number of attempts rather than polling forever', async () => {
    const { wrapper, getDocument } = mountView([
      makeDoc({ file_count: 0, file_id: null }),
      makeDoc({ file_count: 1, file_id: null }),
    ])
    await settle()

    await triggerUpload(wrapper)
    await settle()
    await settle()

    // 1 cold load + 3 bounded attempts, then it stops even though the pointer never lands.
    expect(getDocument).toHaveBeenCalledTimes(4)
  })

  // A second batch is reachable while a settle is still retrying: `runUploads` fires once
  // per batch (the conflict path adds a second) and a fresh drop is accepted as soon as
  // `busy` clears. Two live settlers would invalidate the same exact key concurrently and
  // cancel each other's refetch, so the newer schedule must REPLACE the older one.
  it('a second upload replaces the in-flight settle instead of running two in parallel', async () => {
    const { wrapper, getDocument } = mountView([
      makeDoc({ file_count: 0, file_id: null }),
      makeDoc({ file_count: 1, file_id: null }),
    ])
    await settle()
    expect(getDocument).toHaveBeenCalledTimes(1)

    // Two waves back to back, neither given time to retry: each does its first
    // invalidation immediately, and the second supersedes the first.
    await triggerUpload(wrapper)
    await triggerUpload(wrapper)
    expect(getDocument, 'one immediate invalidation per upload wave').toHaveBeenCalledTimes(3)

    // From here only ONE settle may still be alive, so each interval releases exactly one
    // refetch. Two parallel settlers would double every step.
    await step()
    expect(getDocument, 'retry 2 of the surviving wave only').toHaveBeenCalledTimes(4)
    await step()
    expect(getDocument, 'retry 3 of the surviving wave only').toHaveBeenCalledTimes(5)

    // ...and then nothing: the superseded wave has no remaining steps of its own.
    await settle()
    expect(getDocument).toHaveBeenCalledTimes(5)
  })

  // Unmounting only the CHILD (a tab switch) leaves the query active, so a settle that
  // outlived it would keep refetching a document the user has navigated away from.
  it('unmounting mid-retry stops every further invalidation', async () => {
    const { wrapper, getDocument, childMounted } = mountView([
      makeDoc({ file_count: 0, file_id: null }),
      makeDoc({ file_count: 1, file_id: null }),
    ])
    await settle()

    await triggerUpload(wrapper)
    const afterFirstAttempt = getDocument.mock.calls.length
    expect(afterFirstAttempt).toBe(2)

    childMounted.value = false
    await flushPromises()
    expect(wrapper.find('.child-gone').exists(), 'the child really unmounted').toBe(true)

    // Long past every remaining retry interval: not one more refetch.
    await settle()
    await settle()
    expect(getDocument).toHaveBeenCalledTimes(afterFirstAttempt)
  })
})
