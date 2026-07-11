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
import type { Tag } from '../../api/tag'

// The document-tag picker (#14 type-to-filter, #23 colored wrapping chips) is the unit
// under test. We mount DocumentEdit in create mode and drive its REAL tagOptions computed
// and MultiSelect template off a seeded tag list, so the assertions exercise the shipped
// mapping and chip slot rather than a re-derived copy.

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
import { getDocument } from '../../api/document'

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

  it('#23: tagOptions retains each tag color (would drop if the mapping omits color)', async () => {
    const wrapper = await mountEdit()
    const options = (wrapper.vm as unknown as { tagOptions: Array<{ label: string; value: string; color: string }> }).tagOptions
    expect(options).toEqual([
      { label: 'Invoice', value: 'tag-red', color: '#d32f2f' },
      { label: 'Receipt', value: 'tag-green', color: '#2e7d32' },
      { label: 'Contract', value: 'tag-blue', color: '#1565c0' },
    ])
  })

  it('#14: the document-tag MultiSelect enables type-to-filter', async () => {
    const wrapper = await mountEdit()
    const multiselect = wrapper.findComponent({ name: 'MultiSelect' })
    expect(multiselect.exists()).toBe(true)
    expect(multiselect.props('filter')).toBe(true)
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
