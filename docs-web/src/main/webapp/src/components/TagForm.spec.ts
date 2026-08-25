import { describe, it, expect, beforeAll, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import en from '../locale/en.json'
import AclEditor from './AclEditor.vue'
import TagForm from './TagForm.vue'

// #288 — the tag form is ONE implementation with TWO hosts: the tag management edit page
// (TagEdit.vue) and the document editor's create-tag side panel (TagCreatePanel.vue). Before
// this, the form existed only inside TagEdit's template, which is why the reporter's ask
// ("the tag edit functionality could be a reusable component") was an extraction and not a
// second form. These assertions pin the extracted contract: the fields, the id prefixing both
// hosts depend on, the parent Select's type-to-filter (#14), the permissions section, and the
// two slots the panel needs but the management page does not.

vi.mock('../api/acl', () => ({
  addAcl: vi.fn(),
  deleteAcl: vi.fn(),
  searchAclTargets: vi.fn().mockResolvedValue({ data: { users: [], groups: [] } }),
}))
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))
vi.mock('../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))

beforeAll(() => {
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

const PARENT_OPTIONS = [
  { label: '(none — root level)', value: null },
  { label: 'Alpha', value: 'a' },
]

function mountForm(props: Record<string, unknown> = {}, slots: Record<string, string> = {}) {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  return mount(TagForm, {
    props: {
      name: 'Bravo',
      color: '222222',
      parent: null,
      parentOptions: PARENT_OPTIONS,
      idPrefix: 'tag',
      acl: { sourceId: 'b', entries: [], writable: true },
      ...props,
    } as never,
    slots,
    global: {
      plugins: [i18n, PrimeVue],
      directives: { tooltip: {} },
    },
  })
}

describe('TagForm — the fields both hosts share', () => {
  it('renders name, colour and parent, prefixing the ids the host asked for', () => {
    // `#tag-name` and `#tag-parent` are e2e selectors on the tag management page
    // (e2e/tags.spec.ts). The panel takes a different prefix so the two forms can never
    // collide on one page.
    const wrapper = mountForm()
    expect(wrapper.find('input#tag-name').exists()).toBe(true)
    expect(wrapper.find('label[for="tag-name"]').text()).toBe('Name')
    expect(wrapper.find('#tag-color-label').text()).toBe('Color')
    expect(wrapper.find('label[for="tag-parent"]').text()).toBe('Parent tag')

    const panel = mountForm({ idPrefix: 'tag-create' })
    expect(panel.find('input#tag-create-name').exists()).toBe(true)
    expect(panel.find('input#tag-name').exists()).toBe(false)
  })

  it('keeps type-to-filter on the parent Select (#14, critical at ~350 tags)', () => {
    const select = mountForm().findComponent({ name: 'Select' })
    expect(select.props('filter')).toBe(true)
    expect(select.props('options')).toEqual(PARENT_OPTIONS)
  })

  it('reports every field edit to its host rather than owning the value', async () => {
    const wrapper = mountForm()
    await wrapper.find('input#tag-name').setValue('Charlie')
    expect(wrapper.emitted('update:name')?.at(-1)).toEqual(['Charlie'])

    wrapper.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', 'a')
    expect(wrapper.emitted('update:parent')?.at(-1)).toEqual(['a'])

    wrapper.findComponent({ name: 'ColorPicker' }).vm.$emit('update:modelValue', 'ff0000')
    expect(wrapper.emitted('update:color')?.at(-1)).toEqual(['ff0000'])
  })

  it('previews the tag in its colour, falling back to a placeholder while unnamed', async () => {
    const wrapper = mountForm({ name: '' })
    expect(wrapper.find('.color-preview').text()).toBe('Preview')
    await wrapper.setProps({ name: 'Bravo' })
    expect(wrapper.find('.color-preview').text()).toBe('Bravo')
    // jsdom normalises an inline hex background to its rgb() form.
    expect(wrapper.find('.color-preview').attributes('style')).toContain('rgb(34, 34, 34)')
  })
})

describe('TagForm — the permissions section', () => {
  it('hands the host-owned ACL state straight to the shared AclEditor', () => {
    const immutable = () => true
    const beforeAdd = () => true
    const entries = [{ perm: 'READ' as const, id: 'u1', name: 'bob', type: 'USER' as const }]
    const wrapper = mountForm({
      acl: { sourceId: 'b', entries, writable: true, immutable, beforeAdd },
    })

    expect(wrapper.find('.acl-heading').text()).toBe('Permissions')
    expect(wrapper.find('.acl-desc').text()).toContain('every document that carries this tag')

    const editor = wrapper.findComponent(AclEditor)
    expect(editor.props('sourceId')).toBe('b')
    expect(editor.props('acls')).toEqual(entries)
    expect(editor.props('writable')).toBe(true)
    expect(editor.props('immutable')).toBe(immutable)
    expect(editor.props('beforeAdd')).toBe(beforeAdd)
    expect(editor.props('deferred')).toBeFalsy()
  })

  it('forwards the deferred flag and re-emits what an unsaved tag collects', () => {
    const wrapper = mountForm({
      acl: { sourceId: '', entries: [], writable: true, deferred: true },
    })
    const editor = wrapper.findComponent(AclEditor)
    expect(editor.props('deferred')).toBe(true)

    const grant = { perm: 'READ' as const, id: 'u9', name: 'bob', type: 'USER' as const }
    editor.vm.$emit('add', grant)
    editor.vm.$emit('remove', grant)
    editor.vm.$emit('changed')
    expect(wrapper.emitted('acl-add')).toEqual([[grant]])
    expect(wrapper.emitted('acl-remove')).toEqual([[grant]])
    expect(wrapper.emitted('acl-changed')).toEqual([[]])
  })
})

describe('TagForm — the slots that let one form serve two very different hosts', () => {
  it('renders nothing extra when a host supplies no slot content (the management page)', () => {
    const wrapper = mountForm()
    expect(wrapper.find('.host-lead').exists()).toBe(false)
    expect(wrapper.find('.host-hint').exists()).toBe(false)
    expect(wrapper.find('.host-actions').exists()).toBe(false)
  })

  it('places the lead above the fields, the hint above the ACL editor, actions below them', () => {
    const wrapper = mountForm(
      {},
      {
        lead: '<p class="host-lead">lead</p>',
        'permissions-hint': '<div class="host-hint">hint</div>',
        actions: '<div class="host-actions">actions</div>',
      },
    )
    const html = wrapper.html()
    expect(html.indexOf('host-lead')).toBeLessThan(html.indexOf('tag-name'))
    expect(html.indexOf('host-actions')).toBeGreaterThan(html.indexOf('tag-parent'))
    // The reminder sits between the section's description and the editor it is about (#288
    // mockup), never below the editor where it would read as a result rather than a warning.
    expect(html.indexOf('acl-desc')).toBeLessThan(html.indexOf('host-hint'))
    expect(html.indexOf('host-hint')).toBeLessThan(html.indexOf('acl-editor'))
  })

  it('drops the card chrome in flat mode, and keeps it by default', () => {
    // The management page has always shown the form as two cards; the side panel is already
    // a surface of its own, so the same markup renders flat inside it.
    expect(mountForm().findAll('.tag-form-card.flat')).toHaveLength(0)
    expect(mountForm().findAll('.tag-form-card')).toHaveLength(2)
    expect(mountForm({ flat: true }).findAll('.tag-form-card.flat')).toHaveLength(2)
  })
})
