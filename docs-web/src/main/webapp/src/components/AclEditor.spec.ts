import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'

// --- Mock the api/acl module (the component's only network dependency) ---
const aclApi = vi.hoisted(() => ({
  addAcl: vi.fn(),
  deleteAcl: vi.fn(),
  searchAclTargets: vi.fn(),
}))
vi.mock('../api/acl', () => aclApi)

// --- Mock the toast + confirm services so the component mounts without PrimeVue providers ---
const toastAdd = vi.hoisted(() => vi.fn())
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: toastAdd }) }))

// confirmDanger immediately invokes accept() so we can assert the destructive path.
const confirmDanger = vi.hoisted(() => vi.fn((opts: { accept: () => void }) => opts.accept()))
vi.mock('../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger }),
}))

import AclEditor from './AclEditor.vue'

// Minimal i18n stub: return the key (assertions target logic, not copy).
const i18nStub = { t: (k: string) => k }

// Stub PrimeVue components down to the events/props the component wires. Buttons
// forward @click; we key them by aria-label for targeting.
const Button = {
  props: ['label', 'icon', 'ariaLabel', 'disabled', 'loading', 'severity'],
  emits: ['click'],
  template: `<button :aria-label="ariaLabel" :disabled="disabled" @click="$emit('click')">{{ label }}</button>`,
}
const AutoComplete = { props: ['modelValue', 'suggestions'], template: '<div class="autocomplete" />' }
const Select = { props: ['modelValue', 'options'], template: '<div class="select" />' }
const Tag = { props: ['value', 'severity'], template: '<span class="tag">{{ value }}</span>' }

function mountEditor(props: Record<string, unknown>) {
  return mount(AclEditor, {
    props: props as never,
    global: {
      mocks: { $t: i18nStub.t },
      stubs: { Button, AutoComplete, Select, Tag },
      directives: { tooltip: {} },
      provide: {},
      plugins: [
        {
          install(app) {
            app.config.globalProperties.$t = i18nStub.t
          },
        },
      ],
    },
  })
}

// Provide useI18n via mock (component calls useI18n()).
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

describe('AclEditor', () => {
  const acls = [
    { perm: 'WRITE' as const, id: 'u1', name: 'admin', type: 'USER' as const },
    { perm: 'READ' as const, id: 'g1', name: 'team', type: 'GROUP' as const },
  ]

  beforeEach(() => {
    aclApi.addAcl.mockReset().mockResolvedValue({})
    aclApi.deleteAcl.mockReset().mockResolvedValue({})
    aclApi.searchAclTargets.mockReset().mockResolvedValue({ data: { users: [], groups: [] } })
    toastAdd.mockReset()
    confirmDanger.mockClear()
  })

  it('renders one row per ACL', () => {
    const wrapper = mountEditor({ sourceId: 'src1', acls, writable: true })
    expect(wrapper.findAll('.acl-row')).toHaveLength(2)
    expect(wrapper.text()).toContain('admin')
    expect(wrapper.text()).toContain('team')
  })

  it('shows an empty message when there are no ACLs', () => {
    const wrapper = mountEditor({ sourceId: 'src1', acls: [], writable: true })
    expect(wrapper.find('.acl-empty').exists()).toBe(true)
    expect(wrapper.findAll('.acl-row')).toHaveLength(0)
  })

  it('hides the add form and remove buttons when not writable', () => {
    const wrapper = mountEditor({ sourceId: 'src1', acls, writable: false })
    expect(wrapper.find('.acl-add').exists()).toBe(false)
    // No per-row remove buttons.
    expect(wrapper.find('.acl-row button').exists()).toBe(false)
  })

  it('removes an ACL via deleteAcl and emits changed', async () => {
    const wrapper = mountEditor({ sourceId: 'src1', acls, writable: true })
    const removeBtn = wrapper.find('.acl-row button')
    await removeBtn.trigger('click')
    await Promise.resolve()
    await Promise.resolve()
    expect(confirmDanger).toHaveBeenCalledTimes(1)
    // First row is the WRITE/u1 acl.
    expect(aclApi.deleteAcl).toHaveBeenCalledWith('src1', 'WRITE', 'u1')
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  // #88: the immutable predicate marks the owner's mandatory grants as non-removable so the
  // UI never offers a delete the backend would reject. A matching row loses its remove
  // button and shows the lock marker; a non-matching row stays removable.
  it('hides the remove button and shows a lock marker for immutable rows only', () => {
    const immutable = (acl: { name: string | null }) => acl.name === 'admin'
    const wrapper = mountEditor({ sourceId: 'src1', acls, writable: true, immutable })
    const rows = wrapper.findAll('.acl-row')
    // Row 0 (WRITE/admin) is immutable: no remove button, lock marker present.
    expect(rows[0].find('button').exists()).toBe(false)
    expect(rows[0].find('.acl-immutable').exists()).toBe(true)
    // Row 1 (READ/team) is mutable: remove button present, no lock marker.
    expect(rows[1].find('button').exists()).toBe(true)
    expect(rows[1].find('.acl-immutable').exists()).toBe(false)
  })

  // #88: the beforeAdd gate lets a consumer (the tag editor) confirm a grant before it lands.
  // Resolving false cancels the add entirely; resolving true lets it through unchanged.
  it('cancels the add when beforeAdd resolves false', async () => {
    const beforeAdd = vi.fn().mockResolvedValue(false)
    const wrapper = mountEditor({ sourceId: 'src1', acls, writable: true, beforeAdd })
    const target = { id: 'u9', name: 'newuser', type: 'USER' as const }
    wrapper.findComponent('.autocomplete').vm.$emit('update:modelValue', target)
    await wrapper.vm.$nextTick()
    await wrapper.find('.acl-add button').trigger('click')
    await Promise.resolve()
    await Promise.resolve()
    expect(beforeAdd).toHaveBeenCalledWith('READ', target)
    expect(aclApi.addAcl).not.toHaveBeenCalled()
  })

  it('proceeds with the add when beforeAdd resolves true', async () => {
    const beforeAdd = vi.fn().mockResolvedValue(true)
    const wrapper = mountEditor({ sourceId: 'src1', acls, writable: true, beforeAdd })
    const target = { id: 'u9', name: 'newuser', type: 'USER' as const }
    wrapper.findComponent('.autocomplete').vm.$emit('update:modelValue', target)
    await wrapper.vm.$nextTick()
    await wrapper.find('.acl-add button').trigger('click')
    await Promise.resolve()
    await Promise.resolve()
    expect(beforeAdd).toHaveBeenCalledWith('READ', target)
    expect(aclApi.addAcl).toHaveBeenCalledWith('src1', 'READ', 'newuser', 'USER')
  })
})

// #288 — the document editor's create-tag panel edits permissions for a tag that does not
// exist yet, so there is no source id to send a grant to. In DEFERRED mode the editor stops
// being a writer and becomes an input: every add/remove is emitted for the caller to hold and
// apply once the tag has been created. Nothing may reach /acl.
describe('AclEditor — deferred mode (#288)', () => {
  const pending = [{ perm: 'WRITE' as const, id: 'admin', name: 'admin', type: 'USER' as const }]

  beforeEach(() => {
    aclApi.addAcl.mockReset().mockResolvedValue({})
    aclApi.deleteAcl.mockReset().mockResolvedValue({})
    aclApi.searchAclTargets.mockReset().mockResolvedValue({ data: { users: [], groups: [] } })
    toastAdd.mockReset()
    confirmDanger.mockClear()
  })

  it('emits the grant instead of PUTting it, and never touches /acl', async () => {
    const wrapper = mountEditor({ sourceId: '', acls: pending, writable: true, deferred: true })
    const target = { id: 'u9', name: 'bob', type: 'USER' as const }
    wrapper.findComponent('.autocomplete').vm.$emit('update:modelValue', target)
    await wrapper.vm.$nextTick()
    await wrapper.find('.acl-add button').trigger('click')
    await Promise.resolve()
    await Promise.resolve()

    expect(aclApi.addAcl, 'a tag with no id cannot be granted anything yet').not.toHaveBeenCalled()
    expect(wrapper.emitted('add')).toEqual([
      [{ perm: 'READ', id: 'u9', name: 'bob', type: 'USER' }],
    ])
    // `changed` means "re-read the source from the server" — there is nothing to re-read.
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('emits the removal instead of DELETEing it, with no destructive confirmation', async () => {
    // Nothing is persisted, so taking a row back off an unsaved list is not a destructive act.
    const wrapper = mountEditor({
      sourceId: '',
      acls: [...pending, { perm: 'READ' as const, id: 'u9', name: 'bob', type: 'USER' as const }],
      writable: true,
      deferred: true,
    })
    const bobRow = wrapper.findAll('.acl-row').find((r) => r.text().includes('bob'))!
    await bobRow.find('button[aria-label="ui.acl_editor.remove"]').trigger('click')

    expect(aclApi.deleteAcl).not.toHaveBeenCalled()
    expect(confirmDanger).not.toHaveBeenCalled()
    expect(wrapper.emitted('remove')).toEqual([
      [{ perm: 'READ', id: 'u9', name: 'bob', type: 'USER' }],
    ])
  })

  it('still honours the immutability predicate, so the owner row cannot be taken off', async () => {
    const wrapper = mountEditor({
      sourceId: '',
      acls: pending,
      writable: true,
      deferred: true,
      immutable: (acl: { id: string }) => acl.id === 'admin',
    })
    const row = wrapper.find('.acl-row')
    expect(row.find('button[aria-label="ui.acl_editor.remove"]').exists()).toBe(false)
    expect(row.find('.acl-immutable').exists()).toBe(true)
  })
})
