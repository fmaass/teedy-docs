import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { DocumentKey } from './documentKey'
import type { DocumentDetail } from '../../api/document'

// #296 — the reader-side ordering of "Related documents". The unit under test is
// DocumentViewContent's wiring of `utils/relationSort`: ONE control drives BOTH direction
// groups, it appears only where it has something to reorder, and the chosen order is a
// SESSION value that follows the reader from document to document (never persisted across a
// reload). `sortRelations` itself is pinned separately in utils/relationSort.spec.ts.
//
// A fresh pinia per test is both the auth store the view needs for its file-view-mode key and
// the session boundary these assertions rely on: within one test the store survives a remount,
// which is exactly the "same session, another document" case.
beforeEach(() => setActivePinia(createPinia()))

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
  uploadFile: vi.fn(),
  reorderFiles: vi.fn(),
  getFileList: vi.fn(),
  moveFile: vi.fn(),
}))
vi.mock('../../components/PdfViewer.vue', () => ({
  default: { name: 'PdfViewer', template: '<div />' },
}))
// `t` is stubbed to the identity, so a rendered label IS its message key — which is what lets
// the option/aria-label assertions below name the exact keys the locale gate must carry.
vi.mock('vue-i18n', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (k: string) => k }),
}))
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))
vi.mock('../../composables/usePreviewQueue', () => ({
  usePreviewQueue: () => ({
    enqueue: () => Promise.resolve(new Blob(['x'])),
    cancel: () => {},
    reprioritize: () => {},
  }),
}))
vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:test', revokeObjectURL: () => {} })

import Select from 'primevue/select'
import DocumentViewContent from './DocumentViewContent.vue'

type Relation = { id: string; title: string; source: boolean }

function rel(title: string, source: boolean): Relation {
  return { id: `${source ? 'out' : 'in'}-${title}`, title, source }
}

function makeDoc(relations: Relation[], id = 'doc-1'): DocumentDetail {
  return {
    id,
    title: 'Source Doc',
    language: 'eng',
    writable: true,
    description: '',
    tags: [],
    relations,
    metadata: [],
    files: [],
  } as unknown as DocumentDetail
}

function mountView(doc: DocumentDetail) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(DocumentViewContent, {
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
}

type Wrapper = ReturnType<typeof mountView>

function sortSelect(wrapper: Wrapper) {
  return wrapper.findAllComponents(Select).find((s) => s.classes().includes('relation-sort-select'))
}

function groupTitles(wrapper: Wrapper, labelKey: string) {
  const group = wrapper.findAll('.relation-group').find((g) => g.text().includes(labelKey))
  return group ? group.findAll('.relation-link').map((l) => l.text()) : []
}

const outgoing = (wrapper: Wrapper) => groupTitles(wrapper, 'ui.relations.links_to')
const incoming = (wrapper: Wrapper) => groupTitles(wrapper, 'ui.relations.linked_from')

async function chooseSort(wrapper: Wrapper, value: string) {
  sortSelect(wrapper)!.vm.$emit('update:modelValue', value)
  await wrapper.vm.$nextTick()
}

// Both fixtures are authored in an order that is NEITHER ascending nor descending, so an
// assertion cannot pass on an unsorted list by coincidence — with only two entries the authored
// order IS one of the two answers, which is why the incoming group carries three. They are also
// numeric-aware: a code-point sort puts "Invoice 10" before "Invoice 2".
const OUT = [rel('Invoice 10', true), rel('Alpha', true), rel('Invoice 2', true)]
const IN = [rel('Mike', false), rel('Zulu', false), rel('Bravo', false)]

describe('DocumentViewContent — related-document sort (#296)', () => {
  it('does NOT render the control when neither direction has more than one relation', () => {
    // The relations block itself renders for every writable document — including one with no
    // relations at all, which is a CAPTURED visual surface. A control with nothing to reorder
    // would be pure noise there.
    expect(sortSelect(mountView(makeDoc([])))).toBeUndefined()
    expect(sortSelect(mountView(makeDoc([rel('Only', true)])))).toBeUndefined()
    expect(sortSelect(mountView(makeDoc([rel('Only', false)])))).toBeUndefined()
    expect(
      sortSelect(mountView(makeDoc([rel('One out', true), rel('One in', false)]))),
    ).toBeUndefined()
  })

  it('renders ONE control as soon as either direction has two relations', () => {
    const outOnly = mountView(makeDoc([rel('B', true), rel('A', true)]))
    expect(sortSelect(outOnly)).toBeDefined()
    expect(outOnly.findAllComponents(Select).filter((s) => s.classes().includes('relation-sort-select')).length).toBe(1)

    // The incoming side alone must arm it too — the condition is an OR, not "outgoing only".
    const inOnly = mountView(makeDoc([rel('B', false), rel('A', false)]))
    expect(sortSelect(inOnly)).toBeDefined()
    expect(inOnly.findAllComponents(Select).filter((s) => s.classes().includes('relation-sort-select')).length).toBe(1)
  })

  it('leaves the SERVER order untouched until the reader asks for something else', () => {
    // The backend orders by DOC_TITLE_C in the DATABASE's collation, which can disagree with the
    // browser's on case and accents. Re-collating on mount would silently overrule it, so nothing
    // is sorted here at all: the arrays render exactly as they arrived, in their authored order.
    const wrapper = mountView(makeDoc([...OUT, ...IN]))
    expect(sortSelect(wrapper)!.props('modelValue')).toBe('server')
    expect(outgoing(wrapper)).toEqual(['Invoice 10', 'Alpha', 'Invoice 2'])
    expect(incoming(wrapper)).toEqual(['Mike', 'Zulu', 'Bravo'])
  })

  it('applies ascending to BOTH groups only once the reader chooses it', async () => {
    const wrapper = mountView(makeDoc([...OUT, ...IN]))
    await chooseSort(wrapper, 'asc')
    expect(outgoing(wrapper)).toEqual(['Alpha', 'Invoice 2', 'Invoice 10'])
    expect(incoming(wrapper)).toEqual(['Bravo', 'Mike', 'Zulu'])
  })

  it('returns to the untouched server order when the reader picks it back', async () => {
    // A placeholder-only neutral state would be a one-way door: this is why the neutral state is a
    // real option, exactly as the file grid's "manual order" is.
    const wrapper = mountView(makeDoc([...OUT, ...IN]))
    await chooseSort(wrapper, 'desc')
    expect(outgoing(wrapper)).not.toEqual(['Invoice 10', 'Alpha', 'Invoice 2'])
    await chooseSort(wrapper, 'server')
    expect(outgoing(wrapper)).toEqual(['Invoice 10', 'Alpha', 'Invoice 2'])
    expect(incoming(wrapper)).toEqual(['Mike', 'Zulu', 'Bravo'])
  })

  it('reorders BOTH direction groups from the single control', () => {
    const wrapper = mountView(makeDoc([...OUT, ...IN]))
    return chooseSort(wrapper, 'desc').then(() => {
      expect(outgoing(wrapper)).toEqual(['Invoice 10', 'Invoice 2', 'Alpha'])
      expect(incoming(wrapper)).toEqual(['Zulu', 'Mike', 'Bravo'])
    })
  })

  it('offers the neutral option plus the two title options, and names the control for screen readers', () => {
    const wrapper = mountView(makeDoc([...OUT, ...IN]))
    const select = sortSelect(wrapper)!
    expect(select.props('options')).toEqual([
      { value: 'server', label: 'ui.relations.sort_default' },
      { value: 'asc', label: 'ui.relations.sort_title_asc' },
      { value: 'desc', label: 'ui.relations.sort_title_desc' },
    ])
    // PrimeVue puts the aria-label on the focusable combobox, not on the Select's root wrapper —
    // asserting it there is what proves a screen-reader user actually hears the control named.
    expect(wrapper.find('.relation-sort-select [role="combobox"]').attributes('aria-label')).toBe(
      'ui.relations.sort_label',
    )
  })

  it('carries the choice to the NEXT document in the same session (a remount keeps it)', async () => {
    // The reporter asked for one setting, not a per-document one: having to re-pick the order
    // on every document is the complaint, not the feature.
    const first = mountView(makeDoc([...OUT, ...IN]))
    await chooseSort(first, 'desc')
    first.unmount()

    const second = mountView(makeDoc([rel('Anna', true), rel('Zoe', true)], 'doc-2'))
    expect(sortSelect(second)!.props('modelValue')).toBe('desc')
    expect(outgoing(second)).toEqual(['Zoe', 'Anna'])
  })
})
