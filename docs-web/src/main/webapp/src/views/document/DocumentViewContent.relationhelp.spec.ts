import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { DocumentKey } from './documentKey'
import type { DocumentDetail } from '../../api/document'

// #309 — the "add an outgoing relation" field on the document Content tab. Two defects, one row:
//
//  1. The AutoComplete carried no `fluid`, so PrimeVue left its inner `<input>` at the intrinsic
//     width of a bare text input while `.relation-add-autocomplete { flex: 1 }` stretched only the
//     wrapper around it. A picked document title longer than that intrinsic width is clipped. The
//     mechanism is a class, not a number: `fluid` puts `p-autocomplete-fluid` on the root and
//     `p-inputtext-fluid` (width: 100%) on the input, which is what these assertions pin — the
//     geometric proof at a real viewport lives in e2e/relations.spec.ts, since jsdom has no layout.
//  2. The field feeds `GET /document/list`, i.e. the SAME `DocumentSearchCriteriaUtil` parser the
//     main search bar uses, so `tag:`, `by:`, `after:` … all work here — undocumented. The help
//     affordance reuses the search bar's own popover body (SearchHelpContent) so the operator copy
//     has one source and no new locale keys were needed.
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
// `t` is the identity, so a rendered label IS its message key — which is what lets the assertions
// below name the exact `document.search_help.*` keys the affordance must reuse. The operator TOKENS
// are literal in the source and are not translated, so `tag:invoice` renders verbatim either way.
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

import DocumentViewContent from './DocumentViewContent.vue'

// The popover is opened imperatively (`ref.toggle(event)`), so the stub records the call instead of
// rendering an overlay: PrimeVue teleports the real one and jsdom would need a full overlay cycle to
// see it. The slot renders unconditionally here so the CONTENT is assertable in the same mount.
const toggleHelp = vi.fn()
const PopoverStub = {
  name: 'Popover',
  template: '<div class="popover-stub"><slot /></div>',
  methods: {
    toggle(event: Event) {
      toggleHelp(event)
    },
  },
}

function makeDoc(writable = true): DocumentDetail {
  return {
    id: 'doc-1',
    title: 'Source Doc',
    language: 'eng',
    writable,
    description: '',
    tags: [],
    relations: [],
    metadata: [],
    files: [],
  } as unknown as DocumentDetail
}

function mountView(doc: DocumentDetail = makeDoc()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(DocumentViewContent, {
    attachTo: document.body,
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
        Popover: PopoverStub,
      },
      directives: { tooltip: {} },
    },
  })
}

type Wrapper = ReturnType<typeof mountView>

const relationAutocomplete = (wrapper: Wrapper) =>
  wrapper.find('.relation-add .p-autocomplete.relation-add-autocomplete')
const helpButton = (wrapper: Wrapper) => wrapper.find('[data-testid="relation-search-help"]')

beforeEach(() => toggleHelp.mockClear())

describe('DocumentViewContent — relation add field (#309)', () => {
  it('stretches the relation input to its wrapper', () => {
    const wrapper = mountView()
    const autocomplete = relationAutocomplete(wrapper)
    expect(autocomplete.exists()).toBe(true)
    // Root: `fluid` is what turns the inline-flex wrapper into a full-width flex row.
    expect(autocomplete.classes()).toContain('p-autocomplete-fluid')
    // Input: `p-inputtext-fluid` is the `width: 100%` rule. Without it the input keeps the
    // intrinsic width of a bare `<input>` however wide the wrapper grows — the reported clip.
    expect(autocomplete.find('input').classes()).toContain('p-inputtext-fluid')
  })

  it('offers a keyboard-reachable help affordance next to the input', () => {
    const wrapper = mountView()
    const button = helpButton(wrapper)
    expect(button.exists()).toBe(true)
    // A real <button>, inside the add row, labelled for screen readers by the shared help title.
    expect(button.element.tagName).toBe('BUTTON')
    expect(wrapper.find('.relation-add').element.contains(button.element)).toBe(true)
    expect(button.attributes('aria-label')).toBe('document.search_help.title')
  })

  it('opens the help popover from that button', async () => {
    const wrapper = mountView()
    await helpButton(wrapper).trigger('click')
    expect(toggleHelp).toHaveBeenCalledTimes(1)
  })

  it('names the search operators in the help body, reusing the search bar copy', () => {
    const wrapper = mountView()
    const help = wrapper.find('.relation-add-help .search-help')
    expect(help.exists()).toBe(true)
    const text = help.text()
    // The literal operator tokens the backend parses…
    expect(text).toContain('tag:invoice')
    expect(text).toContain('by:alice')
    // …and the existing keys, proving the copy is the search bar's and not a new set of strings.
    expect(text).toContain('document.search_help.title')
    expect(text).toContain('document.search_help.operators_intro')
    expect(text).toContain('document.search_help.op_tag')
  })

  it('renders neither the field nor its help on a read-only document', () => {
    // The whole add row is writable-only; the help affordance must not outlive it.
    const wrapper = mountView(makeDoc(false))
    expect(wrapper.find('.relation-add').exists()).toBe(false)
    expect(helpButton(wrapper).exists()).toBe(false)
  })
})
