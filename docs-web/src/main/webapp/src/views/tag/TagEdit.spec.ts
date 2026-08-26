import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'
import { tagCountKeys } from '../../api/queryKeys'
import type { Tag } from '../../api/tag'

// #14: the tag-PARENT Select must support type-to-filter (critical at ~350 tags).

const TAGS: Tag[] = [
  { id: 'a', name: 'Alpha', color: '#111111', parent: null },
  { id: 'b', name: 'Bravo', color: '#222222', parent: null },
  { id: 'c', name: 'Charlie', color: '#333333', parent: null },
]

const tagApiMock = vi.hoisted(() => ({
  listTags: vi.fn(),
  getTag: vi.fn(),
  getTagStats: vi.fn(),
  updateTag: vi.fn(),
  deleteTag: vi.fn(),
}))
vi.mock('../../api/tag', () => tagApiMock)

// #281/#289: TagEdit navigates through the tag-filter store's REPLACE-semantics
// action. The real store drags in the router, more of the tag API surface, and
// Pinia — none of which this component test needs; the contract under test is
// only "the click hands THIS tag's id to showDocumentsForTag, and never to the
// additive chip action". Both actions are mocked so the negative half of that
// contract is observable rather than a silent undefined.
const tagFilterStoreMock = vi.hoisted(() => ({
  selectTag: vi.fn(),
  showDocumentsForTag: vi.fn(),
}))
vi.mock('../../stores/tagFilter', () => ({ useTagFilterStore: () => tagFilterStoreMock }))

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

import TagEdit from './TagEdit.vue'
import TagForm from '../../components/TagForm.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: { template: '<div/>' } },
    { path: '/tags', name: 'tags', component: { template: '<div/>' } },
  ],
})

function freshQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

async function mountEdit(queryClient: QueryClient = freshQueryClient()) {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  router.push('/')
  await router.isReady()
  const wrapper = mount(TagEdit, {
    props: { id: 'b' },
    global: {
      plugins: [i18n, router, PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
      // The AclEditor's immutable lock marker carries v-tooltip; register a no-op so the
      // directive resolves in the test (the real app registers it globally in main.ts).
      directives: { tooltip: {} },
    },
  })
  await flushPromises()
  return wrapper
}

describe('TagEdit — parent Select (#14 filter)', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.getTag.mockReset().mockResolvedValue({
      data: { id: 'b', name: 'Bravo', creator: 'admin', color: '#222222', parent: null, writable: false, acls: [] },
    })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: {} } })
  })

  it('enables type-to-filter on the parent Select', async () => {
    const wrapper = await mountEdit()
    const select = wrapper.findComponent({ name: 'Select' })
    expect(select.exists()).toBe(true)
    expect(select.props('filter')).toBe(true)
  })
})

// #281: the per-tag document count was fetched but never displayed, and the only
// way from the edit page to the tag's documents was rebuilding the filter by hand.
// The page now shows the count and offers a one-click "view documents" action.
// #289: it must route through showDocumentsForTag (reset, then the sidebar chips'
// canonical selectTag path) — going through the additive selectTag directly ANDed
// this tag onto whatever the previous visit had left selected, landing the user on
// an empty list.
describe('TagEdit — document count and view documents (#281)', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.getTag.mockReset().mockResolvedValue({
      data: { id: 'b', name: 'Bravo', creator: 'admin', color: '#222222', parent: null, writable: false, acls: [] },
    })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: { b: 4 } } })
    tagFilterStoreMock.selectTag.mockReset()
    tagFilterStoreMock.showDocumentsForTag.mockReset()
  })

  it('displays the fetched document count', async () => {
    const wrapper = await mountEdit()
    expect(wrapper.find('.tag-doc-count').text()).toBe('Documents with this tag: 4')
  })

  it('routes the view-documents click through the REPLACE-semantics action (#289)', async () => {
    const wrapper = await mountEdit()
    await wrapper.find('button.view-docs-btn').trigger('click')
    expect(tagFilterStoreMock.showDocumentsForTag).toHaveBeenCalledTimes(1)
    expect(tagFilterStoreMock.showDocumentsForTag).toHaveBeenCalledWith('b')
    // The additive chip action must NOT be the one this button reaches.
    expect(tagFilterStoreMock.selectTag).not.toHaveBeenCalled()
  })
})

// #88: the tag permissions editor wires GET /tag/{id}'s ACLs into AclEditor and marks the
// creator's own base grants immutable (the owner's mandatory READ/WRITE, which the backend
// refuses to remove) — so those rows have no remove button while a granted user's row does.
describe('TagEdit — permissions editor (#88)', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: { b: 4 } } })
    tagApiMock.getTag.mockReset().mockResolvedValue({
      data: {
        id: 'b',
        name: 'Bravo',
        creator: 'admin',
        color: '#222222',
        parent: null,
        writable: true,
        acls: [
          { perm: 'READ', id: 'uadmin', name: 'admin', type: 'USER' },
          { perm: 'WRITE', id: 'uadmin', name: 'admin', type: 'USER' },
          { perm: 'READ', id: 'ubob', name: 'bob', type: 'USER' },
        ],
      },
    })
  })

  it('renders the tag ACLs and marks the owner base grants immutable', async () => {
    const wrapper = await mountEdit()
    const rows = wrapper.findAll('.acl-row')
    expect(rows).toHaveLength(3)

    // The creator ("admin") holds two base grants — both immutable (no remove button, lock marker).
    const adminRows = rows.filter((r) => r.text().includes('admin'))
    expect(adminRows).toHaveLength(2)
    for (const row of adminRows) {
      expect(row.find('button[aria-label="Remove permission"]').exists()).toBe(false)
      expect(row.find('.acl-immutable').exists()).toBe(true)
    }

    // The granted user ("bob") is removable.
    const bobRow = rows.find((r) => r.text().includes('bob'))!
    expect(bobRow.find('button[aria-label="Remove permission"]').exists()).toBe(true)
    expect(bobRow.find('.acl-immutable').exists()).toBe(false)
  })

  // R3: when the creator's account is deleted, a NON-creator can become the sole WRITE holder.
  // Their WRITE row must be immutable (the server's last-write guard would reject the delete)
  // with a reason-specific lock label, while their READ row stays removable.
  it('marks a non-creator sole WRITE holder immutable when the creator is gone', async () => {
    tagApiMock.getTag.mockResolvedValue({
      data: {
        id: 'b',
        name: 'Bravo',
        creator: 'ghost', // deleted creator, no longer present in the ACL list
        color: '#222222',
        parent: null,
        writable: true,
        acls: [
          { perm: 'READ', id: 'ubob', name: 'bob', type: 'USER' },
          { perm: 'WRITE', id: 'ubob', name: 'bob', type: 'USER' },
        ],
      },
    })
    const wrapper = await mountEdit()
    const rows = wrapper.findAll('.acl-row')
    expect(rows).toHaveLength(2)

    const writeRow = rows.find((r) => r.text().includes('Can edit'))!
    const readRow = rows.find((r) => r.text().includes('Can view'))!

    // The sole WRITE holder's row is immutable, with the last-owner label (not the owner label).
    expect(writeRow.find('button[aria-label="Remove permission"]').exists()).toBe(false)
    expect(writeRow.find('.acl-immutable').exists()).toBe(true)
    expect(writeRow.find('.acl-immutable').attributes('aria-label')).toContain('Sole owner')

    // The same user's READ row is still removable (only the last WRITE is protected).
    expect(readRow.find('button[aria-label="Remove permission"]').exists()).toBe(true)
    expect(readRow.find('.acl-immutable').exists()).toBe(false)
  })
})

// The count on this page is not a page-local reading: it is the app-wide tag-stats
// entry, the one every document tag add/remove/bulk edit stales through `tagCountKeys`.
// Held under a page-private key it kept reporting whatever was true when the edit page
// was first opened, so a tag the user had just emptied from the document list still
// disclosed documents in the READ-grant confirmation.
describe('TagEdit — the document count follows a tag-count invalidation', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.getTag.mockReset().mockResolvedValue({
      data: { id: 'b', name: 'Bravo', creator: 'admin', color: '#222222', parent: null, writable: false, acls: [] },
    })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: { b: 4 } } })
  })

  it('re-reads the count when a document tag change invalidates tagCountKeys', async () => {
    const queryClient = freshQueryClient()
    const wrapper = await mountEdit(queryClient)
    expect(wrapper.find('.tag-doc-count').text()).toBe('Documents with this tag: 4')

    // A document elsewhere in the session gains this tag: the server now answers 5, and
    // the mutation stales every key in tagCountKeys — the real list, so this test follows
    // the production invalidation set rather than a copy of it.
    tagApiMock.getTagStats.mockResolvedValue({ data: { stats: { b: 5 } } })
    for (const key of tagCountKeys) await queryClient.invalidateQueries({ queryKey: key })
    await flushPromises()

    expect(wrapper.find('.tag-doc-count').text()).toBe('Documents with this tag: 5')
  })
})

// #288 — the form on this page became a SHARED component so the document editor's create-tag
// panel could host the very same one. Everything above this block is the regression proof that
// the page itself did not change; these two assertions pin that the sharing is real (one
// implementation, two hosts) rather than a copy, and that the panel's autofocus did not come
// along with it — this page must not grab the caret on load.
describe('TagEdit — hosts the shared tag form (#288)', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.getTag.mockReset().mockResolvedValue({
      data: { id: 'b', name: 'Bravo', creator: 'admin', color: '#222222', parent: null, writable: true, acls: [] },
    })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: {} } })
  })

  it('renders the fields and permissions through TagForm, keeping its own field ids', async () => {
    const wrapper = await mountEdit()
    const form = wrapper.findComponent(TagForm)
    expect(form.exists()).toBe(true)
    expect(form.props('idPrefix')).toBe('tag')
    expect(form.props('flat')).toBeFalsy()
    expect(wrapper.find('input#tag-name').exists()).toBe(true)
    expect(wrapper.find('#tag-parent').exists()).toBe(true)
    // #303 — the hex field is part of the shared form, so it reaches this page through the
    // same `idPrefix` its other fields do, with no host-side wiring of its own.
    expect(wrapper.find('input#tag-color-hex').exists()).toBe(true)
    // The permissions here are the LIVE ones on an existing tag, never the panel's deferred ones.
    expect(form.props('acl')).toMatchObject({ sourceId: 'b' })
    expect(form.props('acl').deferred).toBeFalsy()
  })

  it('does not steal the caret into the name field on load', async () => {
    const wrapper = await mountEdit()
    expect(wrapper.find('input#tag-name').attributes('autofocus')).toBeUndefined()
  })
})

// #280 — this page is the host that MANAGES synonyms: it seeds them from the tag detail, hands
// them to the shared form, and sends the whole list back on save (replace semantics). The other
// two hosts of that form create tags and leave the section off entirely.
describe('TagEdit — synonyms (#280)', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.getTag.mockReset().mockResolvedValue({
      data: {
        id: 'b',
        name: 'Bravo',
        creator: 'admin',
        color: '#222222',
        parent: null,
        writable: true,
        acls: [],
        synonyms: ['Rechnung'],
      },
    })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: {} } })
    tagApiMock.updateTag.mockReset().mockResolvedValue({ data: { id: 'b' } })
  })

  it('seeds the form from the tag detail and excludes the tag itself from the in-use hint', async () => {
    const wrapper = await mountEdit()
    const form = wrapper.findComponent(TagForm)

    expect(form.props('synonyms')).toEqual(['Rechnung'])
    expect(form.props('synonymTagId')).toBe('b')
    expect((form.props('synonymTags') as Tag[]).map((tag) => tag.id)).toEqual(['a', 'b', 'c'])
  })

  it('sends the whole synonym list on save', async () => {
    const wrapper = await mountEdit()
    wrapper.findComponent(TagForm).vm.$emit('update:synonyms', ['Rechnung', 'Quittung'])
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text().includes('Save'))!.trigger('click')
    await flushPromises()

    // The merged signature is (id, name, color, parent, icon, synonyms): #287 put `icon` in
    // front of the synonyms this test is about, and a tag with no icon sends null.
    expect(tagApiMock.updateTag).toHaveBeenCalledWith('b', 'Bravo', '#222222', undefined, null, [
      'Rechnung',
      'Quittung',
    ])
  })

  it('sends an EMPTY list once the last chip is removed, which is what clears them', async () => {
    // Not the same as omitting the field: an absent field leaves the stored synonyms alone, so
    // removing the last chip has to be an explicit empty list or it would silently do nothing.
    const wrapper = await mountEdit()
    wrapper.findComponent(TagForm).vm.$emit('update:synonyms', [])
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text().includes('Save'))!.trigger('click')
    await flushPromises()

    expect(tagApiMock.updateTag).toHaveBeenCalledWith('b', 'Bravo', '#222222', undefined, null, [])
  })
})
