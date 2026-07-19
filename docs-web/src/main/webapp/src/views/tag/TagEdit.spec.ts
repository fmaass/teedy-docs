import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'
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

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: { template: '<div/>' } },
    { path: '/tags', name: 'tags', component: { template: '<div/>' } },
  ],
})

async function mountEdit() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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
