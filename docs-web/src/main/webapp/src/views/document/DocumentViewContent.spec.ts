import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { DocumentKey } from './documentKey'
import { AccessCountsKey } from './accessCountsKey'
import type { DocumentDetail } from '../../api/document'

// The view now derives its per-user file-view-mode key from the auth store, so every
// mount needs an active pinia (no user → anonymous username → grid default).
beforeEach(() => setActivePinia(createPinia()))

// #36: the "Related documents" section is the unit under test — direction grouping,
// full-surviving-list mutation composition, per-direction controls, and cross-document
// cache invalidation. The API modules and the confirm dialog are dependencies (mocked);
// buildRelationsParams stays REAL so the asserted payloads are the shipped wire format.

const listDocumentsMock = vi.fn()
const updateDocumentMock = vi.fn()
const swapRelationMock = vi.fn()
vi.mock('../../api/document', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/document')>()),
  listDocuments: (...a: unknown[]) => listDocumentsMock(...a),
  updateDocument: (...a: unknown[]) => updateDocumentMock(...a),
  swapRelation: (...a: unknown[]) => swapRelationMock(...a),
}))
const setRotationMock = vi.fn(() => Promise.resolve({ data: { status: 'ok', rotation: 0 } }))
const renameFileMock = vi.fn(() => Promise.resolve({ data: {} }))
// #211: the grid reorder asserts on the persisted payload AND on what a REJECTED persist does
// to the local order, so this one has to be steerable per test.
const reorderFilesMock = vi.fn<(...a: unknown[]) => Promise<unknown>>(() =>
  Promise.resolve({ data: { status: 'ok' } }),
)
// DocumentViewContent syncs the preview to a `?file=` deep link (#192), so it now resolves
// a route and a router. A static stand-in is enough here — this spec drives neither.
vi.mock('vue-router', () => ({
  useRoute: () => ({ name: 'document-view-content', params: { id: 'doc-1' }, query: {} }),
  useRouter: () => ({ replace: vi.fn() }),
}))
vi.mock('../../api/file', () => ({
  buildFileLink: (d: string, fid: string) => `https://app/#/document/view/${d}/content?file=${fid}`,
  // Reflect the size + rotation cache-bust so tests can assert the served URL varies by rotation
  // (the real getFileUrl behaviour). The original file (no size) never carries a cache-bust key.
  getFileUrl: (id: string, size?: string, _shareId?: string, rotation?: number) => {
    const params = new URLSearchParams()
    if (size) params.set('size', size)
    if ((size === 'web' || size === 'thumb') && rotation) params.set('v', String(rotation))
    const suffix = params.toString()
    return `/api/file/${id}/data${suffix ? `?${suffix}` : ''}`
  },
  setRotation: (...a: unknown[]) => setRotationMock(...a),
  deleteFile: vi.fn(),
  renameFile: (...a: unknown[]) => renameFileMock(...a),
  uploadFile: vi.fn(),
  reorderFiles: (...a: unknown[]) => reorderFilesMock(...a),
}))
// pdfjs-dist (imported at module level by PdfViewer) needs DOMMatrix, which jsdom
// lacks — replace the whole module; the viewer is irrelevant to the relations unit.
vi.mock('../../components/PdfViewer.vue', () => ({
  default: { name: 'PdfViewer', template: '<div />' },
}))
vi.mock('vue-i18n', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (k: string) => k }),
}))
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))
// The danger-confirm dialog is a dependency; accepting immediately exercises the REAL
// accept callback (the id-list composition under test) without driving PrimeVue overlays.
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({
    confirmDanger: (opts: { accept?: () => void }) => opts.accept?.(),
  }),
}))

vi.stubGlobal('URL', {
  ...URL,
  createObjectURL: (blob: Blob) => {
    const tag = (blob as unknown as { _tag?: string })?._tag ?? 'blob:test'
    return tag
  },
  revokeObjectURL: () => {},
})
vi.mock('../../composables/usePreviewQueue', () => ({
  usePreviewQueue: () => ({
    enqueue: (fileId: string, size?: string, _p?: number, _s?: string, rotation?: number) => {
      const params = new URLSearchParams()
      if (size) params.set('size', size)
      if ((size === 'web' || size === 'thumb') && rotation) params.set('v', String(rotation))
      const suffix = params.toString()
      const url = `/api/file/${fileId}/data${suffix ? `?${suffix}` : ''}`
      const blob = new Blob(['x'])
      ;(blob as unknown as { _tag: string })._tag = url
      return Promise.resolve(blob)
    },
    cancel: () => {},
    reprioritize: () => {},
  }),
}))

import Select from 'primevue/select'
import DocumentViewContent from './DocumentViewContent.vue'
import FilePreviewDialog from '../../components/FilePreviewDialog.vue'

// A PROP-AWARE PdfViewer stub. `PdfViewer: true` would erase the prop contract and record
// every binding as a plain attribute, so a MISSING `:downloadable` and the #178 fix that adds
// `:downloadable="false"` would look identical. Declaring the real props — with `downloadable`'s
// true default — makes the omission observable.
const PdfViewerStub = defineComponent({
  name: 'PdfViewer',
  props: {
    src: { type: String, default: '' },
    initialRotation: { type: Number, default: 0 },
    persistable: { type: Boolean, default: false },
    downloadable: { type: Boolean, default: true },
    // #235: the page area's open affordance. Declared with the real component's OFF default for
    // the same reason `downloadable` is — an unset `openable` and an explicit one must not look
    // alike, or the card losing its open affordance again would be invisible here.
    openable: { type: Boolean, default: false },
    openLabel: { type: String, default: '' },
  },
  emits: ['rotate', 'error', 'open'],
  render: () => h('div', { class: 'pdf-viewer-stub' }),
})

function makeDoc(overrides: Partial<DocumentDetail> = {}): DocumentDetail {
  return {
    id: 'doc-src',
    title: 'Source Doc',
    language: 'eng',
    writable: true,
    description: '',
    tags: [],
    relations: [
      { id: 'rel-out', title: 'Outgoing Doc', source: true },
      { id: 'rel-in', title: 'Incoming Doc', source: false },
    ],
    metadata: [],
    files: [],
    ...overrides,
  } as unknown as DocumentDetail
}

function mountView(doc: DocumentDetail, slots: Record<string, string> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
  const docRef = ref(doc)
  const wrapper = mount(DocumentViewContent, {
    slots,
    global: {
      plugins: [PrimeVue, [VueQueryPlugin, { queryClient }]],
      provide: { [DocumentKey as symbol]: docRef },
      stubs: {
        FileUpload: true,
        CameraCaptureButton: true,
        UploadProgressList: true,
        FileVersionsDialog: true,
        PdfViewer: PdfViewerStub,
        EmptyState: true,
        RouterLink: { template: '<a><slot /></a>' },
      },
      directives: { tooltip: {} },
    },
  })
  return { wrapper, invalidateSpy, docRef }
}

type ViewVm = {
  selectedRelationTarget: { id: string; title: string } | null
  handleAddRelation: () => Promise<void>
}

// `t` is stubbed to the identity, so the aria-label IS the message key. Naming the control is what
// keeps these assertions honest now that a relation row carries more than one button.
const SWAP_BUTTON = 'button[aria-label="ui.relations.swap"]'
const REMOVE_BUTTON = 'button[aria-label="ui.relations.remove"]'

describe('DocumentViewContent — related documents (#36)', () => {
  beforeEach(() => {
    listDocumentsMock.mockReset()
    updateDocumentMock.mockReset().mockResolvedValue({ data: { id: 'doc-src' } })
    swapRelationMock.mockReset().mockResolvedValue({ data: { status: 'ok' } })
  })

  it('renders relations grouped by direction (outgoing under links_to, incoming under linked_from)', () => {
    const { wrapper } = mountView(makeDoc())
    const groups = wrapper.findAll('.relation-group')
    expect(groups.length).toBe(2)
    const linksTo = groups.find((g) => g.text().includes('ui.relations.links_to'))!
    const linkedFrom = groups.find((g) => g.text().includes('ui.relations.linked_from'))!
    expect(linksTo.text()).toContain('Outgoing Doc')
    expect(linksTo.text()).not.toContain('Incoming Doc')
    expect(linkedFrom.text()).toContain('Incoming Doc')
    expect(linkedFrom.text()).not.toContain('Outgoing Doc')
  })

  it('incoming rows carry NO remove control; outgoing rows do — both carry the swap control (writable doc)', () => {
    const { wrapper } = mountView(makeDoc())
    const linksTo = wrapper.findAll('.relation-group').find((g) => g.text().includes('ui.relations.links_to'))!
    const linkedFrom = wrapper.findAll('.relation-group').find((g) => g.text().includes('ui.relations.linked_from'))!
    // Outgoing: swap + remove. Incoming: swap only — the link is owned by the other document, so it
    // can be reversed onto this one but not removed from here.
    expect(linksTo.findAll(REMOVE_BUTTON).length).toBe(1)
    expect(linksTo.findAll(SWAP_BUTTON).length).toBe(1)
    expect(linkedFrom.findAll(REMOVE_BUTTON).length).toBe(0)
    expect(linkedFrom.findAll(SWAP_BUTTON).length).toBe(1)
  })

  it('renders no add form and no remove controls when the document is not writable (links still shown)', () => {
    const { wrapper } = mountView(makeDoc({ writable: false } as Partial<DocumentDetail>))
    expect(wrapper.find('.relation-add').exists()).toBe(false)
    expect(wrapper.findAll('.relation-row button').length).toBe(0)
    // Both direction links still display read-only.
    expect(wrapper.text()).toContain('Outgoing Doc')
    expect(wrapper.text()).toContain('Incoming Doc')
  })

  it('add composes the FULL surviving outgoing id list (existing + new), with required title/language', async () => {
    const { wrapper } = mountView(makeDoc())
    const vm = wrapper.vm as unknown as ViewVm
    vm.selectedRelationTarget = { id: 'rel-new', title: 'New Target' }
    await vm.handleAddRelation()
    await flushPromises()
    expect(updateDocumentMock).toHaveBeenCalledTimes(1)
    const [id, params] = updateDocumentMock.mock.calls[0] as [string, URLSearchParams]
    expect(id).toBe('doc-src')
    expect(params.getAll('relations')).toEqual(['rel-out', 'rel-new'])
    expect(params.get('title')).toBe('Source Doc')
    expect(params.get('language')).toBe('eng')
    expect(params.get('relations_reset')).toBeNull()
  })

  it('remove drops exactly the removed id, keeping the other outgoing relations', async () => {
    const doc = makeDoc({
      relations: [
        { id: 'rel-a', title: 'Keep Me', source: true },
        { id: 'rel-b', title: 'Drop Me', source: true },
        { id: 'rel-in', title: 'Incoming Doc', source: false },
      ],
    } as Partial<DocumentDetail>)
    const { wrapper } = mountView(doc)
    const dropRow = wrapper.findAll('.relation-row').find((r) => r.text().includes('Drop Me'))!
    await dropRow.get(REMOVE_BUTTON).trigger('click')
    await flushPromises()
    expect(updateDocumentMock).toHaveBeenCalledTimes(1)
    const [, params] = updateDocumentMock.mock.calls[0] as [string, URLSearchParams]
    expect(params.getAll('relations')).toEqual(['rel-a'])
    expect(params.get('relations_reset')).toBeNull()
  })

  it('removing the LAST outgoing relation sends relations_reset=true and no relations', async () => {
    const doc = makeDoc({
      relations: [{ id: 'rel-only', title: 'Last One', source: true }],
    } as Partial<DocumentDetail>)
    const { wrapper } = mountView(doc)
    await wrapper.get(`.relation-row ${REMOVE_BUTTON}`).trigger('click')
    await flushPromises()
    const [, params] = updateDocumentMock.mock.calls[0] as [string, URLSearchParams]
    expect(params.getAll('relations')).toEqual([])
    expect(params.get('relations_reset')).toBe('true')
  })

  it('a relations mutation invalidates the SOURCE and every AFFECTED TARGET document query', async () => {
    // Relating doc-src -> rel-new also changes rel-new's INCOMING list; if rel-new's
    // detail is cached, invalidating only the source leaves the target view stale for
    // the whole staleTime window on in-app navigation.
    const { wrapper, invalidateSpy } = mountView(makeDoc())
    const vm = wrapper.vm as unknown as ViewVm
    vm.selectedRelationTarget = { id: 'rel-new', title: 'New Target' }
    await vm.handleAddRelation()
    await flushPromises()
    const keys = invalidateSpy.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey)
    expect(keys).toContainEqual(['document', 'doc-src'])
    expect(keys).toContainEqual(['document', 'rel-new'])
  })

  it('removing a relation invalidates the REMOVED target document query too', async () => {
    const doc = makeDoc({
      relations: [{ id: 'rel-gone', title: 'To Remove', source: true }],
    } as Partial<DocumentDetail>)
    const { wrapper, invalidateSpy } = mountView(doc)
    await wrapper.get(`.relation-row ${REMOVE_BUTTON}`).trigger('click')
    await flushPromises()
    const keys = invalidateSpy.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey)
    expect(keys).toContainEqual(['document', 'doc-src'])
    expect(keys).toContainEqual(['document', 'rel-gone'])
  })

  // #191 — the endpoint takes the pair in its CURRENT orientation, so the argument order is the
  // whole contract: get it backwards on the incoming group and the call either 404s or reverses a
  // different pair. Both directions are pinned explicitly.
  it('swapping an OUTGOING relation sends (this document, related document) in that order', async () => {
    const { wrapper } = mountView(makeDoc())
    const outgoingRow = wrapper.findAll('.relation-row').find((r) => r.text().includes('Outgoing Doc'))!
    await outgoingRow.get(SWAP_BUTTON).trigger('click')
    await flushPromises()
    expect(swapRelationMock).toHaveBeenCalledTimes(1)
    expect(swapRelationMock.mock.calls[0]).toEqual(['doc-src', 'rel-out'])
    expect(updateDocumentMock).not.toHaveBeenCalled()
  })

  it('swapping an INCOMING relation sends (related document, this document) — the reverse order', async () => {
    const { wrapper } = mountView(makeDoc())
    const incomingRow = wrapper.findAll('.relation-row').find((r) => r.text().includes('Incoming Doc'))!
    await incomingRow.get(SWAP_BUTTON).trigger('click')
    await flushPromises()
    expect(swapRelationMock).toHaveBeenCalledTimes(1)
    expect(swapRelationMock.mock.calls[0]).toEqual(['rel-in', 'doc-src'])
  })

  it('a swap invalidates BOTH documents\' queries', async () => {
    const { wrapper, invalidateSpy } = mountView(makeDoc())
    const outgoingRow = wrapper.findAll('.relation-row').find((r) => r.text().includes('Outgoing Doc'))!
    await outgoingRow.get(SWAP_BUTTON).trigger('click')
    await flushPromises()
    const keys = invalidateSpy.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey)
    expect(keys).toContainEqual(['document', 'doc-src'])
    expect(keys).toContainEqual(['document', 'rel-out'])
  })

  it('renders no swap control on either group when the document is not writable', () => {
    const { wrapper } = mountView(makeDoc({ writable: false } as Partial<DocumentDetail>))
    expect(wrapper.findAll(SWAP_BUTTON).length).toBe(0)
  })
})

// v3.5.2 — persisted, non-destructive image rotation. The served _web raster is physically rotated
// server-side, so the image must NOT also carry a CSS rotate transform (double-rotation). The stored
// rotation drives only the cache-bust key and the next rotate value.
describe('DocumentViewContent — persisted image rotation', () => {
  beforeEach(() => {
    setRotationMock.mockReset().mockResolvedValue({ data: { status: 'ok', rotation: 0 } })
  })

  function imageDoc(rotation?: number): DocumentDetail {
    return makeDoc({
      relations: [],
      files: [{ id: 'img-1', name: 'photo.jpg', mimetype: 'image/jpeg', size: 100, rotation }],
    } as unknown as Partial<DocumentDetail>)
  }

  it('does NOT apply a CSS rotate transform to the persisted image (no double-rotation)', async () => {
    const { wrapper } = mountView(imageDoc(90))
    await flushPromises()
    const img = wrapper.find('.rotatable-image')
    expect(img.exists()).toBe(true)
    const style = img.attributes('style') ?? ''
    expect(style).not.toContain('rotate')
    expect(img.attributes('src')).toBe('/api/file/img-1/data?size=web&v=90')
  })

  it('an upright image (rotation 0/absent) has no cache-bust key', async () => {
    const { wrapper } = mountView(imageDoc(0))
    await flushPromises()
    expect(wrapper.find('.rotatable-image').attributes('src')).toBe('/api/file/img-1/data?size=web')
  })

  it('rotate-right persists the next absolute rotation and invalidates BOTH the document AND the documents list', async () => {
    const { wrapper, invalidateSpy } = mountView(imageDoc(90))
    const rotateRight = wrapper
      .findAll('.image-preview-controls button')
      .find((b) => b.attributes('aria-label') === 'ui.rotate_right')!
    await rotateRight.trigger('click')
    await flushPromises()
    // 90 + 90 = 180 (absolute, from the persisted value — never compounds).
    expect(setRotationMock).toHaveBeenCalledWith('img-1', 180)
    const keys = invalidateSpy.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey)
    expect(keys).toContainEqual(['document', 'doc-src'])
    // The list (gallery/table/slide-over rows carry file_rotation + a cache-busted thumb URL) must
    // also refetch, or every list consumer keeps the stale rotation for the whole staleTime window.
    expect(keys).toContainEqual(['documents'])
  })

  it('rotate-left wraps to 270 from an upright image', async () => {
    const { wrapper } = mountView(imageDoc(0))
    const rotateLeft = wrapper
      .findAll('.image-preview-controls button')
      .find((b) => b.attributes('aria-label') === 'ui.rotate_left')!
    await rotateLeft.trigger('click')
    await flushPromises()
    expect(setRotationMock).toHaveBeenCalledWith('img-1', 270)
  })

  it('hides the rotate controls when the document is not writable', () => {
    const { wrapper } = mountView(
      makeDoc({
        relations: [],
        writable: false,
        files: [{ id: 'img-1', name: 'photo.jpg', mimetype: 'image/jpeg', size: 100 }],
      } as unknown as Partial<DocumentDetail>),
    )
    expect(wrapper.find('.image-preview-controls').exists()).toBe(false)
  })
})

// #58 — the shared per-file action slot (#file-extra). A per-file action
// defined ONCE at the view level must light up in BOTH the grid tiles and the list rows
// (this is the mount point #73 "Edit pages" / #117 "Upload new version" use) and inherit
// FileActionMenu's writable gate. Proven by injecting slot content through the view.
describe('DocumentViewContent — #file-extra per-file action slot (#73/#117 mount point)', () => {
  beforeEach(() => localStorage.clear())

  function fileDoc(writable = true): DocumentDetail {
    return makeDoc({
      relations: [],
      writable,
      files: [
        { id: 'f1', name: 'a.jpg', mimetype: 'image/jpeg', size: 1, version: 0, create_date: 0, creator: 'admin' },
      ],
    } as unknown as Partial<DocumentDetail>)
  }
  const slot = { 'file-extra': '<button class="phase-action">edit</button>' }

  it('renders the injected per-file action in the GRID tiles when writable (grid is default)', () => {
    const { wrapper } = mountView(fileDoc(true), slot)
    expect(wrapper.find('.file-preview-grid').exists()).toBe(true)
    expect(wrapper.findAll('.phase-action').length).toBeGreaterThan(0)
  })

  it('renders the injected per-file action in the LIST rows when writable', () => {
    // Anonymous username in tests → the per-user key has an empty suffix.
    localStorage.setItem('teedy_file_view_mode:', 'list')
    const { wrapper } = mountView(fileDoc(true), slot)
    expect(wrapper.find('.file-data-table').exists()).toBe(true)
    expect(wrapper.findAll('.phase-action').length).toBeGreaterThan(0)
  })

  it('does NOT render the per-file action in either view when the document is read-only', () => {
    const { wrapper: grid } = mountView(fileDoc(false), slot)
    expect(grid.find('.file-preview-grid').exists()).toBe(true)
    expect(grid.findAll('.phase-action').length).toBe(0)

    localStorage.setItem('teedy_file_view_mode:', 'list')
    const { wrapper: list } = mountView(fileDoc(false), slot)
    expect(list.find('.file-data-table').exists()).toBe(true)
    expect(list.findAll('.phase-action').length).toBe(0)
  })
})

// The grid tiles gained the shared action menu. Its rename control must be
// live (a compact per-card editor), not a dead button.
describe('DocumentViewContent — grid tile actions', () => {
  beforeEach(() => {
    localStorage.clear()
    renameFileMock.mockClear()
  })

  function imageDoc(writable = true): DocumentDetail {
    return makeDoc({
      relations: [],
      writable,
      files: [
        { id: 'f1', name: 'a.jpg', mimetype: 'image/jpeg', size: 1, version: 0, create_date: 0, creator: 'admin' },
      ],
    } as unknown as Partial<DocumentDetail>)
  }

  it('the grid Rename control opens a per-tile inline editor (writable)', async () => {
    const { wrapper } = mountView(imageDoc(true)) // grid is default
    const rename = wrapper.findAll('.file-card-actions button').find((b) => b.attributes('aria-label') === 'rename')!
    expect(rename).toBeTruthy()
    await rename.trigger('click')
    expect(wrapper.find('input.grid-rename-input').exists()).toBe(true)
  })

  it('read-only grid tiles expose no rename/delete, only version history', () => {
    const { wrapper } = mountView(imageDoc(false))
    const labels = wrapper.findAll('.file-card-actions button').map((b) => b.attributes('aria-label'))
    expect(labels).toContain('ui.versions.title')
    expect(labels).not.toContain('rename')
    expect(labels).not.toContain('ui.remove_file')
  })

  it('a mid-edit permission flip to read-only blocks the grid rename commit (no write fires)', async () => {
    const { wrapper, docRef } = mountView(imageDoc(true))
    // Open the editor while writable.
    await wrapper.findAll('.file-card-actions button').find((b) => b.attributes('aria-label') === 'rename')!.trigger('click')
    const input = wrapper.find('input.grid-rename-input')
    expect(input.exists()).toBe(true)
    await input.setValue('renamed.jpg')

    // Permissions refetch to read-only WHILE the editor is open.
    docRef.value = { ...docRef.value, writable: false }
    await wrapper.vm.$nextTick()

    // Enter must NOT issue the rename write.
    await input.trigger('keyup.enter')
    await flushPromises()
    expect(renameFileMock).not.toHaveBeenCalled()
  })
})

// #178 — preview + download are now offered by the SHARED FileActionMenu, so they must be
// live at every mount site. The grid duplicates its tile markup per MIME branch (image /
// PDF / generic), so a single-branch test would let one branch stay silently unwired —
// each branch is exercised separately. The list row is the fourth mount site. Note that
// DocumentViewContent.processing.spec.ts stubs FileActionMenu, so this wiring is invisible
// there; the coverage has to live here.
describe('DocumentViewContent — #178 preview + download from the tile action menu', () => {
  beforeEach(() => localStorage.clear())

  function mixedDoc(writable = true): DocumentDetail {
    return makeDoc({
      relations: [],
      writable,
      files: [
        { id: 'f-img', name: 'a.jpg', mimetype: 'image/jpeg', size: 1, version: 0, create_date: 0, creator: 'admin' },
        { id: 'f-pdf', name: 'b.pdf', mimetype: 'application/pdf', size: 1, version: 0, create_date: 0, creator: 'admin' },
        { id: 'f-zip', name: 'c.zip', mimetype: 'application/zip', size: 1, version: 0, create_date: 0, creator: 'admin' },
      ],
    } as unknown as Partial<DocumentDetail>)
  }

  function tileFor(wrapper: ReturnType<typeof mountView>['wrapper'], label: string) {
    return wrapper
      .findAll('.file-preview-card')
      .find((c) => c.find('.file-preview-label').text() === label)!
  }

  // The action-menu preview control, scoped to the tile's action row: the generic tile's
  // own card button carries the same label, and this must not fall back onto it.
  function previewButton(tile: ReturnType<typeof tileFor>) {
    return tile
      .findAll('.file-card-actions button')
      .find((b) => b.attributes('aria-label') === 'ui.file_view.open_file')!
  }

  it.each([
    ['image', 'a.jpg', 'f-img', 'image/jpeg'],
    ['pdf', 'b.pdf', 'f-pdf', 'application/pdf'],
    ['generic', 'c.zip', 'f-zip', 'application/zip'],
  ])('the %s grid tile wires its action-menu preview into the in-app dialog', async (_kind, label, id, mimetype) => {
    const { wrapper } = mountView(mixedDoc()) // grid is the default view mode
    const preview = previewButton(tileFor(wrapper, label))
    expect(preview, `${label} tile exposes an action-menu preview control`).toBeTruthy()

    await preview.trigger('click')

    const dialog = wrapper.findComponent(FilePreviewDialog)
    expect(dialog.props('visible')).toBe(true)
    expect(dialog.props('file')).toMatchObject({ id, mimetype })
  })

  it('the LIST row action menu previews through the same dialog (fourth mount site)', async () => {
    // Anonymous username in tests → the per-user key has an empty suffix.
    localStorage.setItem('teedy_file_view_mode:', 'list')
    const { wrapper } = mountView(mixedDoc())
    expect(wrapper.find('.file-data-table').exists()).toBe(true)

    const pdfRow = wrapper.findAll('tbody tr').find((r) => r.text().includes('b.pdf'))!
    const preview = pdfRow
      .findAll('.file-action-menu button')
      .find((b) => b.attributes('aria-label') === 'ui.file_view.open_file')!
    expect(preview, 'list row exposes an action-menu preview control').toBeTruthy()

    await preview.trigger('click')

    const dialog = wrapper.findComponent(FilePreviewDialog)
    expect(dialog.props('visible')).toBe(true)
    expect(dialog.props('file')).toMatchObject({ id: 'f-pdf', mimetype: 'application/pdf' })
  })

  it('every grid tile offers exactly one labelled Download for the ORIGINAL file', () => {
    const { wrapper } = mountView(mixedDoc())
    const anchors = wrapper.findAll('.file-card-actions a')
    expect(anchors.map((a) => a.attributes('href'))).toEqual([
      '/api/file/f-img/data',
      '/api/file/f-pdf/data',
      '/api/file/f-zip/data',
    ])
    // No derived variant: Download must serve the original bytes, not a web/thumb raster.
    expect(anchors.every((a) => !(a.attributes('href') ?? '').includes('size='))).toBe(true)
    expect(anchors.map((a) => a.attributes('aria-label'))).toEqual(['download', 'download', 'download'])
  })

  it('the grid PdfViewer no longer exposes its own download (the replacement is the menu anchor)', () => {
    const { wrapper } = mountView(mixedDoc())
    const pdf = wrapper.findComponent(PdfViewerStub)
    expect(pdf.exists()).toBe(true)
    expect(pdf.props('downloadable')).toBe(false)
    // The rest of the binding is unchanged: the viewer still serves the original URL.
    expect(pdf.props('src')).toBe('/api/file/f-pdf/data')
  })

  it('read-only tiles keep preview and download (both are read actions, above the writable gate)', () => {
    const { wrapper } = mountView(mixedDoc(false))
    const tile = tileFor(wrapper, 'a.jpg')
    expect(previewButton(tile)).toBeTruthy()
    const download = tile.find('.file-card-actions a')
    expect(download.exists()).toBe(true)
    expect(download.attributes('href')).toBe('/api/file/f-img/data')
    // …while the write actions stay gated.
    const labels = tile.findAll('.file-card-actions button').map((b) => b.attributes('aria-label'))
    expect(labels).not.toContain('rename')
    expect(labels).not.toContain('ui.remove_file')
  })
})

// #235 — the grid card's PICTURE opens the file. Only the generic tile ever had an open
// affordance on its media (`.generic-open`); the image and PDF tiles carried mouse/drag
// handlers and nothing else, so clicking the thing a user actually points at — the photo, the
// page — did nothing at all, three shipped fixes notwithstanding (they all landed on the
// document GALLERY, a different surface). The contract has two halves and both are asserted
// here: the media opens, and the controls sitting ON the media (rotation, and inside the
// viewer the page-nav) keep working and never open.
describe('DocumentViewContent — the grid image and PDF media open the preview (#235)', () => {
  beforeEach(() => {
    localStorage.clear()
    setRotationMock.mockClear()
  })

  function openableDoc(writable = true): DocumentDetail {
    return makeDoc({
      relations: [],
      writable,
      files: [
        { id: 'f-img', name: 'a.jpg', mimetype: 'image/jpeg', size: 1, version: 0, create_date: 0, creator: 'admin' },
        { id: 'f-pdf', name: 'b.pdf', mimetype: 'application/pdf', size: 1, version: 0, create_date: 0, creator: 'admin' },
      ],
    } as unknown as Partial<DocumentDetail>)
  }

  function tile(wrapper: ReturnType<typeof mountView>['wrapper'], label: string) {
    return wrapper
      .findAll('.file-preview-card')
      .find((c) => c.find('.file-preview-label').text() === label)!
  }

  it('the image stage is a real button and opens the in-app preview on that file', async () => {
    const { wrapper } = mountView(openableDoc())
    await flushPromises()
    const stage = tile(wrapper, 'a.jpg').find('.image-preview-stage')
    expect(stage.exists()).toBe(true)
    // A BUTTON, like the generic card's stage: the same affordance for the pointer and the
    // keyboard, rather than a click handler on a div that no key can reach.
    expect(stage.element.tagName).toBe('BUTTON')
    expect(stage.attributes('aria-label')).toBe('ui.file_view.open_file')

    await stage.trigger('click')

    const dialog = wrapper.findComponent(FilePreviewDialog)
    expect(dialog.props('visible')).toBe(true)
    expect(dialog.props('file')).toMatchObject({ id: 'f-img', mimetype: 'image/jpeg' })
  })

  it('the stage image is not a native drag source, so a press that travels still clicks', async () => {
    const { wrapper } = mountView(openableDoc())
    await flushPromises()
    const img = tile(wrapper, 'a.jpg').find('img.rotatable-image')
    expect(img.exists()).toBe(true)
    // An <img> drags natively: without this the browser turns a few pixels of travel into an
    // image drag and delivers NO click, which is exactly how the fix bounced on the gallery.
    expect(img.attributes('draggable')).toBe('false')
  })

  it('the rotation controls sit OUTSIDE the open button: they rotate and never open', async () => {
    const { wrapper } = mountView(openableDoc())
    await flushPromises()
    const card = tile(wrapper, 'a.jpg')
    // Structural half of the non-hijack contract: no control is nested inside the open button,
    // so a rotation press can never be swallowed by (or double-fire with) the open.
    expect(card.find('.image-preview-stage').findAll('button')).toHaveLength(0)
    const rotate = card.findAll('.image-preview-controls button')
    expect(rotate).toHaveLength(2)

    await rotate[1].trigger('click')
    await flushPromises()

    expect(setRotationMock).toHaveBeenCalledTimes(1)
    expect(wrapper.findComponent(FilePreviewDialog).props('visible')).toBe(false)
  })

  it('the PDF tile viewer is openable and its open routes to the same dialog', async () => {
    const { wrapper } = mountView(openableDoc())
    await flushPromises()
    const pdf = wrapper.findComponent(PdfViewerStub)
    expect(pdf.props('openable')).toBe(true)
    expect(pdf.props('openLabel')).toBe('ui.file_view.open_file')

    pdf.vm.$emit('open')
    await wrapper.vm.$nextTick()

    const dialog = wrapper.findComponent(FilePreviewDialog)
    expect(dialog.props('visible')).toBe(true)
    expect(dialog.props('file')).toMatchObject({ id: 'f-pdf', mimetype: 'application/pdf' })
  })

  it('a read-only tile still opens (preview is a read action, above the writable gate)', async () => {
    const { wrapper } = mountView(openableDoc(false))
    await flushPromises()
    const card = tile(wrapper, 'a.jpg')
    // The rotation controls are gated away, the open affordance is not.
    expect(card.findAll('.image-preview-controls')).toHaveLength(0)
    await card.find('.image-preview-stage').trigger('click')
    expect(wrapper.findComponent(FilePreviewDialog).props('visible')).toBe(true)
    expect(wrapper.findComponent(PdfViewerStub).props('openable')).toBe(true)
  })
})

// #211 — grid-view drag reorder. The list reorders through PrimeVue's rowReorder inside
// FileListTable, which ALSO owns the list's optimistic order, its pre-drag snapshot and its
// in-flight lock. None of that exists in grid mode — FileListTable is not mounted — so the
// grid's ordered state lives in this component and every one of those guarantees has to be
// proven here, against the same POST /file/reorder contract.
describe('DocumentViewContent — grid drag reorder (#211)', () => {
  beforeEach(() => {
    localStorage.clear()
    reorderFilesMock.mockReset().mockResolvedValue({ data: { status: 'ok' } })
  })

  function file(id: string, name: string, mimetype = 'text/plain') {
    return { id, name, mimetype, size: 1, version: 0, create_date: 0, creator: 'admin' }
  }

  function gridDoc(writable = true, files = [file('f1', 'a.txt'), file('f2', 'b.txt'), file('f3', 'c.txt')]) {
    return makeDoc({ relations: [], writable, files } as unknown as Partial<DocumentDetail>)
  }

  type Wrapper = ReturnType<typeof mountView>['wrapper']

  function tileNames(wrapper: Wrapper) {
    return wrapper.findAll('.file-preview-grid .file-preview-label').map((l) => l.text())
  }

  // The gesture as the browser delivers it: a mousedown ON THE HANDLE (which arms the card as a
  // drag source), then dragstart on the card, then dragover + drop on the target card. jsdom has
  // no drag-and-drop, so the events carry no dataTransfer — which is also the honest shape of
  // the code under test (it never reads the payload back).
  async function dragTile(wrapper: Wrapper, from: number, to: number) {
    const cards = wrapper.findAll('.file-preview-card')
    await cards[from].get('.file-card-drag-handle').trigger('mousedown')
    await cards[from].trigger('dragstart')
    await cards[to].trigger('dragover')
    await cards[to].trigger('drop')
  }

  it('a drag applies the new order optimistically and persists the FULL id order', async () => {
    const { wrapper } = mountView(gridDoc())
    expect(tileNames(wrapper)).toEqual(['a.txt', 'b.txt', 'c.txt'])

    await dragTile(wrapper, 0, 2)

    // Optimistic: the tiles move before the request resolves.
    expect(tileNames(wrapper)).toEqual(['b.txt', 'c.txt', 'a.txt'])
    await flushPromises()
    expect(reorderFilesMock).toHaveBeenCalledTimes(1)
    // The endpoint rewrites each file's order from its position, so the payload must be the
    // COMPLETE id list — a partial one would renumber the document's files wrongly.
    expect(reorderFilesMock.mock.calls[0]).toEqual(['doc-src', ['f2', 'f3', 'f1']])
  })

  it('a REJECTED persist rolls the grid back to the pre-drag order', async () => {
    // Rejected on demand rather than immediately, so the OPTIMISTIC order is observable in
    // between: an implementation that never applied it would pass a rollback-only assertion.
    let fail: (reason: unknown) => void = () => {}
    reorderFilesMock.mockReturnValue(new Promise((_resolve, reject) => (fail = reject)))
    const { wrapper } = mountView(gridDoc())

    await dragTile(wrapper, 0, 2)
    expect(tileNames(wrapper), 'optimistic order applied before the failure').toEqual([
      'b.txt',
      'c.txt',
      'a.txt',
    ])

    fail(new Error('500'))
    await flushPromises()
    // The refetch may fail too, so the rollback has to be local and unconditional: the grid
    // must never keep showing an order the server refused.
    expect(tileNames(wrapper)).toEqual(['a.txt', 'b.txt', 'c.txt'])
  })

  it('serializes reorders — the handles are withdrawn while a persist is pending, and return after it', async () => {
    let settle: (v: unknown) => void = () => {}
    reorderFilesMock.mockReturnValue(new Promise((resolve) => (settle = resolve)))
    const { wrapper } = mountView(gridDoc())
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(3)

    await dragTile(wrapper, 0, 2)
    // No handle means no second drag: the single pre-drag snapshot cannot be overwritten
    // while a late failure could still need it.
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(0)
    expect(reorderFilesMock).toHaveBeenCalledTimes(1)

    settle({ data: { status: 'ok' } })
    await flushPromises()
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(3)
  })

  // The dragged card is the one the drop leaves under the pointer, so it is the one whose
  // synthesized click has to be swallowed — and the ONLY one.
  function cardNamed(wrapper: Wrapper, name: string) {
    return wrapper.findAll('.file-preview-card').find((c) => c.text().includes(name))!
  }

  it('a completed drop does not activate the control under the pointer on the DRAGGED card', async () => {
    const { wrapper } = mountView(gridDoc())
    await dragTile(wrapper, 0, 2)
    await flushPromises()

    // The click a finished drag synthesizes lands on whatever is under the pointer — the
    // dragged tile's own open-preview button. It must be swallowed, or every reorder opens a
    // preview of the file that was just moved.
    await cardNamed(wrapper, 'a.txt').get('.generic-open').trigger('click')
    expect(wrapper.findComponent(FilePreviewDialog).props('visible')).toBe(false)
  })

  it('a click on ANOTHER tile right after a drop is NOT swallowed', async () => {
    const { wrapper } = mountView(gridDoc())
    await dragTile(wrapper, 0, 2)
    await flushPromises()

    // Same millisecond, different card: this is the user's own click on a file the drag never
    // touched, and a blanket time-window swallow would eat it.
    await cardNamed(wrapper, 'c.txt').get('.generic-open').trigger('click')
    const dialog = wrapper.findComponent(FilePreviewDialog)
    expect(dialog.props('visible')).toBe(true)
    expect(dialog.props('file')).toMatchObject({ id: 'f3' })
  })

  it('an ordinary click (no preceding drop) still opens the preview', async () => {
    const { wrapper } = mountView(gridDoc())
    await wrapper.findAll('.generic-open')[0].trigger('click')
    expect(wrapper.findComponent(FilePreviewDialog).props('visible')).toBe(true)
  })

  // The window a refresh can land in starts at the GRAB, not at the drop: an in-flight drag
  // holds an INDEX into the displayed order, so re-seeding underneath it makes that index name
  // a different file and the drop moves — and persists — the wrong one.
  it('a refetch landing MID-DRAG does not move the grab: the drop still moves the file that was grabbed', async () => {
    const { wrapper, docRef } = mountView(gridDoc())
    const cards = wrapper.findAll('.file-preview-card')
    await cards[0].get('.file-card-drag-handle').trigger('mousedown')
    await cards[0].trigger('dragstart') // grabbed a.txt, at index 0 of [a, b, c]

    // A refresh lands mid-drag in a DIFFERENT order and with an extra file: index 0 would now
    // name d.txt.
    docRef.value = {
      ...docRef.value,
      files: [file('f4', 'd.txt'), file('f1', 'a.txt'), file('f2', 'b.txt'), file('f3', 'c.txt')],
    } as unknown as DocumentDetail
    await wrapper.vm.$nextTick()
    expect(tileNames(wrapper), 'the order under the drag is frozen').toEqual([
      'a.txt',
      'b.txt',
      'c.txt',
    ])

    await cards[2].trigger('dragover')
    await cards[2].trigger('drop')
    await flushPromises()

    // a.txt — the file actually grabbed — moved to the end. Not d.txt, which the refresh put
    // at the grabbed index.
    expect(reorderFilesMock.mock.calls[0]).toEqual(['doc-src', ['f2', 'f3', 'f1']])
    // The held-back refresh is reconciled once the drag concludes: its new file appears.
    expect(tileNames(wrapper)).toEqual(['b.txt', 'c.txt', 'a.txt', 'd.txt'])
  })

  it('a drag CANCELLED after a mid-drag refetch applies that refresh and persists nothing', async () => {
    const { wrapper, docRef } = mountView(gridDoc())
    const cards = wrapper.findAll('.file-preview-card')
    await cards[0].get('.file-card-drag-handle').trigger('mousedown')
    await cards[0].trigger('dragstart')

    docRef.value = {
      ...docRef.value,
      files: [file('f3', 'c.txt'), file('f1', 'a.txt'), file('f2', 'b.txt')],
    } as unknown as DocumentDetail
    await wrapper.vm.$nextTick()
    expect(tileNames(wrapper)).toEqual(['a.txt', 'b.txt', 'c.txt'])

    // Escape / a release outside the grid: dragend without a drop. Nothing holds the order any
    // more, so the server's order must take over — a frozen order that never thaws would show
    // a stale grid until the next refetch.
    await cards[0].trigger('dragend')
    expect(tileNames(wrapper)).toEqual(['c.txt', 'a.txt', 'b.txt'])
    expect(reorderFilesMock).not.toHaveBeenCalled()
  })

  // A sibling mutation (rename, rotate, the upload poll) invalidates the document query, so a
  // refresh can land at any moment — including while a reorder POST is in flight, carrying the
  // PRE-reorder order because the server had not applied ours yet.
  it('a refetch landing MID-PERSIST neither clobbers the optimistic order nor re-opens the lock', async () => {
    let settle: (v: unknown) => void = () => {}
    reorderFilesMock.mockReturnValue(new Promise((resolve) => (settle = resolve)))
    const { wrapper, docRef } = mountView(gridDoc())

    await dragTile(wrapper, 0, 2)
    expect(tileNames(wrapper)).toEqual(['b.txt', 'c.txt', 'a.txt'])

    docRef.value = {
      ...docRef.value,
      files: [file('f1', 'a.txt'), file('f2', 'b.txt'), file('f3', 'c.txt'), file('f4', 'd.txt')],
    } as unknown as DocumentDetail
    await wrapper.vm.$nextTick()

    // The order under the in-flight request is untouched…
    expect(tileNames(wrapper), 'the optimistic order survives a mid-persist refresh').toEqual([
      'b.txt',
      'c.txt',
      'a.txt',
    ])
    // …and the refresh has NOT released the single-in-flight lock: no second drag can start.
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(0)
    const cards = wrapper.findAll('.file-preview-card')
    await cards[1].trigger('dragstart')
    await cards[0].trigger('dragover')
    await cards[0].trigger('drop')
    expect(reorderFilesMock).toHaveBeenCalledTimes(1)

    settle({ data: { status: 'ok' } })
    await flushPromises()
    // Reconciled on resolution: the persisted sequence, plus the file the refresh brought.
    expect(tileNames(wrapper)).toEqual(['b.txt', 'c.txt', 'a.txt', 'd.txt'])
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(4)
  })

  it('a rollback after a mid-persist refetch reverts to the FRESH server order, not the stale snapshot', async () => {
    let fail: (reason: unknown) => void = () => {}
    reorderFilesMock.mockReturnValue(new Promise((_resolve, reject) => (fail = reject)))
    const { wrapper, docRef } = mountView(gridDoc())

    await dragTile(wrapper, 0, 2)
    docRef.value = {
      ...docRef.value,
      files: [file('f1', 'a.txt'), file('f2', 'b.txt'), file('f3', 'c.txt'), file('f4', 'd.txt')],
    } as unknown as DocumentDetail
    await wrapper.vm.$nextTick()

    fail(new Error('500'))
    await flushPromises()
    // The pre-drag snapshot has no d.txt — restoring it would drop a file the server has.
    expect(tileNames(wrapper)).toEqual(['a.txt', 'b.txt', 'c.txt', 'd.txt'])
  })

  it('a handle press that never becomes a drag leaves the tile unarmed', async () => {
    const { wrapper } = mountView(gridDoc())
    const card = wrapper.findAll('.file-preview-card')[0]
    await card.get('.file-card-drag-handle').trigger('mousedown')
    expect((card.element as HTMLElement).draggable, 'armed by the handle press').toBe(true)

    // Released without a drag: the tile must stop being a drag source again.
    await card.trigger('mouseup')
    expect((card.element as HTMLElement).draggable).toBe(false)
  })

  it('a press released OUTSIDE the tile disarms it too', async () => {
    const { wrapper } = mountView(gridDoc())
    const card = wrapper.findAll('.file-preview-card')[0]
    await card.get('.file-card-drag-handle').trigger('mousedown')
    expect((card.element as HTMLElement).draggable, 'armed by the handle press').toBe(true)

    // Carried off the tile with the button down and released somewhere else entirely: the
    // card's own mouseup never fires, and its mouseleave was skipped because a button was held.
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
    expect((card.element as HTMLElement).draggable).toBe(false)
  })

  it('the pointer leaving the tile WITH the button held keeps it armed (the drag is starting)', async () => {
    const { wrapper } = mountView(gridDoc())
    const card = wrapper.findAll('.file-preview-card')[0]
    await card.get('.file-card-drag-handle').trigger('mousedown')

    // Chromium dispatches this boundary crossing before it turns the move into a dragstart, so
    // disarming here cancels every drag at its first pixel (it did: the e2e reorder stopped
    // working on both viewports until this case was excluded).
    await card.trigger('mouseleave', { buttons: 1 })
    expect((card.element as HTMLElement).draggable, 'still a drag source').toBe(true)

    // …and the drag that follows still reorders.
    await card.trigger('dragstart')
    await wrapper.findAll('.file-preview-card')[1].trigger('dragover')
    await wrapper.findAll('.file-preview-card')[1].trigger('drop')
    await flushPromises()
    expect(reorderFilesMock.mock.calls[0]).toEqual(['doc-src', ['f2', 'f1', 'f3']])
  })

  it('a refetch re-seeds the grid from the authoritative order', async () => {
    const { wrapper, docRef } = mountView(gridDoc())
    expect(tileNames(wrapper)).toEqual(['a.txt', 'b.txt', 'c.txt'])

    // What the query cache hands back after a successful persist (the backend returns the
    // files in their stored order) — this is what makes a reorder survive a reload.
    docRef.value = {
      ...docRef.value,
      files: [file('f3', 'c.txt'), file('f1', 'a.txt'), file('f2', 'b.txt')],
    } as unknown as DocumentDetail
    await wrapper.vm.$nextTick()
    expect(tileNames(wrapper)).toEqual(['c.txt', 'a.txt', 'b.txt'])
  })

  it('every tile branch (image / PDF / generic) carries a handle', () => {
    const { wrapper } = mountView(
      gridDoc(true, [
        file('f-img', 'a.jpg', 'image/jpeg'),
        file('f-pdf', 'b.pdf', 'application/pdf'),
        file('f-zip', 'c.zip', 'application/zip'),
      ]),
    )
    expect(wrapper.findAll('.file-preview-card').length).toBe(3)
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(3)
  })

  // Eligibility parity with the list (FileListTable:157).
  it('a read-only document has no handles and cannot reorder', async () => {
    const { wrapper } = mountView(gridDoc(false))
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(0)
    // …and the drop path is inert even if a drag event is delivered anyway.
    const cards = wrapper.findAll('.file-preview-card')
    await cards[2].trigger('dragover')
    await cards[2].trigger('drop')
    await flushPromises()
    expect(reorderFilesMock).not.toHaveBeenCalled()
    expect(tileNames(wrapper)).toEqual(['a.txt', 'b.txt', 'c.txt'])
  })

  // The threshold rule needs a hundred-plus tiles actually mounted, and a mount that size scales
  // with host load rather than with correctness: under a concurrent Maven build the two mounts
  // that used to share one test took 6.4–7.5 s against vitest's 5 s default (seven recurrences
  // between 2026-08-25 and 2026-09-01), then passed in isolation every time. Each mount gets its
  // own test and a budget that is a load tolerance, not a behaviour change.
  const LARGE_MOUNT_TIMEOUT = 30_000

  it('withdraws the handles above the 100-file threshold (the endpoint needs the complete order)', () => {
    const many = Array.from({ length: 101 }, (_, i) => file(`f${i}`, `f${i}.txt`))
    const { wrapper } = mountView(gridDoc(true, many))
    expect(wrapper.findAll('.file-preview-card').length, 'every tile still renders').toBe(101)
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(0)
  }, LARGE_MOUNT_TIMEOUT)

  it('keeps the handles at exactly 100 files (the threshold is exclusive)', () => {
    const hundred = Array.from({ length: 100 }, (_, i) => file(`f${i}`, `f${i}.txt`))
    const { wrapper } = mountView(gridDoc(true, hundred))
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(100)
  }, LARGE_MOUNT_TIMEOUT)

  // The handle is the only drag origin: a tile carries a preview click, rotation controls, PDF
  // controls and an action menu, and none of them may become a way to reorder the document.
  it('a drag that did NOT begin on the handle is inert', async () => {
    const { wrapper } = mountView(gridDoc())
    const cards = wrapper.findAll('.file-preview-card')

    // A mousedown anywhere else on the tile disarms it…
    await cards[0].get('.generic-open').trigger('mousedown')
    await cards[0].trigger('dragstart')
    await cards[2].trigger('dragover')
    await cards[2].trigger('drop')
    await flushPromises()

    expect(reorderFilesMock).not.toHaveBeenCalled()
    expect(tileNames(wrapper)).toEqual(['a.txt', 'b.txt', 'c.txt'])
  })

  it('a drop on the tile the drag started from changes nothing and persists nothing', async () => {
    const { wrapper } = mountView(gridDoc())
    await dragTile(wrapper, 1, 1)
    await flushPromises()
    expect(reorderFilesMock).not.toHaveBeenCalled()
    expect(tileNames(wrapper)).toEqual(['a.txt', 'b.txt', 'c.txt'])
  })
})

// #207 — the tile's filename label is ellipsized by CSS (.file-preview-label), so a long name
// is unreadable with no way to recover it. The grid duplicates that label per MIME branch
// (image / PDF / generic), so a single-branch assertion would let two branches stay silently
// unwired — all three are asserted, each against its OWN name.
describe('DocumentViewContent — full file name in a native title (#207)', () => {
  beforeEach(() => localStorage.clear())

  const LONG_IMG = 'scan-of-the-2026-quarterly-board-meeting-minutes-appendix-b-page-14.jpeg'
  const LONG_PDF = 'Q3-2026-consolidated-financial-statements-and-management-commentary-v7.pdf'
  const LONG_ZIP = 'archive-of-every-supporting-workpaper-referenced-by-the-year-end-audit.zip'

  it('every grid tile branch carries its own full file name in a native title', () => {
    const { wrapper } = mountView(
      makeDoc({
        relations: [],
        files: [
          { id: 'f-img', name: LONG_IMG, mimetype: 'image/jpeg', size: 1, version: 0, create_date: 0, creator: 'admin' },
          { id: 'f-pdf', name: LONG_PDF, mimetype: 'application/pdf', size: 1, version: 0, create_date: 0, creator: 'admin' },
          { id: 'f-zip', name: LONG_ZIP, mimetype: 'application/zip', size: 1, version: 0, create_date: 0, creator: 'admin' },
        ],
      } as unknown as Partial<DocumentDetail>),
    ) // grid is the default view mode
    const labels = wrapper.findAll('.file-preview-label')
    expect(labels.length).toBe(3)
    expect(labels.map((l) => l.attributes('title'))).toEqual([LONG_IMG, LONG_PDF, LONG_ZIP])
    // The rendered text is unchanged — truncation stays purely CSS.
    expect(labels.map((l) => l.text())).toEqual([LONG_IMG, LONG_PDF, LONG_ZIP])
  })
})

// #211 (second half): the list view has a transient column sort; the grid had none, so the two
// views of the same files could not be ordered the same way. The grid's sort is a CLONED
// PROJECTION over the manual order — it never persists, never touches POST /file/reorder, and
// suspends the drag handles for as long as it is active (a drop into a sorted view has no
// meaningful target index, and the endpoint needs the complete MANUAL order).
describe('DocumentViewContent — grid transient sort (#211)', () => {
  beforeEach(() => {
    localStorage.clear()
    reorderFilesMock.mockReset().mockResolvedValue({ data: { status: 'ok' } })
  })

  type Wrapper = ReturnType<typeof mountView>['wrapper']

  function file(id: string, name: string | null, size: number, create_date: number) {
    return { id, name, mimetype: 'text/plain', size, version: 0, create_date, creator: 'admin' }
  }

  // Deliberately cross-cutting: the manual order is neither name- nor size- nor date-ordered,
  // so each criterion produces a DIFFERENT sequence and no assertion can pass by accident.
  function sortDoc(writable = true) {
    return makeDoc({
      relations: [],
      writable,
      files: [
        file('f1', 'charlie.txt', 300, 20),
        file('f2', 'alpha.txt', 100, 30),
        file('f3', 'bravo.txt', 200, 10),
      ],
    } as unknown as Partial<DocumentDetail>)
  }

  function tileNames(wrapper: Wrapper) {
    return wrapper.findAll('.file-preview-grid .file-preview-label').map((l) => l.text())
  }

  function sortSelect(wrapper: Wrapper) {
    return wrapper.findAllComponents(Select).find((s) => s.classes().includes('grid-sort-select'))!
  }

  async function chooseSort(wrapper: Wrapper, value: string) {
    sortSelect(wrapper).vm.$emit('update:modelValue', value)
    await wrapper.vm.$nextTick()
  }

  const MANUAL = ['charlie.txt', 'alpha.txt', 'bravo.txt']

  it('offers the sort control only in grid mode, defaulting to the manual order', async () => {
    const { wrapper } = mountView(sortDoc()) // grid is the default view mode
    expect(sortSelect(wrapper)).toBeTruthy()
    expect(sortSelect(wrapper).props('modelValue')).toBe('manual')
    expect(tileNames(wrapper)).toEqual(MANUAL)
  })

  it('sorts by name in BOTH directions', async () => {
    const { wrapper } = mountView(sortDoc())
    await chooseSort(wrapper, 'name:asc')
    expect(tileNames(wrapper)).toEqual(['alpha.txt', 'bravo.txt', 'charlie.txt'])
    await chooseSort(wrapper, 'name:desc')
    expect(tileNames(wrapper)).toEqual(['charlie.txt', 'bravo.txt', 'alpha.txt'])
  })

  it('sorts by size in BOTH directions', async () => {
    const { wrapper } = mountView(sortDoc())
    await chooseSort(wrapper, 'size:asc')
    expect(tileNames(wrapper)).toEqual(['alpha.txt', 'bravo.txt', 'charlie.txt'])
    await chooseSort(wrapper, 'size:desc')
    expect(tileNames(wrapper)).toEqual(['charlie.txt', 'bravo.txt', 'alpha.txt'])
  })

  it('sorts by date in BOTH directions', async () => {
    const { wrapper } = mountView(sortDoc())
    await chooseSort(wrapper, 'create_date:asc')
    expect(tileNames(wrapper)).toEqual(['bravo.txt', 'charlie.txt', 'alpha.txt'])
    await chooseSort(wrapper, 'create_date:desc')
    expect(tileNames(wrapper)).toEqual(['alpha.txt', 'charlie.txt', 'bravo.txt'])
  })

  it('clearing the sort restores the MANUAL order', async () => {
    const { wrapper } = mountView(sortDoc())
    await chooseSort(wrapper, 'name:asc')
    expect(tileNames(wrapper)).not.toEqual(MANUAL)
    await chooseSort(wrapper, 'manual')
    expect(tileNames(wrapper)).toEqual(MANUAL)
  })

  it('withdraws the drag handles while a sort is active and returns them when it is cleared', async () => {
    const { wrapper } = mountView(sortDoc())
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(3)

    await chooseSort(wrapper, 'name:asc')
    // A drop into a sorted projection has no meaningful target index, and POST /file/reorder
    // needs the complete MANUAL order — so the affordance goes away rather than lying.
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(0)

    await chooseSort(wrapper, 'manual')
    expect(wrapper.findAll('.file-card-drag-handle').length).toBe(3)
  })

  it('is view-only: sorting never persists an order', async () => {
    const { wrapper } = mountView(sortDoc())
    await chooseSort(wrapper, 'name:asc')
    await chooseSort(wrapper, 'size:desc')
    await chooseSort(wrapper, 'manual')
    await flushPromises()
    expect(reorderFilesMock).not.toHaveBeenCalled()
  })

  it('does not mutate the manual order: a refetch arriving while sorted still re-seeds it, and clearing shows the SERVER order', async () => {
    const { wrapper, docRef } = mountView(sortDoc())
    await chooseSort(wrapper, 'name:asc')
    expect(tileNames(wrapper)).toEqual(['alpha.txt', 'bravo.txt', 'charlie.txt'])

    // Nothing is frozen (no drag, no persist in flight), so the refresh is authoritative and
    // lands on the MANUAL order underneath the projection.
    docRef.value = {
      ...docRef.value,
      files: [
        file('f3', 'bravo.txt', 200, 10),
        file('f1', 'charlie.txt', 300, 20),
        file('f2', 'alpha.txt', 100, 30),
      ],
    } as unknown as DocumentDetail
    await wrapper.vm.$nextTick()
    // The projection re-derives over the new manual order — same sorted sequence.
    expect(tileNames(wrapper)).toEqual(['alpha.txt', 'bravo.txt', 'charlie.txt'])

    await chooseSort(wrapper, 'manual')
    expect(tileNames(wrapper)).toEqual(['bravo.txt', 'charlie.txt', 'alpha.txt'])
  })

  it('a null-named file sorts LAST by name in both directions', async () => {
    const { wrapper } = mountView(
      makeDoc({
        relations: [],
        writable: true,
        files: [file('f1', 'bravo.txt', 1, 1), file('f2', null, 2, 2), file('f3', 'alpha.txt', 3, 3)],
      } as unknown as Partial<DocumentDetail>),
    )
    await chooseSort(wrapper, 'name:asc')
    expect(tileNames(wrapper)).toEqual(['alpha.txt', 'bravo.txt', 'ui.file_view.untitled'])
    await chooseSort(wrapper, 'name:desc')
    expect(tileNames(wrapper)).toEqual(['bravo.txt', 'alpha.txt', 'ui.file_view.untitled'])
  })
})

// #300 / TEEDY-139 — where the per-file access count may live in the GRID.
//
// The badge broke main by rendering INSIDE `.file-preview-label`, whose textContent is the file
// name that nullname.spec and file-panel.spec read with toHaveText ("Untitled file" arrived as
// "Untitled file0"). It is now a sibling, and a zero count renders nothing at all.
describe('DocumentViewContent — grid access count placement (#300)', () => {
  function mountWithCounts(doc: DocumentDetail, counts: Record<string, number>) {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return mount(DocumentViewContent, {
      global: {
        plugins: [PrimeVue, [VueQueryPlugin, { queryClient }]],
        provide: {
          [DocumentKey as symbol]: ref(doc),
          [AccessCountsKey as symbol]: ref({
            count: 0,
            files: Object.entries(counts).map(([id, count]) => ({ id, count })),
          }),
        },
        stubs: {
          FileUpload: true,
          CameraCaptureButton: true,
          UploadProgressList: true,
          FileVersionsDialog: true,
          PdfViewer: PdfViewerStub,
          EmptyState: true,
          RouterLink: { template: '<a><slot /></a>' },
        },
        directives: { tooltip: {} },
      },
    })
  }

  const gridDocument = () =>
    makeDoc({
      relations: [],
      files: [
        { id: 'f-seen', name: 'seen.txt', mimetype: 'text/plain', size: 1, version: 0, create_date: 0, creator: 'admin' },
        { id: 'f-unseen', name: 'unseen.txt', mimetype: 'text/plain', size: 1, version: 0, create_date: 0, creator: 'admin' },
      ],
    } as unknown as Partial<DocumentDetail>)

  it('keeps the tile label holding the file name and nothing else', () => {
    const wrapper = mountWithCounts(gridDocument(), { 'f-seen': 5, 'f-unseen': 0 })
    expect(wrapper.findAll('.file-preview-label').map((l) => l.text())).toEqual([
      'seen.txt',
      'unseen.txt',
    ])
  })

  it('renders the badge beside the label, once, only for the accessed file', () => {
    const wrapper = mountWithCounts(gridDocument(), { 'f-seen': 5, 'f-unseen': 0 })
    const badges = wrapper.findAll('.access-count')
    expect(badges).toHaveLength(1)
    expect(badges[0].find('.access-count-value').text()).toBe('5')
    // A sibling of the label, not a descendant of it.
    expect(wrapper.findAll('.file-preview-label .access-count')).toHaveLength(0)
  })
})
