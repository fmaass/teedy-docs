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
import TagForm from '../../components/TagForm.vue'

// #306 — "let permissions be set directly in the tag-management CREATE flow" (the reporter,
// off the #288 mockup review). Until now the page could only create a bare tag: name, colour,
// parent. Setting who may see it meant creating it, finding it in the tree, opening it and
// only THEN reaching a permissions section — the round trip the reporter asked to remove.
//
// The fix is parity with the #288 side panel, through the same two shared units: the
// components/TagForm.vue form (permissions section included) and the deferred-grant create
// contract — collect the grants while the tag has no id, then apply them to the id the
// server hands back.

const TAGS: Tag[] = [
  { id: 'tag-a', name: 'Alpha', color: '#111111', parent: null },
  { id: 'tag-b', name: 'Bravo', color: '#222222', parent: null },
]

const tagApi = vi.hoisted(() => ({
  listTags: vi.fn(),
  createTag: vi.fn(),
  getTagStats: vi.fn(),
  getTagMaintenance: vi.fn(),
  deleteTagSubtree: vi.fn(),
  deleteUnusedTags: vi.fn(),
}))
vi.mock('../../api/tag', () => tagApi)

const aclApi = vi.hoisted(() => ({
  addAcl: vi.fn(),
  deleteAcl: vi.fn(),
  searchAclTargets: vi.fn(),
}))
vi.mock('../../api/acl', () => aclApi)

const toastAdd = vi.hoisted(() => vi.fn())
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: toastAdd }) }))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn((o: { accept: () => void }) => o.accept()) }),
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ username: 'admin' }) }))

beforeAll(() => {
  if (typeof globalThis.ResizeObserver !== 'function') {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
})

import TagList from './TagList.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: { template: '<div/>' } },
    { path: '/tags', name: 'tags', component: { template: '<div/>' } },
    { path: '/tags/:id', name: 'tag-edit', component: { template: '<div/>' }, props: true },
  ],
})

async function mountList() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
  router.push('/tags')
  await router.isReady()
  const wrapper = mount(TagList, {
    global: {
      plugins: [i18n, router, PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
    },
  })
  await flushPromises()
  return { wrapper, invalidate }
}

type Wrapper = Awaited<ReturnType<typeof mountList>>['wrapper']

/** Open the full form — the affordance that carries the permissions section. */
async function openFullForm(wrapper: Wrapper) {
  await wrapper.get('.tag-new-permissions-btn').trigger('click')
  await flushPromises()
}

/** Type a name into whichever field the create card is currently showing. */
async function typeName(wrapper: Wrapper, value: string) {
  const input = wrapper.get('input[placeholder="Tag name"]')
  await input.setValue(value)
  await flushPromises()
}

async function clickCreate(wrapper: Wrapper) {
  await wrapper.get('.tag-create-btn').trigger('click')
  await flushPromises()
}

const READ_BOB = { perm: 'READ', id: 'u-bob', name: 'bob', type: 'USER' } as const
const WRITE_CAROL = { perm: 'WRITE', id: 'u-carol', name: 'carol', type: 'USER' } as const

beforeEach(() => {
  tagApi.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
  tagApi.createTag.mockReset().mockResolvedValue({ data: { id: 'tag-new' } })
  tagApi.getTagStats.mockReset().mockResolvedValue({ data: { stats: {} } })
  tagApi.getTagMaintenance.mockReset().mockResolvedValue({ data: { tags: [] } })
  tagApi.deleteTagSubtree.mockReset()
  tagApi.deleteUnusedTags.mockReset()
  aclApi.addAcl.mockReset().mockResolvedValue({})
  aclApi.searchAclTargets.mockReset().mockResolvedValue({ data: { users: [], groups: [] } })
  toastAdd.mockReset()
})

describe('TagList — reaching the permissions section while creating (#306)', () => {
  it('starts on the compact create row, with no form and no permissions section', async () => {
    // The page's resting state is deliberately unchanged: the compact row is the seeding
    // path of six e2e specs and the surface #86's German button-geometry gate measures.
    const { wrapper } = await mountList()
    expect(wrapper.findComponent(TagForm).exists()).toBe(false)
    expect(wrapper.findAll('.create-row')).toHaveLength(2)
    expect(wrapper.find('.tag-new-permissions-btn').exists(), 'the way in is offered').toBe(true)
  })

  it('opens the SHARED tag form, permissions section and all', async () => {
    const { wrapper } = await mountList()
    await openFullForm(wrapper)

    const form = wrapper.findComponent(TagForm)
    expect(form.exists(), 'the same component the #288 panel and the edit page host').toBe(true)
    expect(wrapper.find('.acl-editor').exists(), 'the permissions section is rendered').toBe(true)
    // The tag does not exist yet, so the editor must COLLECT grants rather than send them.
    expect(form.props('acl').deferred).toBe(true)
    expect(form.props('acl').writable).toBe(true)
  })

  it('shows the creator\'s own owner grant, locked, because the server will create it', async () => {
    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    const rows = wrapper.findAll('.acl-row')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('admin')
    expect(rows[0].find('.acl-immutable').exists(), 'not the user\'s to drop').toBe(true)
  })

  it('carries the name already typed into the compact row over to the full form', async () => {
    const { wrapper } = await mountList()
    await typeName(wrapper, 'Insurance 2026')
    await openFullForm(wrapper)
    expect(wrapper.findComponent(TagForm).props('name')).toBe('Insurance 2026')
  })

  it('offers every tag as a parent, defaulting to root level', async () => {
    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    const form = wrapper.findComponent(TagForm)
    expect(form.props('parent')).toBeNull()
    expect(form.props('parentOptions')).toEqual([
      { label: '(none — root level)', value: null },
      { label: 'Alpha', value: 'tag-a' },
      { label: 'Bravo', value: 'tag-b' },
    ])
  })
})

describe('TagList — creating a tag with its permissions (#306)', () => {
  it('applies the collected grants to the created tag, in order, only once it exists', async () => {
    const order: string[] = []
    tagApi.createTag.mockImplementation(async () => {
      order.push('create')
      return { data: { id: 'tag-new' } }
    })
    aclApi.addAcl.mockImplementation(async (_id: string, _perm: string, name: string) => {
      order.push(`grant:${name}`)
      return {}
    })

    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    await typeName(wrapper, 'Insurance 2026')
    const form = wrapper.findComponent(TagForm)
    form.vm.$emit('acl-add', READ_BOB)
    form.vm.$emit('acl-add', WRITE_CAROL)
    await flushPromises()
    // Both grants are visible beside the owner row before anything is saved.
    expect(wrapper.findAll('.acl-row')).toHaveLength(3)

    await clickCreate(wrapper)

    expect(tagApi.createTag).toHaveBeenCalledWith('Insurance 2026', '#2aabd2', undefined, null)
    // The tag has to exist before a grant has anywhere to land, and the grants are applied
    // in the order they were collected.
    expect(order).toEqual(['create', 'grant:bob', 'grant:carol'])
    expect(aclApi.addAcl).toHaveBeenNthCalledWith(1, 'tag-new', 'READ', 'bob', 'USER')
    expect(aclApi.addAcl).toHaveBeenNthCalledWith(2, 'tag-new', 'WRITE', 'carol', 'USER')
    // The owner row is the SERVER's to create; re-granting it would be a wasted round trip.
    expect(aclApi.addAcl).toHaveBeenCalledTimes(2)
  })

  it('drops a collected grant again when it is removed before saving', async () => {
    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    await typeName(wrapper, 'Insurance 2026')
    const form = wrapper.findComponent(TagForm)
    form.vm.$emit('acl-add', READ_BOB)
    await flushPromises()
    form.vm.$emit('acl-remove', READ_BOB)
    await flushPromises()

    await clickCreate(wrapper)
    expect(aclApi.addAcl).not.toHaveBeenCalled()
  })

  it('sends the chosen parent and colour with the tag', async () => {
    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    await typeName(wrapper, 'Insurance 2026')
    const form = wrapper.findComponent(TagForm)
    form.vm.$emit('update:parent', 'tag-a')
    form.vm.$emit('update:color', 'ff8800')
    await flushPromises()

    await clickCreate(wrapper)
    expect(tagApi.createTag).toHaveBeenCalledWith('Insurance 2026', '#ff8800', 'tag-a', null)
  })

  it('refreshes the tree and clears the draft once the tag and its grants are in', async () => {
    const { wrapper, invalidate } = await mountList()
    await openFullForm(wrapper)
    await typeName(wrapper, 'Insurance 2026')
    wrapper.findComponent(TagForm).vm.$emit('acl-add', READ_BOB)
    await flushPromises()

    await clickCreate(wrapper)

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tags'] })
    expect(toastAdd).toHaveBeenCalledWith(
      expect.objectContaining({ severity: 'success', summary: 'Tag created' }),
    )
    // The applied grant belongs to the tag that now exists — it must not follow the next one.
    expect(wrapper.get('input[placeholder="Tag name"]').element).toHaveProperty('value', '')
    expect(wrapper.findAll('.acl-row'), 'only the owner row is left').toHaveLength(1)
  })

  it('reports a grant the server refused without pretending the tag failed', async () => {
    // The tag exists at that point — it is not rolled back, and the permissions can be
    // finished on the tag's own page.
    aclApi.addAcl.mockRejectedValue(new Error('nope'))
    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    await typeName(wrapper, 'Insurance 2026')
    wrapper.findComponent(TagForm).vm.$emit('acl-add', READ_BOB)
    await flushPromises()

    await clickCreate(wrapper)

    expect(tagApi.createTag).toHaveBeenCalledTimes(1)
    expect(toastAdd).toHaveBeenCalledWith(
      expect.objectContaining({ severity: 'error', summary: 'Failed to add permission' }),
    )
  })
})

describe('TagList — a create the server refuses (#306)', () => {
  it('quotes the server\'s own reason in the card and keeps the draft', async () => {
    // The tag endpoints answer with a named client error (IllegalTagName, ValidationError,
    // ParentNotFound, and the duplicate-name case). Quoting it beside the field is the
    // difference between "fix the name" and "try again".
    tagApi.createTag.mockRejectedValue({
      response: { data: { message: 'Spaces, colons and asterisks are not allowed in tag name' } },
    })
    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    await typeName(wrapper, 'bad name')
    wrapper.findComponent(TagForm).vm.$emit('acl-add', READ_BOB)
    await flushPromises()

    await clickCreate(wrapper)

    const error = wrapper.find('.tag-new-error')
    expect(error.exists(), 'the failure is surfaced where the user is looking').toBe(true)
    expect(error.text()).toContain('asterisks are not allowed')
    // Nothing is thrown away: the name and the collected grant are still there to retry with.
    expect(wrapper.findComponent(TagForm).props('name')).toBe('bad name')
    expect(wrapper.findAll('.acl-row')).toHaveLength(2)
    expect(aclApi.addAcl).not.toHaveBeenCalled()
  })

  it('falls back to a plain failure message when the server names no reason', async () => {
    tagApi.createTag.mockRejectedValue(new Error('network'))
    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    await typeName(wrapper, 'Insurance 2026')
    await clickCreate(wrapper)
    expect(wrapper.get('.tag-new-error').text()).toContain('Failed to create tag')
  })

  it('clears the failure once the next attempt succeeds', async () => {
    tagApi.createTag.mockRejectedValueOnce(new Error('network'))
    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    await typeName(wrapper, 'Insurance 2026')
    await clickCreate(wrapper)
    expect(wrapper.find('.tag-new-error').exists()).toBe(true)

    await clickCreate(wrapper)
    expect(wrapper.find('.tag-new-error').exists()).toBe(false)
  })
})

// A create is not instantaneous, and every field the card holds is a ref the user can still
// reach while `createTag` is in flight. Two guarantees, the same pair the #288 panel makes:
// the draft cannot be discarded out from under a running request, and the request that IS
// running carries the draft it started with rather than whatever the refs say when each
// `await` resumes.
describe('TagList — a create already in flight (#306)', () => {
  function deferred<T>() {
    let resolve!: (value: T) => void
    const promise = new Promise<T>((r) => {
      resolve = r
    })
    return { promise, resolve }
  }

  it('refuses to discard the draft while the tag is being created', async () => {
    const pending = deferred<{ data: { id: string } }>()
    tagApi.createTag.mockReturnValue(pending.promise)
    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    await typeName(wrapper, 'Insurance 2026')

    await clickCreate(wrapper)
    const cancel = wrapper.get('.tag-create-cancel-btn')
    expect((cancel.element as HTMLButtonElement).disabled, 'Cancel is withdrawn').toBe(true)

    // And a cancel arriving anyway is refused rather than resetting the running draft.
    await cancel.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(TagForm).exists(), 'the form stays open').toBe(true)
    expect(wrapper.findComponent(TagForm).props('name')).toBe('Insurance 2026')

    pending.resolve({ data: { id: 'tag-new' } })
    await flushPromises()
    expect(toastAdd).toHaveBeenCalledWith(expect.objectContaining({ summary: 'Tag created' }))
  })

  it('will not start a second tag while the first create is still running', async () => {
    // Driven through the name field's Enter, not the button: the button is `loading` for the
    // duration and would refuse the second press by itself, which would prove nothing about
    // the guard. Enter reaches the handler either way.
    const pending = deferred<{ data: { id: string } }>()
    tagApi.createTag.mockReturnValue(pending.promise)
    const { wrapper } = await mountList()
    const input = wrapper.get('input[placeholder="Tag name"]')
    await input.setValue('Insurance 2026')

    await input.trigger('keydown.enter')
    await flushPromises()
    await input.trigger('keydown.enter')
    await flushPromises()

    expect(tagApi.createTag, 'one press, one tag').toHaveBeenCalledTimes(1)

    pending.resolve({ data: { id: 'tag-new' } })
    await flushPromises()
    expect(toastAdd).toHaveBeenCalledTimes(1)
  })

  it('carries the grants it started with, not the ones added while it ran', async () => {
    const pending = deferred<{ data: { id: string } }>()
    tagApi.createTag.mockReturnValue(pending.promise)
    const { wrapper } = await mountList()
    await openFullForm(wrapper)
    await typeName(wrapper, 'Insurance 2026')
    const form = wrapper.findComponent(TagForm)
    form.vm.$emit('acl-add', READ_BOB)
    await flushPromises()

    await clickCreate(wrapper)

    // The user keeps editing a card whose create has not come back yet.
    form.vm.$emit('acl-add', WRITE_CAROL)
    await flushPromises()

    pending.resolve({ data: { id: 'tag-new' } })
    await flushPromises()

    // Only the grant that was on the draft when Create was pressed reaches that tag.
    expect(aclApi.addAcl).toHaveBeenCalledTimes(1)
    expect(aclApi.addAcl).toHaveBeenCalledWith('tag-new', 'READ', 'bob', 'USER')
  })
})

describe('TagList — the compact create row still works (#306 regression)', () => {
  it('creates a tag without ever opening the full form', async () => {
    const { wrapper, invalidate } = await mountList()
    await typeName(wrapper, 'Quick tag')
    await clickCreate(wrapper)

    expect(tagApi.createTag).toHaveBeenCalledWith('Quick tag', '#2aabd2', undefined, null)
    expect(aclApi.addAcl).not.toHaveBeenCalled()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tags'] })
    expect(toastAdd).toHaveBeenCalledWith(
      expect.objectContaining({ severity: 'success', summary: 'Tag created' }),
    )
    expect(wrapper.get('input[placeholder="Tag name"]').element).toHaveProperty('value', '')
  })

  it('sends nothing at all for an empty name', async () => {
    const { wrapper } = await mountList()
    await typeName(wrapper, '   ')
    await clickCreate(wrapper)
    expect(tagApi.createTag).not.toHaveBeenCalled()
  })
})
