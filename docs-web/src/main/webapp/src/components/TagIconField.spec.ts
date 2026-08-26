import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import { VueQueryPlugin } from '@tanstack/vue-query'
import en from '../locale/en.json'
import TagForm from './TagForm.vue'

/**
 * The icon field (#287) is mounted through the SHARED TagForm rather than on its own, because
 * that is the contract that matters: both hosts — the tag management page and the document
 * editor's create panel — get the field by hosting the form, and each supplies its own
 * `idPrefix`. Testing the field in isolation would prove it works somewhere neither host is.
 */

vi.mock('../api/acl', () => ({
  addAcl: vi.fn(),
  deleteAcl: vi.fn(),
  searchAclTargets: vi.fn().mockResolvedValue({ data: { users: [], groups: [] } }),
}))
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))
vi.mock('../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))

const listTagIcons = vi.fn()
vi.mock('../api/tag', () => ({
  listTagIcons: (...args: unknown[]) => listTagIcons(...args),
}))

const ICONS = [
  { id: 'icon-a', name: 'Warning', mimetype: 'image/png' },
  { id: 'icon-b', name: 'Vendor', mimetype: 'image/svg+xml' },
]

function mountForm(props: Record<string, unknown> = {}) {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  return mount(TagForm, {
    props: {
      name: 'Bravo',
      color: '222222',
      parent: null,
      parentOptions: [{ label: '(none — root level)', value: null }],
      idPrefix: 'tag',
      acl: { sourceId: 'b', entries: [], writable: true },
      ...props,
    },
    global: { plugins: [i18n, PrimeVue, VueQueryPlugin] },
  })
}

beforeEach(() => {
  listTagIcons.mockReset().mockResolvedValue({ data: { icons: ICONS } })
})

describe('TagForm — the icon field, in both hosts', () => {
  it('prefixes the icon field ids with whatever the host asked for (management page)', async () => {
    const wrapper = mountForm({ idPrefix: 'tag', icon: 'emoji:\u{2B50}' })
    await flushPromises()
    expect(wrapper.find('#tag-icon-label').exists()).toBe(true)
    expect(wrapper.find('#tag-icon-emoji').exists()).toBe(true)
  })

  it('prefixes them with the side panel s own prefix too, so the two never collide', async () => {
    const wrapper = mountForm({ idPrefix: 'tag-new', icon: 'emoji:\u{2B50}' })
    await flushPromises()
    expect(wrapper.find('#tag-new-icon-label').exists()).toBe(true)
    expect(wrapper.find('#tag-new-icon-emoji').exists()).toBe(true)
    // The management page's ids must NOT be present under the panel's prefix.
    expect(wrapper.find('#tag-icon-emoji').exists()).toBe(false)
  })

  it('opens on "None" for a tag with no icon, showing neither panel', async () => {
    const wrapper = mountForm({ icon: null })
    await flushPromises()
    expect(wrapper.find('#tag-icon-emoji').exists()).toBe(false)
    expect(wrapper.find('.icon-set-grid').exists()).toBe(false)
  })

  it('opens on the emoji panel, with the tag s emoji in the box, when it has one', async () => {
    const wrapper = mountForm({ icon: 'emoji:\u{1F525}' })
    await flushPromises()
    expect((wrapper.find('#tag-icon-emoji').element as HTMLInputElement).value).toBe('\u{1F525}')
  })

  it('reports a typed emoji to its host in the STORED form', async () => {
    const wrapper = mountForm({ icon: 'emoji:\u{2B50}' })
    await flushPromises()
    await wrapper.find('#tag-icon-emoji').setValue('\u{1F525}')
    expect(wrapper.emitted('update:icon')?.at(-1)).toEqual(['emoji:\u{1F525}'])
  })

  it('reports NOTHING while what is typed is not one emoji, and says so', async () => {
    const wrapper = mountForm({ icon: 'emoji:\u{2B50}' })
    await flushPromises()
    await wrapper.find('#tag-icon-emoji').setValue('ab')
    // A half-typed or wrong value must not be stored: the server would refuse it anyway, and a
    // Save landing mid-typing has to leave the tag with no icon rather than an invalid one.
    expect(wrapper.emitted('update:icon')?.at(-1)).toEqual([null])
    expect(wrapper.find('#tag-icon-emoji-error').exists()).toBe(true)
    expect(wrapper.find('#tag-icon-emoji').attributes('aria-invalid')).toBe('true')
  })

  it('complains about nothing while the box is merely empty', async () => {
    const wrapper = mountForm({ icon: 'emoji:\u{2B50}' })
    await flushPromises()
    await wrapper.find('#tag-icon-emoji').setValue('')
    expect(wrapper.find('#tag-icon-emoji-error').exists()).toBe(false)
  })

  it('picks an emoji straight from the suggested grid', async () => {
    const wrapper = mountForm({ icon: 'emoji:\u{2B50}' })
    await flushPromises()
    const options = wrapper.findAll('.icon-emoji-option')
    expect(options.length).toBeGreaterThan(10)
    await options[0].trigger('click')
    const emitted = wrapper.emitted('update:icon')?.at(-1) as [string]
    expect(emitted[0].startsWith('emoji:')).toBe(true)
  })

  it('offers the uploaded set and reports the chosen icon as a set reference', async () => {
    const wrapper = mountForm({ icon: 'set:icon-a' })
    await flushPromises()
    const options = wrapper.findAll('.icon-set-option')
    expect(options).toHaveLength(2)
    // The tag's current icon is shown as chosen.
    expect(options[0].attributes('aria-pressed')).toBe('true')
    expect(options[1].attributes('aria-pressed')).toBe('false')

    await options[1].trigger('click')
    expect(wrapper.emitted('update:icon')?.at(-1)).toEqual(['set:icon-b'])
  })

  it('clears the icon when the already-chosen set icon is clicked again', async () => {
    const wrapper = mountForm({ icon: 'set:icon-a' })
    await flushPromises()
    await wrapper.findAll('.icon-set-option')[0].trigger('click')
    expect(wrapper.emitted('update:icon')?.at(-1)).toEqual([null])
  })

  it('says the set is empty rather than showing an empty box', async () => {
    listTagIcons.mockResolvedValue({ data: { icons: [] } })
    const wrapper = mountForm({ icon: 'set:icon-a' })
    await flushPromises()
    expect(wrapper.find('.icon-set-hint').text()).toBe(en.ui.tag_icon.set_empty)
  })

  it('shows the chosen icon in the form s own preview chip', async () => {
    const wrapper = mountForm({ icon: 'emoji:\u{1F525}' })
    await flushPromises()
    expect(wrapper.find('.color-preview .tag-icon-emoji').text()).toBe('\u{1F525}')
  })

  it('leaves the preview chip untouched for a tag with no icon', async () => {
    const wrapper = mountForm({ icon: null })
    await flushPromises()
    expect(wrapper.find('.color-preview .tag-icon').exists()).toBe(false)
    expect(wrapper.find('.color-preview').text()).toBe('Bravo')
  })

  it('stays on the source the user opened, even though it reports no icon yet', async () => {
    // The trap: opening "Icon set" (or "Emoji" with an empty box) legitimately reports NO icon,
    // and a control derived from the stored value would read that back as "None" and snap shut
    // under the user's cursor. Both are states the stored value cannot express.
    const wrapper = mountForm({ icon: 'emoji:\u{2B50}' })
    await flushPromises()

    await wrapper
      .find('.icon-source-toggle')
      .findAll('[role="radio"], button')
      .filter((option) => option.text() === en.ui.tag_icon.from_set)[0]
      .trigger('click')
    // The host answers the emit — this is what v-model does.
    await wrapper.setProps({ icon: null })
    await flushPromises()

    expect(wrapper.emitted('update:icon')?.at(-1)).toEqual([null])
    expect(wrapper.find('.icon-set-grid').exists(), 'the set panel is still open').toBe(true)
  })

  it('DOES re-derive the control when the host loads a different tag', async () => {
    // The other half of the same rule: a prop change that is NOT this component's own echo is a
    // different tag, and the control must follow it.
    const wrapper = mountForm({ icon: 'emoji:\u{2B50}' })
    await flushPromises()
    expect((wrapper.find('#tag-icon-emoji').element as HTMLInputElement).value).toBe('\u{2B50}')

    await wrapper.setProps({ icon: 'set:icon-b' })
    await flushPromises()
    expect(wrapper.find('#tag-icon-emoji').exists()).toBe(false)
    expect(wrapper.findAll('.icon-set-option')[1].attributes('aria-pressed')).toBe('true')

    await wrapper.setProps({ icon: null })
    await flushPromises()
    expect(wrapper.find('.icon-set-grid').exists(), 'a tag with no icon shows neither panel').toBe(
      false,
    )
  })

  it('mounts for a host that passes no icon prop at all', async () => {
    // The prop is optional on purpose: "absent" is what the API says for a tag with no icon.
    const wrapper = mountForm()
    await flushPromises()
    expect(wrapper.find('.tag-icon-field').exists()).toBe(true)
    expect(wrapper.find('#tag-icon-emoji').exists()).toBe(false)
  })
})
