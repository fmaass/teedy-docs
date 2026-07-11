import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import PrimeVue from 'primevue/config'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { DocumentKey } from './documentKey'
import type { DocumentDetail } from '../../api/document'

// #36: the "Related documents" section is the unit under test — direction grouping,
// full-surviving-list mutation composition, per-direction controls, and cross-document
// cache invalidation. The API modules and the confirm dialog are dependencies (mocked);
// buildRelationsParams stays REAL so the asserted payloads are the shipped wire format.

const listDocumentsMock = vi.fn()
const updateDocumentMock = vi.fn()
vi.mock('../../api/document', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/document')>()),
  listDocuments: (...a: unknown[]) => listDocumentsMock(...a),
  updateDocument: (...a: unknown[]) => updateDocumentMock(...a),
}))
vi.mock('../../api/file', () => ({
  getFileUrl: (id: string) => `/api/file/${id}/data`,
  deleteFile: vi.fn(),
  renameFile: vi.fn(),
  uploadFile: vi.fn(),
}))
// pdfjs-dist (imported at module level by PdfViewer) needs DOMMatrix, which jsdom
// lacks — replace the whole module; the viewer is irrelevant to the relations unit.
vi.mock('../../components/PdfViewer.vue', () => ({
  default: { name: 'PdfViewer', template: '<div />' },
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))
// The danger-confirm dialog is a dependency; accepting immediately exercises the REAL
// accept callback (the id-list composition under test) without driving PrimeVue overlays.
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({
    confirmDanger: (opts: { accept?: () => void }) => opts.accept?.(),
  }),
}))

import DocumentViewContent from './DocumentViewContent.vue'

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

function mountView(doc: DocumentDetail) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
  const wrapper = mount(DocumentViewContent, {
    global: {
      plugins: [PrimeVue, [VueQueryPlugin, { queryClient }]],
      provide: { [DocumentKey as symbol]: ref(doc) },
      stubs: {
        FileUpload: true,
        CameraCaptureButton: true,
        UploadProgressList: true,
        FileVersionsDialog: true,
        PdfViewer: true,
        EmptyState: true,
        RouterLink: { template: '<a><slot /></a>' },
      },
      directives: { tooltip: {} },
    },
  })
  return { wrapper, invalidateSpy }
}

type ViewVm = {
  selectedRelationTarget: { id: string; title: string } | null
  handleAddRelation: () => Promise<void>
}

describe('DocumentViewContent — related documents (#36)', () => {
  beforeEach(() => {
    listDocumentsMock.mockReset()
    updateDocumentMock.mockReset().mockResolvedValue({ data: { id: 'doc-src' } })
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

  it('incoming rows carry NO remove control; outgoing rows do (writable doc)', () => {
    const { wrapper } = mountView(makeDoc())
    const linksTo = wrapper.findAll('.relation-group').find((g) => g.text().includes('ui.relations.links_to'))!
    const linkedFrom = wrapper.findAll('.relation-group').find((g) => g.text().includes('ui.relations.linked_from'))!
    expect(linksTo.findAll('button').length).toBe(1)
    expect(linkedFrom.findAll('button').length).toBe(0)
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
    await dropRow.find('button').trigger('click')
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
    await wrapper.find('.relation-row button').trigger('click')
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
    await wrapper.find('.relation-row button').trigger('click')
    await flushPromises()
    const keys = invalidateSpy.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey)
    expect(keys).toContainEqual(['document', 'doc-src'])
    expect(keys).toContainEqual(['document', 'rel-gone'])
  })
})
