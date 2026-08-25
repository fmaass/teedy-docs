import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import en from '../locale/en.json'
import type { Tag } from '../api/tag'
import TagForm from './TagForm.vue'

// #288 — "the tag form could be also some kind of panel which appears on the right side (like
// the document preview panel), so we have a split view: document edit form + tag edit form"
// (the reporter). The panel creates the tag and hands it back to the document form's
// SELECTION — it never saves the document itself, because the user has not pressed Save on
// the document yet and a silent PUT would commit half-finished edits.

const tagApi = vi.hoisted(() => ({
  createTag: vi.fn(),
  listTags: vi.fn(),
  isMetaTag: (name: string) => name.startsWith('__'),
}))
vi.mock('../api/tag', () => tagApi)

const aclApi = vi.hoisted(() => ({
  addAcl: vi.fn(),
  deleteAcl: vi.fn(),
  searchAclTargets: vi.fn(),
}))
vi.mock('../api/acl', () => aclApi)

const toastAdd = vi.hoisted(() => vi.fn())
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: toastAdd }) }))
vi.mock('../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn((o: { accept: () => void }) => o.accept()) }),
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => ({ username: 'admin' }) }))

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

import TagCreatePanel from './TagCreatePanel.vue'

const TAGS: Tag[] = [
  { id: 'tag-red', name: 'Invoice', color: '#d32f2f', parent: null },
  { id: 'tag-blue', name: 'Contract', color: '#1565c0', parent: null },
]

function mountPanel(props: Record<string, unknown> = {}) {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(TagCreatePanel, {
    props: {
      visible: true,
      initialName: 'Insurance 2026',
      documentTitle: 'Building insurance policy 2026',
      tags: TAGS,
      ...props,
    } as never,
    global: {
      plugins: [i18n, PrimeVue, [VueQueryPlugin, { queryClient }]],
      directives: { tooltip: {} },
      stubs: { transition: false },
    },
  })
}

// The drawer is teleported to <body>, so its content is read through the document.
const drawer = () => document.querySelector('.tag-create-panel') as HTMLElement | null
const nameInput = () => document.querySelector('input#tag-create-name') as HTMLInputElement | null
const saveButton = () =>
  Array.from(document.querySelectorAll('.tag-create-actions button')).find((b) =>
    b.textContent?.includes('Save'),
  ) as HTMLElement | undefined
const cancelButton = () =>
  Array.from(document.querySelectorAll('.tag-create-actions button')).find((b) =>
    b.textContent?.includes('Cancel'),
  ) as HTMLElement | undefined

beforeEach(() => {
  tagApi.createTag.mockReset().mockResolvedValue({ data: { id: 'tag-new' } })
  tagApi.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
  aclApi.addAcl.mockReset().mockResolvedValue({})
  aclApi.searchAclTargets.mockReset().mockResolvedValue({ data: { users: [], groups: [] } })
  toastAdd.mockReset()
  document.body.innerHTML = ''
})

describe('TagCreatePanel — what the panel shows when it opens', () => {
  it('renders nothing at all while it is closed', async () => {
    const wrapper = mountPanel({ visible: false })
    await flushPromises()
    expect(drawer(), 'a closed panel must add no markup to the document edit view').toBeNull()
    expect(wrapper.findComponent(TagForm).exists()).toBe(false)
    wrapper.unmount()
  })

  it('pre-fills the name with the text typed into the tag field', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(nameInput()!.value).toBe('Insurance 2026')
    wrapper.unmount()
  })

  it('puts the caret in the name field without the host having to manage focus', async () => {
    // PrimeVue's Drawer focuses `[autofocus]` inside its content first of all (drawer/index.mjs
    // `focus()`), so declaring it is the whole mechanism — no host-side focus call to race with
    // the overlay the picker just closed.
    const wrapper = mountPanel()
    await flushPromises()
    expect(nameInput()!.hasAttribute('autofocus')).toBe(true)
    wrapper.unmount()
  })

  it('states that saving creates the tag AND puts it on THIS document, naming it', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const lead = document.querySelector('.tag-create-lead')!
    expect(lead.textContent).toContain('Building insurance policy 2026')
    expect(lead.textContent).toContain('when you save')
    wrapper.unmount()
  })

  it('still explains itself while the document is untitled (the reporter\'s own case)', async () => {
    // "In case we create a new document and need a new tag" — a document being created has no
    // title yet, so the lead cannot be built around one.
    const wrapper = mountPanel({ documentTitle: '   ' })
    await flushPromises()
    const lead = document.querySelector('.tag-create-lead')!
    expect(lead.textContent).toContain('this document')
    wrapper.unmount()
  })

  it('reminds that a fresh tag is private until permissions are set', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const hint = document.querySelector('.tag-create-perm-hint')
    expect(hint, 'the reminder the reporter asked for').not.toBeNull()
    expect(hint!.textContent).toContain('only you will see it')
    wrapper.unmount()
  })

  it('shows the creator\'s own owner grant, locked, because the server will create it', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const rows = Array.from(document.querySelectorAll('.acl-row'))
    expect(rows).toHaveLength(1)
    expect(rows[0].textContent).toContain('admin')
    expect(rows[0].textContent).toContain('Can edit')
    expect(rows[0].querySelector('.acl-immutable'), 'the owner grant is not the user\'s to drop').not.toBeNull()
    wrapper.unmount()
  })

  it('offers the hex code field the shared form gained in #303, under its own prefix', async () => {
    // Nothing here wires it up: the panel hosts TagForm, so the field arrives with the rest of
    // the form and takes the panel's `tag-create` prefix rather than the management page's.
    const wrapper = mountPanel()
    await flushPromises()
    const hex = document.querySelector('input#tag-create-color-hex') as HTMLInputElement | null
    expect(hex).not.toBeNull()
    expect(hex!.value).toBe('#2aabd2')
    expect(document.querySelector('input#tag-color-hex')).toBeNull()
    wrapper.unmount()
  })

  it('offers every existing tag as a possible parent, defaulting to root level', async () => {
    // Approved with the mockup: a new tag starts at ROOT level. Nothing is inferred from the
    // document's existing tags — the parent is an explicit choice or none.
    const wrapper = mountPanel()
    await flushPromises()
    const form = wrapper.findComponent(TagForm)
    expect(form.props('parent')).toBeNull()
    expect(form.props('parentOptions')).toEqual([
      { label: '(none — root level)', value: null },
      { label: 'Invoice', value: 'tag-red' },
      { label: 'Contract', value: 'tag-blue' },
    ])
    wrapper.unmount()
  })
})

describe('TagCreatePanel — saving', () => {
  it('creates the tag and hands it back for the document\'s selection, then closes', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    wrapper.findComponent(TagForm).vm.$emit('update:color', 'ff8800')
    await flushPromises()

    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(tagApi.createTag).toHaveBeenCalledWith('Insurance 2026', '#ff8800', undefined)
    expect(wrapper.emitted('created')).toEqual([
      [{ id: 'tag-new', name: 'Insurance 2026', color: '#ff8800', parent: null }],
    ])
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('never saves the document itself — that stays the user\'s Save on the form', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    // The panel's whole API surface: the tag endpoints and nothing else.
    expect(Object.keys(tagApi.createTag.mock.calls).length).toBe(1)
    expect(wrapper.emitted('created')).toBeTruthy()
    wrapper.unmount()
  })

  it('sends the chosen parent when one is picked', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    wrapper.findComponent(TagForm).vm.$emit('update:parent', 'tag-red')
    await flushPromises()
    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(tagApi.createTag).toHaveBeenCalledWith('Insurance 2026', '#2aabd2', 'tag-red')
    wrapper.unmount()
  })

  it('applies the permissions collected in the panel only AFTER the tag exists', async () => {
    const order: string[] = []
    tagApi.createTag.mockImplementation(async () => {
      order.push('create')
      return { data: { id: 'tag-new' } }
    })
    aclApi.addAcl.mockImplementation(async () => {
      order.push('grant')
      return {}
    })

    const wrapper = mountPanel()
    await flushPromises()
    wrapper
      .findComponent(TagForm)
      .vm.$emit('acl-add', { perm: 'READ', id: 'u9', name: 'bob', type: 'USER' })
    await flushPromises()
    // The collected grant is visible in the panel before it is saved anywhere.
    expect(document.querySelectorAll('.acl-row')).toHaveLength(2)

    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(order).toEqual(['create', 'grant'])
    expect(aclApi.addAcl).toHaveBeenCalledWith('tag-new', 'READ', 'bob', 'USER')
    // The owner row is the SERVER's to create; re-granting it would be a wasted round trip.
    expect(aclApi.addAcl).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('drops a collected grant again when it is removed before saving', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const grant = { perm: 'READ', id: 'u9', name: 'bob', type: 'USER' }
    wrapper.findComponent(TagForm).vm.$emit('acl-add', grant)
    await flushPromises()
    wrapper.findComponent(TagForm).vm.$emit('acl-remove', grant)
    await flushPromises()

    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(aclApi.addAcl).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('refuses to save an empty name rather than sending one the server will reject', async () => {
    const wrapper = mountPanel({ initialName: '   ' })
    await flushPromises()
    expect((saveButton() as HTMLButtonElement).disabled).toBe(true)
    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(tagApi.createTag).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('TagCreatePanel — a create the server refuses', () => {
  it('shows the server\'s own reason in the panel and stays open', async () => {
    // The tag endpoints answer with a named client error (IllegalTagName, ValidationError,
    // ParentNotFound). Quoting it is the difference between "fix the name" and "try again".
    tagApi.createTag.mockRejectedValue({
      response: { data: { message: 'Spaces, colons and asterisks are not allowed in tag name' } },
    })
    const wrapper = mountPanel()
    await flushPromises()
    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    const error = document.querySelector('.tag-create-error')
    expect(error, 'the failure is surfaced where the user is looking').not.toBeNull()
    expect(error!.textContent).toContain('asterisks are not allowed')
    expect(wrapper.emitted('update:visible'), 'a failed create keeps the panel open').toBeFalsy()
    expect(wrapper.emitted('created')).toBeFalsy()
    wrapper.unmount()
  })

  it('falls back to a plain failure message when the server names no reason', async () => {
    tagApi.createTag.mockRejectedValue(new Error('network'))
    const wrapper = mountPanel()
    await flushPromises()
    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(document.querySelector('.tag-create-error')!.textContent).toContain('Failed to create tag')
    wrapper.unmount()
  })

  it('clears a previous failure when the next save succeeds', async () => {
    tagApi.createTag.mockRejectedValueOnce(new Error('network'))
    const wrapper = mountPanel()
    await flushPromises()
    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(document.querySelector('.tag-create-error')).not.toBeNull()

    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(wrapper.emitted('created')).toBeTruthy()
    wrapper.unmount()
  })
})

describe('TagCreatePanel — cancelling', () => {
  it('discards everything and creates nothing', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    nameInput()!.value = 'Something else'
    nameInput()!.dispatchEvent(new Event('input'))
    await flushPromises()

    cancelButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(tagApi.createTag).not.toHaveBeenCalled()
    expect(aclApi.addAcl).not.toHaveBeenCalled()
    expect(wrapper.emitted('created')).toBeFalsy()
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('starts from scratch the next time it opens, not from the abandoned draft', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    nameInput()!.value = 'Abandoned'
    nameInput()!.dispatchEvent(new Event('input'))
    wrapper
      .findComponent(TagForm)
      .vm.$emit('acl-add', { perm: 'READ', id: 'u9', name: 'bob', type: 'USER' })
    await flushPromises()

    cancelButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await wrapper.setProps({ visible: false })
    await flushPromises()
    await wrapper.setProps({ visible: true, initialName: 'Lease 2027' })
    await flushPromises()

    expect(nameInput()!.value).toBe('Lease 2027')
    expect(document.querySelectorAll('.acl-row'), 'the abandoned grant is gone').toHaveLength(1)
    wrapper.unmount()
  })
})

// A save is not instantaneous, and everything the panel holds is a ref that the user can still
// reach while `createTag` is in flight: the drawer's close icon, the footer Cancel and the
// form's own fields all stay live. Two independent guarantees are needed, and this block asserts
// both — a save in flight cannot be closed out from under, and even if it could, the request
// that is running carries the draft it STARTED with rather than whatever the refs say when each
// `await` happens to resume.
describe('TagCreatePanel — a save already in flight', () => {
  /** A promise whose settling this test controls, standing in for a slow server. */
  function deferred<T>() {
    let resolve!: (value: T) => void
    const promise = new Promise<T>((r) => {
      resolve = r
    })
    return { promise, resolve }
  }

  async function startSlowSave() {
    const pending = deferred<{ data: { id: string } }>()
    tagApi.createTag.mockReturnValue(pending.promise)
    const wrapper = mountPanel({ initialName: 'First draft' })
    await flushPromises()
    return { wrapper, pending }
  }

  it('refuses to close while the tag is being created', async () => {
    const { wrapper, pending } = await startSlowSave()
    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    // The drawer's own close affordance is withdrawn for the duration...
    expect(wrapper.findComponent({ name: 'Drawer' }).props('showCloseIcon')).toBe(false)
    // ...the footer Cancel with it...
    expect((cancelButton() as HTMLButtonElement).disabled).toBe(true)
    // ...and a close arriving through the drawer anyway (Escape, a stray v-model write) is
    // refused rather than passed on to the host.
    wrapper.findComponent({ name: 'Drawer' }).vm.$emit('update:visible', false)
    await flushPromises()
    expect(
      wrapper.emitted('update:visible'),
      'a save in flight cannot be cancelled out from under itself',
    ).toBeFalsy()

    pending.resolve({ data: { id: 'tag-new' } })
    await flushPromises()
    // Once it is done, the panel closes itself normally.
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('carries the draft it started with, not whatever the fields say when each await resumes', async () => {
    const { wrapper, pending } = await startSlowSave()
    const form = wrapper.findComponent(TagForm)
    form.vm.$emit('acl-add', { perm: 'READ', id: 'ubob', name: 'bob', type: 'USER' })
    await flushPromises()

    saveButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    // The user keeps typing into a panel whose save has not come back yet.
    form.vm.$emit('acl-add', { perm: 'WRITE', id: 'ucarol', name: 'carol', type: 'USER' })
    form.vm.$emit('update:parent', 'tag-red')
    form.vm.$emit('update:color', 'ff0000')
    await flushPromises()

    pending.resolve({ data: { id: 'tag-new' } })
    await flushPromises()

    // Only the grant that was on the draft when Save was pressed is applied to that tag.
    expect(aclApi.addAcl).toHaveBeenCalledTimes(1)
    expect(aclApi.addAcl).toHaveBeenCalledWith('tag-new', 'READ', 'bob', 'USER')
    // And the tag handed to the host is the one that was actually created.
    expect(wrapper.emitted('created')).toEqual([
      [{ id: 'tag-new', name: 'First draft', color: '#2aabd2', parent: null }],
    ])
    wrapper.unmount()
  })
})
