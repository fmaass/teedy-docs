import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { DocumentKey } from './documentKey'
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
  reorderFiles: vi.fn(() => Promise.resolve({ data: { status: 'ok' } })),
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
  },
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
