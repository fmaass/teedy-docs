import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import type { DocumentDetail } from '../../api/document'
import { DocumentKey } from './documentKey'

// #192 — the `?file=<id>` deep link on the document CONTENT route, two-way synced with
// the in-app preview dialog:
//
//   URL  → preview: once the document's files have resolved, a known id opens the preview
//                   on exactly that file. An unknown/stale id opens nothing, clears the
//                   param and warns ONCE — a link to a deleted or moved file degrades, it
//                   never dead-ends on an empty dialog.
//   preview → URL : opening writes `?file=<id>` by REPLACE (the preview is not a history
//                   entry), closing removes it, and every OTHER query key is preserved
//                   surgically ({...route.query}, never a rebuild).
//
// The replace feeds straight back into the route watcher, so every write is guarded by a
// value-equality check against the CURRENT query — without it, open→replace→watch→open is
// a loop. `applyReplace()` mirrors what the real router does, and the replace counts below
// are what would blow up on an unguarded implementation.

beforeEach(() => setActivePinia(createPinia()))

const toastAdd = vi.hoisted(() => vi.fn())
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: toastAdd }) }))

// The router stand-in. `route` must be genuinely REACTIVE — every "the URL changed
// underneath us" test drives it by assignment, and a plain object would make them pass
// vacuously. It is built inside the mock factory (which can await vue) and published on
// this holder for the tests to drive.
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
  // `reactive` has to come from the ACTUAL vue module and can only be pulled in here (the
  // factory is async); the reactive object is stashed on the hoisted holder so the tests
  // can drive it. Always read it through `router.route` — a captured reference goes stale.
  const { reactive } = await vi.importActual<typeof import('vue')>('vue')
  router.route = reactive<RouteStub>({
    name: 'document-view-content',
    params: { id: 'doc-1' },
    query: {},
  })
  return { useRoute: () => router.route, useRouter: () => ({ replace: router.replace }) }
})

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
vi.mock('../../components/PdfViewer.vue', () => ({
  default: { name: 'PdfViewer', render: () => null },
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

const FILES = [
  { id: 'f1', name: 'alpha.txt', mimetype: 'text/plain', size: 10, create_date: 1, creator: 'admin', version: 0 },
  { id: 'f2', name: 'beta.txt', mimetype: 'text/plain', size: 20, create_date: 2, creator: 'admin', version: 0 },
]

function makeDoc(files: unknown[] = FILES): DocumentDetail {
  return {
    id: 'doc-1',
    title: 'Doc',
    writable: true,
    relations: [],
    metadata: [],
    files,
  } as unknown as DocumentDetail
}

// `null` models the COLD load: the injected ref is empty until the document-detail query
// settles, and the deep link may only act once it does. It must NOT be spelled `undefined` —
// that is exactly what a defaulted parameter swallows, and the cold-load test would then
// silently run against a fully resolved document.
function mountView(doc: DocumentDetail | null = makeDoc()) {
  const docRef = ref<DocumentDetail | undefined>(doc ?? undefined)
  const wrapper = mount(DocumentViewContent, {
    global: {
      plugins: [PrimeVue],
      provide: { [DocumentKey as symbol]: docRef },
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
  return { wrapper, docRef }
}

function preview(wrapper: ReturnType<typeof mountView>['wrapper']) {
  return wrapper.findComponent(FilePreviewDialog)
}

function replaceCalls() {
  return router.replace.mock.calls as [{ query: Record<string, string> }][]
}

// The real router applies what was replaced. Mirroring it is what turns an unguarded
// implementation into an observable second write instead of a silent pass.
function applyReplace() {
  const last = replaceCalls().at(-1)?.[0]
  if (last?.query) router.route.query = { ...last.query }
}

describe('DocumentViewContent — file deep link (#192)', () => {
  beforeEach(() => {
    toastAdd.mockReset()
    router.replace.mockReset()
    router.route.query = {}
  })

  it('a cold load with a known ?file= opens the preview on exactly that file', async () => {
    router.route.query = { file: 'f2' }
    const { wrapper } = mountView()
    await flushPromises()

    expect(preview(wrapper).props('visible')).toBe(true)
    expect(preview(wrapper).props('file')).toMatchObject({ id: 'f2', name: 'beta.txt' })
    // The param already says what the app now shows — hydration must not rewrite it.
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('waits for the files to resolve before acting on the param (cold load)', async () => {
    router.route.query = { file: 'f1' }
    const { wrapper, docRef } = mountView(null)
    await flushPromises()
    // An unresolved document is NOT a missing file: the view renders nothing at all yet,
    // and the param must be left exactly as found — no clear, no toast.
    expect(preview(wrapper).exists()).toBe(false)
    expect(router.replace).not.toHaveBeenCalled()
    expect(toastAdd).not.toHaveBeenCalled()

    docRef.value = makeDoc()
    await flushPromises()
    expect(preview(wrapper).props('visible')).toBe(true)
    expect(preview(wrapper).props('file')).toMatchObject({ id: 'f1' })
  })

  it('an unknown/stale id opens nothing, clears the param surgically and warns exactly once', async () => {
    router.route.query = { file: 'gone', tab: 'x' }
    const { wrapper, docRef } = mountView()
    await flushPromises()

    expect(preview(wrapper).props('visible')).toBe(false)
    expect(router.replace).toHaveBeenCalledTimes(1)
    // Only `file` is dropped; every other key survives.
    expect(replaceCalls()[0][0].query).toEqual({ tab: 'x' })
    expect(toastAdd).toHaveBeenCalledTimes(1)
    expect(toastAdd.mock.calls[0][0]).toMatchObject({
      severity: 'warn',
      summary: 'ui.file_view.link_not_found',
    })

    // A refetch re-runs the resolution while the (async) replace is still in flight — the
    // same dead id must not produce a second toast, NOR a second replace. The clearing
    // write is already pending; re-issuing it on every refetch churns the router for no
    // change in outcome. The file SET has to change for the resolution to re-run at all —
    // re-seeding the same ids leaves the watch key identical and would prove nothing.
    docRef.value = makeDoc([
      ...FILES,
      { id: 'f3', name: 'gamma.txt', mimetype: 'text/plain', size: 30, create_date: 3, creator: 'admin', version: 0 },
    ])
    await flushPromises()
    expect(toastAdd).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledTimes(1)
  })

  it('an unknown id closes whichever preview is open, not only a preview of that id', async () => {
    // The URL is authoritative. A link that resolves to nothing must not leave a DIFFERENT
    // file's preview standing: the user would be looking at file A while the address bar
    // claims (and then disclaims) file B.
    router.route.query = { file: 'f1' }
    const { wrapper } = mountView()
    await flushPromises()
    expect(preview(wrapper).props('file')).toMatchObject({ id: 'f1' })

    router.route.query = { file: 'gone' }
    await flushPromises()

    expect(preview(wrapper).props('visible')).toBe(false)
    expect(toastAdd).toHaveBeenCalledTimes(1)
    expect(toastAdd.mock.calls[0][0]).toMatchObject({
      severity: 'warn',
      summary: 'ui.file_view.link_not_found',
    })
    // Closing the stale preview makes the preview watcher ask for the very same clearing
    // write the missing-file branch already issued — exactly one replace must reach the
    // router.
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(replaceCalls()[0][0].query).toEqual({})
  })

  it('a non-scalar (array) param is inactive and is canonicalized out of the URL', async () => {
    router.route.query = { file: ['f1', 'f2'] }
    const { wrapper } = mountView()
    await flushPromises()
    expect(preview(wrapper).props('visible')).toBe(false)
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(replaceCalls()[0][0].query).toEqual({})
    // A malformed param is not a dead link — it gets no "file not found" warning.
    expect(toastAdd).not.toHaveBeenCalled()
  })

  it('opening the preview writes ?file= by replace, preserving the rest of the query', async () => {
    router.route.query = { q: 'kw' }
    const { wrapper } = mountView()
    await flushPromises()
    expect(router.replace).not.toHaveBeenCalled()

    // The real user path: the generic grid tile's open button (first tile = f1).
    await wrapper.findAll('.generic-open')[0].trigger('click')
    await flushPromises()

    expect(preview(wrapper).props('visible')).toBe(true)
    expect(preview(wrapper).props('file')).toMatchObject({ id: 'f1' })
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(replaceCalls()[0][0].query).toEqual({ q: 'kw', file: 'f1' })
    // Feeding the replace back in must NOT trigger a second write (the loop guard).
    applyReplace()
    await flushPromises()
    expect(router.replace).toHaveBeenCalledTimes(1)
  })

  it('closing the preview removes ?file= and leaves the other keys alone', async () => {
    router.route.query = { q: 'kw', file: 'f1' }
    const { wrapper } = mountView()
    await flushPromises()
    expect(preview(wrapper).props('visible')).toBe(true)

    preview(wrapper).vm.$emit('update:visible', false)
    await flushPromises()

    expect(preview(wrapper).props('visible')).toBe(false)
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(replaceCalls()[0][0].query).toEqual({ q: 'kw' })
    applyReplace()
    await flushPromises()
    expect(router.replace).toHaveBeenCalledTimes(1)
  })

  it('a refetch landing before the replace does NOT close a preview the user just opened', async () => {
    // The replace is asynchronous, so there is a window in which the preview is open and
    // the URL does not say so yet. A document refetch arriving in that window re-runs the
    // resolution: it must not read the absent param as "the link was taken away" and close
    // the dialog. Modelled by NOT applying the replace before the refetch.
    const { wrapper, docRef } = mountView()
    await flushPromises()

    await wrapper.findAll('.generic-open')[0].trigger('click')
    await flushPromises()
    expect(preview(wrapper).props('visible')).toBe(true)
    expect(router.replace).toHaveBeenCalledTimes(1)

    // The file set changes (an upload landed) while `?file=` is still not in the route.
    docRef.value = makeDoc([
      ...FILES,
      { id: 'f3', name: 'gamma.txt', mimetype: 'text/plain', size: 30, create_date: 3, creator: 'admin', version: 0 },
    ])
    await flushPromises()

    expect(preview(wrapper).props('visible')).toBe(true)
    expect(preview(wrapper).props('file')).toMatchObject({ id: 'f1' })
  })

  it('the param survives while the preview stays open across a document refetch', async () => {
    router.route.query = { file: 'f1' }
    const { wrapper, docRef } = mountView()
    await flushPromises()
    expect(preview(wrapper).props('visible')).toBe(true)

    docRef.value = makeDoc([...FILES])
    await flushPromises()

    expect(preview(wrapper).props('visible')).toBe(true)
    expect(router.route.query.file).toBe('f1')
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('dropping the param externally (Back) closes the preview it opened', async () => {
    router.route.query = { file: 'f1' }
    const { wrapper } = mountView()
    await flushPromises()
    expect(preview(wrapper).props('visible')).toBe(true)

    router.route.query = {}
    await flushPromises()
    expect(preview(wrapper).props('visible')).toBe(false)
    // Closing in RESPONSE to the URL must not write the URL back.
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('switching the param to another known file re-targets the preview', async () => {
    router.route.query = { file: 'f1' }
    const { wrapper } = mountView()
    await flushPromises()
    expect(preview(wrapper).props('file')).toMatchObject({ id: 'f1' })

    router.route.query = { file: 'f2' }
    await flushPromises()
    expect(preview(wrapper).props('visible')).toBe(true)
    expect(preview(wrapper).props('file')).toMatchObject({ id: 'f2' })
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('passes the document id into every action menu so the copy-link can be built', () => {
    const { wrapper } = mountView()
    const menus = wrapper.findAllComponents({ name: 'FileActionMenu' })
    // Grid is the default view, so the tiles' menus are what is mounted here.
    expect(menus.length).toBe(FILES.length)
    for (const menu of menus) expect(menu.props('documentId')).toBe('doc-1')
  })
})
