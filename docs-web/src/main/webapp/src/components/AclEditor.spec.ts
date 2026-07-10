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
})
