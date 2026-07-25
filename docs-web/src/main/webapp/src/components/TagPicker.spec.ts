import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import TagPicker from './TagPicker.vue'
import type { Tag } from '../api/tag'

// The shared tag field (#182). These assertions pin the behaviour the edit form had
// and the bulk bar lacked: the {label,value,color} mapping, the unknown-id fallback
// chip, keyboard reachability of the filter, and the id forwarding the e2e selectors
// and `<label for="edit-tags">` depend on.

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

const TAGS: Tag[] = [
  { id: 'tag-red', name: 'Invoice', color: '#d32f2f', parent: null },
  { id: 'tag-green', name: 'Receipt', color: '#2e7d32', parent: null },
  { id: 'tag-blue', name: 'Contract', color: '#1565c0', parent: null },
]

function mountPicker(props: Record<string, unknown> = {}, attachTo?: HTMLElement) {
  return mount(TagPicker, {
    props: { modelValue: [], tags: TAGS, placeholder: 'placeholder', ...props },
    // transition: false keeps the REAL <Transition> instead of VTU's stub — the
    // overlay's @enter hook is where PrimeVue applies autoFilterFocus, so stubbing
    // it out would silently skip the very behaviour the focus test asserts.
    global: { plugins: [PrimeVue], stubs: { transition: false } },
    ...(attachTo ? { attachTo } : {}),
  })
}

describe('TagPicker — option mapping', () => {
  it('derives {label,value,color} options from the raw tag list (colors would drop if omitted)', () => {
    const wrapper = mountPicker()
    const options = wrapper.findComponent({ name: 'MultiSelect' }).props('options')
    expect(options).toEqual([
      { label: 'Invoice', value: 'tag-red', color: '#d32f2f' },
      { label: 'Receipt', value: 'tag-green', color: '#2e7d32' },
      { label: 'Contract', value: 'tag-blue', color: '#1565c0' },
    ])
  })

  it('enables type-to-filter and chip display', () => {
    const multiselect = mountPicker().findComponent({ name: 'MultiSelect' })
    expect(multiselect.props('filter')).toBe(true)
    expect(multiselect.props('display')).toBe('chip')
  })

  it('carries the wrap-enabling class so chips wrap instead of clipping', () => {
    // The scoped :deep(.p-multiselect-label){flex-wrap:wrap} rule is keyed on this class;
    // jsdom cannot compute the scoped style, so guard the hook the wrap rule targets.
    expect(mountPicker().findComponent({ name: 'MultiSelect' }).classes()).toContain('tag-multiselect')
  })
})

describe('TagPicker — chips', () => {
  it('renders a selected tag as a colored chip', async () => {
    const wrapper = mountPicker({ modelValue: ['tag-red', 'tag-blue'] })
    await flushPromises()
    const rendered = wrapper
      .findAllComponents({ name: 'TagBadge' })
      .map((b) => ({ name: b.props('name'), color: b.props('color') }))
    expect(rendered).toEqual([
      { name: 'Invoice', color: '#d32f2f' },
      { name: 'Contract', color: '#1565c0' },
    ])
  })

  it('renders an unknown tag id as a visible, removable fallback chip', async () => {
    // 'ghost' is not in TAGS — e.g. a tag on the doc not in the loaded list, or a
    // timing gap before the list populates. It must NOT vanish silently.
    const wrapper = mountPicker({ modelValue: ['tag-red', 'ghost'] })
    await flushPromises()
    const badges = wrapper.findAllComponents({ name: 'TagBadge' })
    expect(badges.length).toBe(2)
    expect(badges[1].props('name')).toBe('ghost')

    // Removing the fallback chip emits the selection without exactly that id.
    await badges[1].find('.tag-remove-btn').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([['tag-red']])
  })
})

describe('TagPicker — forwarded identity and limits', () => {
  it('forwards id onto the MultiSelect root element (#edit-tags selector, <label for>)', () => {
    const wrapper = mountPicker({ id: 'edit-tags' })
    expect(wrapper.findComponent({ name: 'MultiSelect' }).attributes('id')).toBe('edit-tags')
    expect(wrapper.find('#edit-tags').exists()).toBe(true)
  })

  it('forwards inputId onto the focusable input', () => {
    const wrapper = mountPicker({ inputId: 'tags-input' })
    expect(wrapper.find('input#tags-input').exists()).toBe(true)
  })

  it('forwards selectionLimit, and a limit of 1 makes a second selection impossible', async () => {
    const wrapper = mountPicker({ selectionLimit: 1, modelValue: ['tag-red'] })
    const multiselect = wrapper.findComponent({ name: 'MultiSelect' })
    expect(multiselect.props('selectionLimit')).toBe(1)
    // PrimeVue disables every unselected option once the limit is reached, so a
    // second tag cannot enter the model — one-tag-per-apply is structural, not a
    // convention the caller has to remember.
    const vm = multiselect.vm as unknown as {
      maxSelectionLimitReached: boolean
      isOptionDisabled: (o: unknown) => boolean
    }
    expect(vm.maxSelectionLimitReached).toBe(true)
    expect(vm.isOptionDisabled({ label: 'Receipt', value: 'tag-green', color: '#2e7d32' })).toBe(true)
    expect(vm.isOptionDisabled({ label: 'Invoice', value: 'tag-red', color: '#d32f2f' })).toBe(false)
  })

  it('leaves the selection uncapped when no limit is given', () => {
    const multiselect = mountPicker().findComponent({ name: 'MultiSelect' })
    expect(multiselect.props('selectionLimit')).toBeNull()
  })
})

describe('TagPicker — keyboard reachability (#171 acceptance)', () => {
  it('opens the overlay from the exposed show(), with the filter rendered and autoFilterFocus armed', async () => {
    // The two halves of #171's keyboard path: a container-driven open (no click
    // anywhere — the bulk popover cannot click its own picker) plus autoFilterFocus,
    // which is what puts the caret in the filter once that overlay opens.
    //
    // The `document.activeElement` half of the acceptance is asserted in e2e
    // (bulk.spec.ts, desktop and mobile) rather than here: jsdom runs PrimeVue's
    // overlay `@enter` hook before the teleported node is focusable, so its focus()
    // call is a no-op in this environment while working in a real browser.
    const host = document.createElement('div')
    document.body.appendChild(host)
    const wrapper = mountPicker({ filterPlaceholder: 'filter' }, host)

    const multiselect = wrapper.findComponent({ name: 'MultiSelect' })
    expect(multiselect.props('autoFilterFocus')).toBe(true)
    expect((multiselect.vm as unknown as { overlayVisible: boolean }).overlayVisible).toBe(false)

    ;(wrapper.vm as unknown as { show: () => void }).show()
    await flushPromises()

    expect((multiselect.vm as unknown as { overlayVisible: boolean }).overlayVisible).toBe(true)
    expect(document.querySelector('.p-multiselect-filter')).not.toBeNull()

    // hide() is the other half of the exposed contract (the popover closes on apply).
    ;(wrapper.vm as unknown as { hide: () => void }).hide()
    // PrimeVue defers the close by a macrotask, so flushPromises alone is not enough.
    await new Promise((resolve) => setTimeout(resolve, 0))
    await flushPromises()
    expect((multiselect.vm as unknown as { overlayVisible: boolean }).overlayVisible).toBe(false)

    wrapper.unmount()
    host.remove()
  })
})
