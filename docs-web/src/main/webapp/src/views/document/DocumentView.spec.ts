import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'
import { duplicateDocument } from '../../api/document'
import type { Tag } from '../../api/tag'
import { useTagFilterStore } from '../../stores/tagFilter'

// (Auto-unmount after each test is now enabled globally in vitest.setup.ts, so no pending
// async work fires after the jsdom environment is torn down — the fix for the intermittent
// "HTMLElement is not defined" CI failure that first surfaced here.)

// The document header's clickable tag chips (#34) are the unit under test: a chip
// click must apply a POSITIVE tag filter via the store's selectTag and land on the
// filtered documents list. The chip must never route through toggleTag — its
// 3-state cycle would EXCLUDE an already-selected tag, so the already-selected
// case is pinned explicitly below.

const DOC_TAG: Tag = { id: 'tag-inv', name: 'Invoice', color: '#d32f2f', parent: null }

const DOC = {
  id: 'doc1',
  title: 'Quarterly invoice',
  description: '',
  create_date: 1700000000000,
  update_date: 1700000000000,
  language: 'eng',
  creator: 'admin',
  file_id: null,
  file_count: 0,
  tags: [DOC_TAG],
  shared: false,
  writable: true,
}

// A document WITH a cover file (#206): `file_id` is the served cover pointer the backend
// reconciles, `file_rotation` the baked rotation of its raster.
const DOC_WITH_COVER = {
  ...DOC,
  file_id: 'file-cover-1',
  file_rotation: 90,
  file_count: 3,
}

// Which document the mocked API serves. `DOC` (no files at all) is the default so the
// pre-existing tests are unaffected; the #206 tests swap in the with-cover variant.
let servedDoc: Record<string, unknown> = DOC

vi.mock('../../api/document', () => ({
  getDocument: vi.fn(() => Promise.resolve({ data: servedDoc })),
  deleteDocument: vi.fn(),
  duplicateDocument: vi.fn(() => Promise.resolve({ data: { id: 'copy-1' } })),
}))

// The header also shows the caller's own access count (#300), whose query would otherwise reach
// for the network. Stub it; DocumentView.access.spec.ts covers that surface.
vi.mock('../../api/access', () => ({
  getDocumentAccessCounts: vi.fn(() => Promise.resolve({ data: { count: 0, files: [] } })),
}))

// Mirrors the REAL getFileUrl signature (size, shareId, rotation → `?size=…&v=…`): a mock
// that swallowed the extra arguments would let a header thumbnail request the ORIGINAL file
// (a multi-MB attachment download) or a stale pre-rotation raster and still read green.
vi.mock('../../api/file', () => ({
  getFileUrl: (id: string, size?: string, shareId?: string, rotation?: number) => {
    const q = new URLSearchParams()
    if (size) q.set('size', size)
    if (shareId) q.set('share', shareId)
    if ((size === 'web' || size === 'thumb') && rotation) q.set('v', String(rotation))
    const s = q.toString()
    return `api/file/${id}/data${s ? `?${s}` : ''}`
  },
  getDocumentZipUrl: (id: string) => `api/file/zip?id=${id}`,
}))

// The tagFilter store hits the tag API on setup; feed it the doc's tag so the
// store logic runs against real data without the network.
vi.mock('../../api/tag', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/tag')>()
  return {
    ...actual,
    listTags: vi.fn(() => Promise.resolve({ data: { tags: [DOC_TAG] } })),
    getTagStats: vi.fn(() => Promise.resolve({ data: { stats: {} } })),
    getTagFacets: vi.fn(() => Promise.resolve({ data: { facets: {}, total: 0 } })),
    getTagCoOccurrence: vi.fn(() => Promise.resolve({ data: { pairs: [] } })),
  }
})

// PrimeVue overlays probe window.matchMedia, and Tabs uses ResizeObserver —
// neither is provided by jsdom. Stub both for this environment.
beforeAll(() => {
  if (typeof globalThis.ResizeObserver !== 'function') {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
  if (typeof window.matchMedia !== 'function') {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }),
    })
  }
})

import DocumentView from './DocumentView.vue'

let router: Router

beforeEach(() => {
  servedDoc = DOC
  setActivePinia(createPinia())
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/document', name: 'documents', component: { template: '<div />' } },
      {
        path: '/document/view/:id',
        name: 'document-view-content',
        component: { template: '<div />' },
      },
      { path: '/document/copy/:id', name: 'document-view', component: { template: '<div />' } },
    ],
  })
})

// The mounted view's query cache, exposed so a test can push a NEW server state (e.g. a cover
// swap) into the live component the way a refetch would.
let queryClient: QueryClient

async function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router.push('/document/view/doc1')
  await router.isReady()
  const wrapper = mount(DocumentView, {
    props: { id: 'doc1' },
    global: {
      plugins: [i18n, router, PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
      stubs: { RouterView: true },
    },
  })
  await flushPromises()
  return wrapper
}

describe('DocumentView — clickable header tag chips (#34)', () => {
  it('clicking an unselected tag chip selects the tag and navigates to the filtered list', async () => {
    const wrapper = await mountView()
    const store = useTagFilterStore()
    expect(store.selectedTagIds.has(DOC_TAG.id)).toBe(false)

    // The header chip renders as a filter button with the tag's accessible name.
    const chip = wrapper.find('.doc-header-tags button.tag-clickable')
    expect(chip.exists()).toBe(true)
    expect(chip.attributes('aria-label')).toBe('Filter by tag Invoice')

    await chip.trigger('click')
    await flushPromises()

    expect(store.selectedTagIds.has(DOC_TAG.id)).toBe(true)
    expect(store.excludedTagIds.size).toBe(0)
    // selectTag itself navigates to the documents list carrying the filter.
    expect(router.currentRoute.value.name).toBe('documents')
    expect(router.currentRoute.value.query.tags).toBe(DOC_TAG.id)
  })

  it('clicking an ALREADY-SELECTED tag chip navigates WITHOUT excluding the tag', async () => {
    const wrapper = await mountView()
    const store = useTagFilterStore()
    // The tag is already part of the active filter (e.g. the user arrived from
    // the filtered list).
    store.selectedTagIds = new Set([DOC_TAG.id])

    await wrapper.find('.doc-header-tags button.tag-clickable').trigger('click')
    await flushPromises()

    // Idempotent: still selected, and NEVER moved into the excluded set (the
    // toggleTag cycle would do exactly that).
    expect(store.selectedTagIds.has(DOC_TAG.id)).toBe(true)
    expect(store.excludedTagIds.size).toBe(0)
    // Navigation still lands on the filtered documents list.
    expect(router.currentRoute.value.name).toBe('documents')
    expect(router.currentRoute.value.query.tags).toBe(DOC_TAG.id)
  })
})

// #206 — the header carries the document's cover thumbnail. Two states have to hold: a
// document WITH files shows the cover raster the list/gallery rows already show, and a
// document with NO files shows a placeholder. The placeholder is decided in the CLIENT
// because the server's placeholder raster sits behind a file lookup — a document with no
// files has no file id to ask about, so that branch is unreachable for exactly the case
// that needs it.
describe('DocumentView — header cover thumbnail (#206)', () => {
  it('with a cover file: renders the THUMB raster, rotation-busted, never the original', async () => {
    servedDoc = DOC_WITH_COVER
    const wrapper = await mountView()

    const img = wrapper.find('.doc-header-thumb img')
    expect(img.exists()).toBe(true)
    const src = img.attributes('src')!
    // The derived thumbnail — not the original attachment (which the backend serves as a
    // download), and not the heavier `web` raster.
    expect(src).toContain('api/file/file-cover-1/data')
    expect(src).toContain('size=thumb')
    // The raster is cached long-lived, so the stored rotation must vary the URL — otherwise a
    // rotated cover keeps showing its pre-rotation orientation.
    expect(src).toContain('v=90')
    // The placeholder is NOT rendered alongside it.
    expect(wrapper.find('.doc-header-thumb i.pi-file').exists()).toBe(false)
  })

  it('with NO files: renders the client-side placeholder and requests no raster', async () => {
    // DOC (the default) has file_id: null / file_count: 0.
    const wrapper = await mountView()

    expect(wrapper.find('.doc-header-thumb').exists()).toBe(true)
    expect(wrapper.find('.doc-header-thumb img').exists()).toBe(false)
    expect(wrapper.find('.doc-header-thumb i.pi-file').exists()).toBe(true)
  })

  it('a thumbnail that fails to load degrades to the placeholder, not a broken image', async () => {
    servedDoc = DOC_WITH_COVER
    const wrapper = await mountView()

    await wrapper.find('.doc-header-thumb img').trigger('error')
    await flushPromises()

    expect(wrapper.find('.doc-header-thumb img').exists()).toBe(false)
    expect(wrapper.find('.doc-header-thumb i.pi-file').exists()).toBe(true)
  })

  // The cover raster can change under an in-flight request in three ways — the cover file
  // changes, the same file is rotated, or the cover cycles back to one already shown — and in
  // each the replaced <img> can still fire a LATE `error` that must not hide what is on screen
  // now. All three are walked because each defeats a DIFFERENT weak discriminator:
  //   * cover swap    — breaks a shared failure boolean (any stale error hides the live cover);
  //   * rotation      — breaks an element keyed coarser than the url (e.g. file_id): the
  //                     element survives, its src mutates, and the stale error names the NEW url;
  //   * A→B→A cycle   — breaks url matching itself: the first A element's late error names a
  //                     url that is live again by the time it arrives.
  // Only ELEMENT IDENTITY closes all three, which is what the handler guards on.
  it('a LATE error from the replaced thumbnail cannot hide the new cover', async () => {
    servedDoc = DOC_WITH_COVER
    const wrapper = await mountView()
    // Hold the FIRST cover's element; it is the one whose request goes on to fail.
    const staleImg = wrapper.find('.doc-header-thumb img')
    expect(staleImg.attributes('src')).toContain('file-cover-1')

    // The document's cover changes while that request is still in flight.
    queryClient.setQueryData(['document', 'doc1'], {
      ...DOC_WITH_COVER,
      file_id: 'file-cover-2',
      file_rotation: 0,
    })
    await flushPromises()
    expect(wrapper.find('.doc-header-thumb img').attributes('src')).toContain('file-cover-2')

    // …and only NOW does the first cover's request fail, on the element it was issued from.
    await staleImg.trigger('error')
    await flushPromises()

    const img = wrapper.find('.doc-header-thumb img')
    expect(img.exists(), 'the new cover survives the stale failure').toBe(true)
    expect(img.attributes('src')).toContain('file-cover-2')
    expect(wrapper.find('.doc-header-thumb i.pi-file').exists()).toBe(false)
  })

  it('a LATE error from the pre-ROTATION thumbnail cannot hide the rotated one', async () => {
    servedDoc = DOC_WITH_COVER // file-cover-1, rotation 90
    const wrapper = await mountView()
    const staleImg = wrapper.find('.doc-header-thumb img')
    expect(staleImg.attributes('src')).toContain('v=90')

    // SAME file, rotated again: only the url changes (`v=`), which is exactly the case an
    // element keyed on file_id would survive — its src would be mutated in place.
    queryClient.setQueryData(['document', 'doc1'], { ...DOC_WITH_COVER, file_rotation: 180 })
    await flushPromises()
    expect(wrapper.find('.doc-header-thumb img').attributes('src')).toContain('v=180')

    // The pre-rotation request now fails, on the element it was issued from.
    await staleImg.trigger('error')
    await flushPromises()

    const img = wrapper.find('.doc-header-thumb img')
    expect(img.exists(), 'the rotated cover survives the stale failure').toBe(true)
    expect(img.attributes('src')).toContain('v=180')
    expect(wrapper.find('.doc-header-thumb i.pi-file').exists()).toBe(false)
  })

  it('an A→B→A cover cycle: the FIRST A element cannot hide the live A cover', async () => {
    servedDoc = DOC_WITH_COVER // A = file-cover-1 at rotation 90
    const wrapper = await mountView()
    const staleA = wrapper.find('.doc-header-thumb img')
    const urlA = staleA.attributes('src')!
    expect(urlA).toContain('file-cover-1')

    // A → B …
    queryClient.setQueryData(['document', 'doc1'], {
      ...DOC_WITH_COVER,
      file_id: 'file-cover-2',
      file_rotation: 0,
    })
    await flushPromises()
    // … and back to A: the very url the first, still-pending element carries is LIVE again.
    queryClient.setQueryData(['document', 'doc1'], { ...DOC_WITH_COVER })
    await flushPromises()

    const liveA = wrapper.find('.doc-header-thumb img')
    expect(liveA.attributes('src')).toBe(urlA)
    expect(liveA.element, 'a different element carrying the same url').not.toBe(staleA.element)

    // The FIRST A element's request finally fails — url matching would blame the live cover.
    await staleA.trigger('error')
    await flushPromises()
    const img = wrapper.find('.doc-header-thumb img')
    expect(img.exists(), 'the live cover survives its predecessor’s failure').toBe(true)
    expect(img.attributes('src')).toBe(urlA)
    expect(wrapper.find('.doc-header-thumb i.pi-file').exists()).toBe(false)

    // REALNESS: the guard is not a blanket "ignore every error" — the LIVE element's own
    // failure still degrades to the placeholder. This also pins that the template ref follows
    // the element across a keyed replacement (a lagging ref would silently swallow real
    // failures and make all three tests above pass vacuously).
    await wrapper.find('.doc-header-thumb img').trigger('error')
    await flushPromises()
    expect(wrapper.find('.doc-header-thumb img').exists()).toBe(false)
    expect(wrapper.find('.doc-header-thumb i.pi-file').exists()).toBe(true)
  })

  // A recorded failure must never outlive the URL it was about. Once the placeholder is
  // showing, NO <img> is mounted — so nothing on screen can ever retry that raster by itself;
  // only a URL change can put an element back. If the record survives a URL change, rotating
  // away and back lands on a URL that still matches it and the header is stuck on the
  // placeholder for the rest of the view's life, even though the raster has since been
  // generated. The retry is therefore driven by the CANDIDATE url changing, not by file_id
  // (which a same-file rotation never touches).
  it('a cover that failed is RETRIED after rotating away and back to it', async () => {
    servedDoc = DOC_WITH_COVER // rotation 90
    const wrapper = await mountView()
    const urlAt90 = wrapper.find('.doc-header-thumb img').attributes('src')!
    expect(urlAt90).toContain('v=90')

    // The rotation-90 raster is still being generated and fails → placeholder, by design.
    await wrapper.find('.doc-header-thumb img').trigger('error')
    await flushPromises()
    expect(wrapper.find('.doc-header-thumb img').exists()).toBe(false)
    expect(wrapper.find('.doc-header-thumb i.pi-file').exists()).toBe(true)

    // Rotate to 180 — a different url, so the cover is attempted again.
    queryClient.setQueryData(['document', 'doc1'], { ...DOC_WITH_COVER, file_rotation: 180 })
    await flushPromises()
    expect(wrapper.find('.doc-header-thumb img').attributes('src')).toContain('v=180')

    // …and back to 90, where the raster now exists. The header must ATTEMPT it.
    queryClient.setQueryData(['document', 'doc1'], { ...DOC_WITH_COVER })
    await flushPromises()
    const img = wrapper.find('.doc-header-thumb img')
    expect(img.exists(), 'the previously-failed cover is retried, not permanently placeheld').toBe(
      true,
    )
    expect(img.attributes('src')).toBe(urlAt90)
    expect(wrapper.find('.doc-header-thumb i.pi-file').exists()).toBe(false)

    // REALNESS: the retry is one honest attempt, not a disabled failure path — if it fails
    // again, the placeholder comes back.
    await wrapper.find('.doc-header-thumb img').trigger('error')
    await flushPromises()
    expect(wrapper.find('.doc-header-thumb img').exists()).toBe(false)
    expect(wrapper.find('.doc-header-thumb i.pi-file').exists()).toBe(true)
  })

  it('an unrotated cover carries no cache-bust key (URLs stay stable)', async () => {
    servedDoc = { ...DOC_WITH_COVER, file_rotation: 0 }
    const wrapper = await mountView()

    expect(wrapper.find('.doc-header-thumb img').attributes('src')).toBe(
      'api/file/file-cover-1/data?size=thumb',
    )
  })
})

describe('DocumentView — duplicate action (#184)', () => {
  it('renders a Duplicate action that calls the API and navigates to the copy', async () => {
    const wrapper = await mountView()

    const dup = wrapper.findAll('.doc-header-actions button').find((b) => b.text().includes('Duplicate'))
    expect(dup?.exists()).toBe(true)

    await dup!.trigger('click')
    await flushPromises()

    expect(vi.mocked(duplicateDocument)).toHaveBeenCalledWith('doc1')
    expect(router.currentRoute.value.name).toBe('document-view')
    expect(router.currentRoute.value.params.id).toBe('copy-1')
  })
})
