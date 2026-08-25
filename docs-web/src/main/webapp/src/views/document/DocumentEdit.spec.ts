import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'
import TagPicker from '../../components/TagPicker.vue'
import type { Tag } from '../../api/tag'

// The document-tag picker (#14 type-to-filter, #23 colored wrapping chips) is the unit
// under test. We mount DocumentEdit in create mode with the REAL shared TagPicker (#182)
// off a seeded tag list, so the assertions exercise the shipped chip slot and the form's
// binding to it rather than a re-derived copy. The option mapping itself now lives in
// TagPicker and is asserted in TagPicker.spec.ts.

const TAGS: Tag[] = [
  { id: 'tag-red', name: 'Invoice', color: '#d32f2f', parent: null },
  { id: 'tag-green', name: 'Receipt', color: '#2e7d32', parent: null },
  { id: 'tag-blue', name: 'Contract', color: '#1565c0', parent: null },
]

// Mock the API modules DocumentEdit / the tagFilter store hit on mount. listTags feeds the
// store's allTags (source of the tag colors); the rest keep create-mode init from erroring.
const tagApiMock = vi.hoisted(() => ({
  listTags: vi.fn(),
  getTagStats: vi.fn(),
  getTagFacets: vi.fn(),
  getTagCoOccurrence: vi.fn(),
  isMetaTag: (name: string) => name.startsWith('_'),
}))
vi.mock('../../api/tag', () => tagApiMock)

vi.mock('../../api/document', () => ({
  getDocument: vi.fn(),
  createDocument: vi.fn(),
  updateDocument: vi.fn(),
  importEml: vi.fn(),
  // #295: the title field's typeahead queries the document list; unmocked it would hit axios.
  listDocuments: vi.fn(),
}))
vi.mock('../../api/metadata', () => ({
  listMetadata: vi.fn().mockResolvedValue({ data: { metadata: [] } }),
}))
vi.mock('../../api/vocabulary', () => ({
  getVocabulary: vi.fn().mockResolvedValue({ data: { entries: [] } }),
}))
vi.mock('../../api/app', () => ({
  getAppInfo: vi.fn().mockResolvedValue({ data: { default_language: 'eng' } }),
}))
vi.mock('../../api/file', () => ({
  uploadFile: vi.fn(),
  deleteFile: vi.fn(),
  getFileUrl: (id: string) => `/api/file/${id}/data`,
}))

// PrimeVue overlays probe window.matchMedia, and Textarea autoResize uses
// ResizeObserver — neither is provided by jsdom. Stub both for this environment.
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

import DocumentEdit from './DocumentEdit.vue'
import { getDocument, listDocuments, createDocument, updateDocument } from '../../api/document'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: { template: '<div/>' } },
    { path: '/document/:id', name: 'document-view', component: { template: '<div/>' } },
  ],
})

async function mountEdit(props: { id?: string } = {}) {
  setActivePinia(createPinia())
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router.push('/')
  await router.isReady()
  const wrapper = mount(DocumentEdit, {
    props,
    global: {
      plugins: [i18n, router, PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
    },
  })
  await flushPromises()
  return wrapper
}

// Minimal document-detail payload for edit-mode hydration; `relations` varies per test.
function docDetail(relations: Array<{ id: string; title: string; source: boolean }>) {
  return {
    data: {
      id: 'doc-1',
      title: 'Edited Doc',
      description: '',
      subject: '',
      identifier: '',
      publisher: '',
      format: '',
      source: '',
      type: '',
      coverage: '',
      rights: '',
      language: 'eng',
      create_date: 1700000000000,
      tags: [],
      relations,
      metadata: [],
      files: [],
    },
  }
}

// buildDocParams is the REAL save-payload builder the update/create flows submit;
// script-setup bindings are reachable on wrapper.vm in dev mode (as the tag tests above rely on).
type EditVm = { buildDocParams: () => URLSearchParams }

describe('DocumentEdit — tag picker (#14 filter, #23 colored chips)', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: [] } })
    tagApiMock.getTagFacets.mockReset().mockResolvedValue({ data: { tags: [] } })
    tagApiMock.getTagCoOccurrence.mockReset().mockResolvedValue({ data: { pairs: [] } })
  })

  it('#182: hands the shared TagPicker the RAW tag list, colors intact', async () => {
    // The {label,value,color} mapping moved into TagPicker (#182) so both tag-add
    // surfaces derive it identically; what the form still owns is the binding. Passing
    // a pre-mapped list here would strip the colors TagPicker needs for its chips —
    // the derivation itself is asserted in TagPicker.spec.ts.
    const wrapper = await mountEdit()
    expect(wrapper.findComponent(TagPicker).props('tags')).toEqual(TAGS)
  })

  it('#182: forwards id="edit-tags" so the label association and e2e selectors survive', async () => {
    // `<label for="edit-tags">` plus six e2e call sites across five specs resolve this
    // id on the picker's root element; an extraction that dropped it would break all of
    // them without failing a unit test.
    const wrapper = await mountEdit()
    expect(wrapper.findComponent(TagPicker).props('id')).toBe('edit-tags')
    expect(wrapper.find('#edit-tags').exists()).toBe(true)
    expect(wrapper.find('label[for="edit-tags"]').exists()).toBe(true)
  })

  it('#182: leaves the edit form multi-tag (no bulk-style selection cap)', async () => {
    const wrapper = await mountEdit()
    expect(wrapper.findComponent(TagPicker).props('selectionLimit')).toBeUndefined()
  })

  it('#14/#286: the tag search box is the picker\'s own, with a clear labelled from the shared key', async () => {
    // PrimeVue's built-in filter is off because its text is unreachable (#286), so the
    // form must hand the picker the label its clear (×) is announced with — the same
    // `document.search_clear` the main search bar and the quick menu use. Wired here
    // rather than inside the picker, which owns no locale keys.
    const wrapper = await mountEdit()
    const multiselect = wrapper.findComponent({ name: 'MultiSelect' })
    expect(multiselect.exists()).toBe(true)
    expect(multiselect.props('filter')).toBe(false)
    expect(wrapper.findComponent(TagPicker).props('clearFilterLabel')).toBe(en.document.search_clear)
  })

  it('#23: the MultiSelect carries the wrap-enabling class (chips wrap, do not clip)', async () => {
    // The scoped :deep(.p-multiselect-label){flex-wrap:wrap} rule is keyed on this class;
    // jsdom cannot compute the scoped style, so guard the hook the wrap rule targets.
    const wrapper = await mountEdit()
    const multiselect = wrapper.findComponent({ name: 'MultiSelect' })
    expect(multiselect.classes()).toContain('tag-multiselect')
  })

  it('#23: selected tags render as colored TagBadge chips', async () => {
    const wrapper = await mountEdit()
    // Select two tags the way v-model would; the chip slot must colour them from the tag map.
    ;(wrapper.vm as unknown as { form: { tags: string[] } }).form.tags = ['tag-red', 'tag-blue']
    await flushPromises()
    const badges = wrapper.findAllComponents({ name: 'TagBadge' })
    const rendered = badges.map((b) => ({ name: b.props('name'), color: b.props('color') }))
    expect(rendered).toEqual([
      { name: 'Invoice', color: '#d32f2f' },
      { name: 'Contract', color: '#1565c0' },
    ])
  })

  it('#23: a selected tag absent from tagMap still renders a visible, removable chip', async () => {
    const wrapper = await mountEdit()
    // 'ghost' is not in TAGS/tagMap — e.g. a tag on the doc not in the loaded list, or a
    // timing gap before tagMap populates. It must NOT vanish silently.
    ;(wrapper.vm as unknown as { form: { tags: string[] } }).form.tags = ['tag-red', 'ghost']
    await flushPromises()
    // Two chips render: the known coloured one and a visible fallback for the unknown id.
    const badges = wrapper.findAllComponents({ name: 'TagBadge' })
    expect(badges.length).toBe(2)
    // The fallback chip shows the raw id (best available label) and is removable.
    const fallback = badges[1]
    expect(fallback.props('name')).toBe('ghost')
    // Removing the fallback chip drops exactly that id from the selection.
    await fallback.find('.tag-remove-btn').trigger('click')
    await flushPromises()
    expect((wrapper.vm as unknown as { form: { tags: string[] } }).form.tags).toEqual(['tag-red'])
  })

  it('#23: clicking a chip remove button deselects that tag', async () => {
    const wrapper = await mountEdit()
    ;(wrapper.vm as unknown as { form: { tags: string[] } }).form.tags = ['tag-red', 'tag-blue']
    await flushPromises()
    // The first chip's remove button must drop only that tag from the selection.
    const firstBadge = wrapper.findAllComponents({ name: 'TagBadge' })[0]
    await firstBadge.find('.tag-remove-btn').trigger('click')
    await flushPromises()
    expect((wrapper.vm as unknown as { form: { tags: string[] } }).form.tags).toEqual(['tag-blue'])
  })
})

describe('DocumentEdit — relations save payload (#36 spurious-reverse fix)', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: [] } })
    tagApiMock.getTagFacets.mockReset().mockResolvedValue({ data: { tags: [] } })
    tagApiMock.getTagCoOccurrence.mockReset().mockResolvedValue({ data: { pairs: [] } })
    vi.mocked(getDocument).mockReset()
  })

  it('does NOT re-send an incoming relation as `relations` (would mint a spurious reverse relation)', async () => {
    // The document has one outgoing (source=true) and one incoming (source=false) relation.
    // POST /document/:id `relations` params are reconciled as OUTGOING only, so re-sending
    // the incoming id would CREATE a new reverse relation on every save.
    vi.mocked(getDocument).mockResolvedValue(
      docDetail([
        { id: 'rel-out', title: 'Out', source: true },
        { id: 'rel-in', title: 'In', source: false },
      ]) as never,
    )
    const wrapper = await mountEdit({ id: 'doc-1' })
    const params = (wrapper.vm as unknown as EditVm).buildDocParams()
    expect(params.getAll('relations')).toEqual(['rel-out'])
    expect(params.get('relations_reset')).toBeNull()
  })

  it('sends relations_reset=true (and no relations) when the document has no outgoing relations on edit', async () => {
    // Only an incoming relation: zero outgoing survive, so the explicit clear sentinel is
    // sent (an omitted `relations` param would silently preserve a stale outgoing set).
    vi.mocked(getDocument).mockResolvedValue(
      docDetail([{ id: 'rel-in', title: 'In', source: false }]) as never,
    )
    const wrapper = await mountEdit({ id: 'doc-1' })
    const params = (wrapper.vm as unknown as EditVm).buildDocParams()
    expect(params.getAll('relations')).toEqual([])
    expect(params.get('relations_reset')).toBe('true')
  })

  it('a create (no id) never emits the relations_reset sentinel', async () => {
    const wrapper = await mountEdit()
    const params = (wrapper.vm as unknown as EditVm).buildDocParams()
    expect(params.getAll('relations')).toEqual([])
    expect(params.get('relations_reset')).toBeNull()
  })
})

describe('DocumentEdit — title proposals while typing (#295)', () => {
  // Restored from the AngularJS app's `getTitleTypeahead` (lost in the Vue rewrite): typing a
  // title prefix proposes existing document titles so the same document is not filed twice.
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: [] } })
    tagApiMock.getTagFacets.mockReset().mockResolvedValue({ data: { tags: [] } })
    tagApiMock.getTagCoOccurrence.mockReset().mockResolvedValue({ data: { pairs: [] } })
    vi.mocked(getDocument).mockReset()
    vi.mocked(listDocuments).mockReset()
  })

  // The typeahead is debounced, so the query is not in flight when setValue() returns. Poll
  // instead of sleeping a fixed span: the assertion below is what decides pass/fail.
  async function waitForTypeahead() {
    for (let i = 0; i < 100 && vi.mocked(listDocuments).mock.calls.length === 0; i++) {
      await new Promise((resolve) => setTimeout(resolve, 20))
    }
    await flushPromises()
  }

  function titlePayload(titles: string[]) {
    return { data: { documents: titles.map((title, i) => ({ id: `doc-${i}`, title })) } }
  }

  it('queries the old typeahead shape with the typed prefix and offers the DISTINCT titles', async () => {
    vi.mocked(listDocuments).mockResolvedValue(
      titlePayload(['Invoice 2024', 'Invoice 2025', 'Invoice 2024']) as never,
    )
    const wrapper = await mountEdit()
    const input = wrapper.find('input#edit-title')
    expect(input.exists()).toBe(true)
    await input.trigger('focus')
    await input.setValue('Inv')
    await waitForTypeahead()

    // Same request shape the AngularJS controller used: 5 rows, title-sorted ascending.
    expect(vi.mocked(listDocuments)).toHaveBeenCalledWith({
      search: 'Inv',
      limit: 5,
      sort_column: 1,
      asc: true,
    })
    const auto = wrapper.findComponent({ name: 'AutoComplete' })
    expect(auto.exists()).toBe(true)
    // Two documents can share a title; the proposal list must not repeat it.
    expect(auto.props('suggestions')).toEqual(['Invoice 2024', 'Invoice 2025'])
    const rendered = Array.from(document.body.querySelectorAll('.p-autocomplete-option')).map(
      (option) => option.textContent?.trim(),
    )
    expect(rendered).toEqual(['Invoice 2024', 'Invoice 2025'])
  })

  it('keeps the typed value on input#edit-title (inputId, not an id on the root div)', async () => {
    // `<label for="edit-title">` and ten e2e specs resolve this id; PrimeVue puts a plain
    // `id` on the AutoComplete's wrapper div, which is not fillable and not labellable.
    vi.mocked(listDocuments).mockResolvedValue(titlePayload([]) as never)
    const wrapper = await mountEdit()
    const byId = wrapper.element.querySelector('#edit-title')
    expect(byId?.tagName).toBe('INPUT')
    expect(wrapper.find('label[for="edit-title"]').exists()).toBe(true)
    // The new-document form has always opened with the caret in the title; the attribute
    // has to reach the inner input, since the AutoComplete's own wrapper is not focusable.
    expect(byId?.hasAttribute('autofocus')).toBe(true)

    await wrapper.find('input#edit-title').setValue('Quarterly report')
    // A freely typed title (no proposal chosen) still reaches the save payload.
    expect((wrapper.vm as unknown as EditVm).buildDocParams().get('title')).toBe(
      'Quarterly report',
    )
  })

  it('fills the title only when a proposal is chosen — it does not open that document', async () => {
    vi.mocked(listDocuments).mockResolvedValue(titlePayload(['Invoice 2024']) as never)
    const wrapper = await mountEdit()
    const input = wrapper.find('input#edit-title')
    await input.trigger('focus')
    await input.setValue('Inv')
    await waitForTypeahead()

    const option = document.body.querySelector('.p-autocomplete-option') as HTMLElement
    expect(option).toBeTruthy()
    option.click()
    await flushPromises()

    expect((wrapper.vm as unknown as { form: { title: string } }).form.title).toBe('Invoice 2024')
    expect((wrapper.find('input#edit-title').element as HTMLInputElement).value).toBe(
      'Invoice 2024',
    )
    // The ask was "keep the database clean", not navigation: the form stays put.
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('drops a response that lands after the field was left (no dropdown over the next field)', async () => {
    // The search is debounced, so a user who types a title and moves straight on to the
    // description has a request still in flight. PrimeVue opens the overlay whenever the
    // suggestions change — focused or not — so a late response would drop a dropdown on top
    // of the field being typed into.
    let deliver: (payload: unknown) => void = () => {}
    vi.mocked(listDocuments).mockReturnValue(
      new Promise((resolve) => {
        deliver = resolve
      }) as never,
    )
    const wrapper = await mountEdit()
    const input = wrapper.find('input#edit-title')
    await input.trigger('focus')
    await input.setValue('Inv')
    await waitForTypeahead()

    await input.trigger('blur')
    deliver(titlePayload(['Invoice 2024']))
    await flushPromises()

    expect(wrapper.findComponent({ name: 'AutoComplete' }).props('suggestions')).toEqual([])
    expect(document.body.querySelectorAll('.p-autocomplete-option').length).toBe(0)
  })
})

// #288 — create a tag without leaving this form. The edit view is the HOST: it lends the
// picker a create affordance, opens the side panel with what was typed, and folds the created
// tag into the SELECTION it is already holding. It must not save the document to do it, and —
// the #234 lesson — it must not re-open the tag overlay behind the panel.
describe('DocumentEdit — create a tag from the tags field (#288)', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: [] } })
    tagApiMock.getTagFacets.mockReset().mockResolvedValue({ data: { tags: [] } })
    tagApiMock.getTagCoOccurrence.mockReset().mockResolvedValue({ data: { pairs: [] } })
    vi.mocked(getDocument).mockReset()
  })

  const panel = () => document.querySelector('.tag-create-panel')

  it('adds NO markup to the form until the panel is asked for', async () => {
    // The rich-description baseline screenshots this very view. A panel that rendered
    // anything in its default state would move it.
    const wrapper = await mountEdit()
    expect(panel()).toBeNull()
    expect(wrapper.find('.p-drawer').exists()).toBe(false)
  })

  it('lends the picker a create label built from the typed text', async () => {
    const wrapper = await mountEdit()
    const build = wrapper.findComponent(TagPicker).props('createTagLabel') as (n: string) => string
    expect(typeof build).toBe('function')
    expect(build('Insurance 2026')).toBe('Create tag “Insurance 2026”…')
  })

  it('opens the panel on the picker\'s create, seeded with the typed text and the title', async () => {
    const wrapper = await mountEdit()
    await wrapper.find('input#edit-title').setValue('Building insurance policy 2026')
    wrapper.findComponent(TagPicker).vm.$emit('create', 'Insurance 2026')
    await flushPromises()

    const created = wrapper.findComponent({ name: 'TagCreatePanel' })
    expect(created.props('visible')).toBe(true)
    expect(created.props('initialName')).toBe('Insurance 2026')
    expect(created.props('documentTitle')).toBe('Building insurance policy 2026')
  })

  it('selects the created tag on the form and leaves the document unsaved', async () => {
    const wrapper = await mountEdit()
    wrapper.findComponent(TagPicker).vm.$emit('create', 'Insurance 2026')
    await flushPromises()

    wrapper.findComponent({ name: 'TagCreatePanel' }).vm.$emit('created', {
      id: 'tag-new',
      name: 'Insurance 2026',
      color: '#2aabd2',
      parent: null,
    })
    await flushPromises()

    expect(wrapper.findComponent(TagPicker).props('modelValue')).toEqual(['tag-new'])
    // The tag lands in the SELECTION; the document is written only when the user saves it.
    expect(vi.mocked(updateDocument)).not.toHaveBeenCalled()
    expect(vi.mocked(createDocument)).not.toHaveBeenCalled()
    // The new tag is on the save payload the form would submit.
    expect((wrapper.vm as unknown as EditVm).buildDocParams().getAll('tags')).toEqual(['tag-new'])
  })

  it('keeps a tag already on the document when a second one is created', async () => {
    const wrapper = await mountEdit()
    wrapper.findComponent(TagPicker).vm.$emit('update:modelValue', ['tag-red'])
    await flushPromises()
    wrapper.findComponent(TagPicker).vm.$emit('create', 'Insurance 2026')
    await flushPromises()
    wrapper.findComponent({ name: 'TagCreatePanel' }).vm.$emit('created', {
      id: 'tag-new',
      name: 'Insurance 2026',
      color: '#2aabd2',
      parent: null,
    })
    await flushPromises()
    expect(wrapper.findComponent(TagPicker).props('modelValue')).toEqual(['tag-red', 'tag-new'])
  })

  it('never re-opens the tag overlay behind or after the panel (#234)', async () => {
    // #234: an overlay the user did not ask for, opening itself, is the exact regression this
    // project has already paid for once. Choosing "create" closes the overlay, and nothing in
    // the create round trip may put it back.
    const wrapper = await mountEdit()
    wrapper.findComponent(TagPicker).vm.$emit('create', 'Insurance 2026')
    await flushPromises()
    expect(document.querySelector('.p-multiselect-overlay')).toBeNull()

    wrapper.findComponent({ name: 'TagCreatePanel' }).vm.$emit('created', {
      id: 'tag-new',
      name: 'Insurance 2026',
      color: '#2aabd2',
      parent: null,
    })
    await flushPromises()
    expect(document.querySelector('.p-multiselect-overlay')).toBeNull()
    const multiselect = wrapper.findComponent(TagPicker).findComponent({ name: 'MultiSelect' })
    expect((multiselect.vm as unknown as { overlayVisible: boolean }).overlayVisible).toBe(false)
  })

  it('closes the panel again when it reports itself closed', async () => {
    const wrapper = await mountEdit()
    wrapper.findComponent(TagPicker).vm.$emit('create', 'Insurance 2026')
    await flushPromises()
    wrapper.findComponent({ name: 'TagCreatePanel' }).vm.$emit('update:visible', false)
    await flushPromises()
    expect(wrapper.findComponent({ name: 'TagCreatePanel' }).props('visible')).toBe(false)
  })
})
