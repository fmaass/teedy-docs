import { describe, it, expect, beforeAll, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import BulkActionBar from './BulkActionBar.vue'
import TagPicker from './TagPicker.vue'
import type { Tag } from '../api/tag'

// The bulk bar had no unit spec at all: it froze on a single-value, uncoloured,
// keyboard-unreachable `Select` while the edit form's picker gained filtering,
// coloured chips and #171's keyboard path (#182). These assertions pin the shared
// picker AND the cardinality contract the batching Apply depends on — one tag per
// invocation, emitted exactly once.

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

// PrimeVue's Select (the language action) probes window.matchMedia for its responsive
// overlay; jsdom does not provide it. Install a minimal stub for this environment only.
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

const TAGS: Tag[] = [
  { id: 'tag-red', name: 'Invoice', color: '#d32f2f', parent: null },
  { id: 'tag-green', name: 'Receipt', color: '#2e7d32', parent: null },
]

// Popover renders its content inline (no teleport/overlay in jsdom) and still emits
// `show`, which is the hook the picker's keyboard open hangs off.
const PopoverStub = {
  name: 'Popover',
  emits: ['show'],
  template: '<div class="popover-stub"><slot /></div>',
  methods: { show() {}, hide() {}, toggle() {} },
}

function mountBar(props: Record<string, unknown> = {}) {
  return mount(BulkActionBar, {
    props: { count: 2, tags: TAGS, progress: null, downloading: false, ...props },
    global: {
      plugins: [PrimeVue],
      stubs: { Popover: PopoverStub, transition: false },
    },
  })
}

function tagPopover(wrapper: ReturnType<typeof mountBar>) {
  // The tag popover is the first of the two (tag, then language).
  return wrapper.findAllComponents(PopoverStub)[0]
}

// The Apply button inside popover #index. Selected chips render their own remove
// <button> ahead of it, so match on the label rather than on document order.
function applyButton(wrapper: ReturnType<typeof mountBar>, index: number) {
  const button = wrapper
    .findAll('.bulk-popover')
    [index].findAll('button')
    .find((b) => b.text().includes('ui.bulk.apply'))
  expect(button).toBeDefined()
  return button!
}

// The bar's first action button opens the tag popover.
function addTagButton(wrapper: ReturnType<typeof mountBar>) {
  return wrapper.findAll('.bulk-actions button')[0]
}

describe('BulkActionBar — the shared tag picker (#182)', () => {
  it('renders the shared TagPicker for the tag action', () => {
    const picker = mountBar().findComponent(TagPicker)
    expect(picker.exists()).toBe(true)
    expect(picker.props('tags')).toEqual(TAGS)
  })

  it('renders selected tags as colored chips (the plain Select could not)', async () => {
    const wrapper = mountBar()
    const picker = wrapper.findComponent(TagPicker)
    picker.vm.$emit('update:modelValue', ['tag-red'])
    await flushPromises()
    const badges = wrapper.findAllComponents({ name: 'TagBadge' })
    expect(badges.map((b) => ({ name: b.props('name'), color: b.props('color') }))).toEqual([
      { name: 'Invoice', color: '#d32f2f' },
    ])
  })

  it('offers type-to-filter over the tag list, through the picker\'s own search box (#286)', () => {
    // PrimeVue's built-in filter is off: its text is component-private, so the clear (×)
    // could not reach it. The bulk bar supplies that clear's accessible name, since the
    // shared picker holds no locale keys of its own.
    const picker = mountBar().findComponent(TagPicker)
    expect(picker.findComponent({ name: 'MultiSelect' }).props('filter')).toBe(false)
    expect(picker.props('clearFilterLabel')).toBe('document.search_clear')
  })

  it('opens the picker overlay when the popover shows, so the filter is keyboard-reachable', async () => {
    const wrapper = mountBar()
    const multiselect = wrapper.findComponent(TagPicker).findComponent({ name: 'MultiSelect' })
    expect((multiselect.vm as unknown as { overlayVisible: boolean }).overlayVisible).toBe(false)

    // The picker lives inside a lazily-teleported Popover, so a mount-time auto-open
    // cannot reach it — #171's fix is to open it from the container's `show` event.
    tagPopover(wrapper).vm.$emit('show')
    await flushPromises()

    expect((multiselect.vm as unknown as { overlayVisible: boolean }).overlayVisible).toBe(true)
    // And the caret is in the picker's own search box, so the next keystroke searches
    // (PrimeVue's autoFilterFocus targeted a filter input that no longer exists — #286).
    expect(document.activeElement).toBe(document.querySelector('input.tp-filter-input'))
  })
})

describe('BulkActionBar — one tag per apply (cardinality)', () => {
  it('caps the picker at one tag, so a multi-selected state cannot exist', () => {
    const picker = mountBar().findComponent(TagPicker)
    expect(picker.props('selectionLimit')).toBe(1)
    expect(picker.findComponent({ name: 'MultiSelect' }).props('selectionLimit')).toBe(1)
  })

  it('Apply emits addTag EXACTLY ONCE with a single tag id string', async () => {
    const wrapper = mountBar()
    wrapper.findComponent(TagPicker).vm.$emit('update:modelValue', ['tag-green'])
    await flushPromises()

    await applyButton(wrapper, 0).trigger('click')
    await flushPromises()

    const emitted = wrapper.emitted('addTag')
    expect(emitted).toHaveLength(1)
    expect(emitted![0]).toEqual(['tag-green'])
    expect(typeof emitted![0][0]).toBe('string')
  })

  it('Apply is inert while nothing is picked', async () => {
    const wrapper = mountBar()
    const apply = applyButton(wrapper, 0)
    expect(apply.attributes('disabled')).toBeDefined()
    await apply.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('addTag')).toBeUndefined()
  })

  it('re-opening the tag popover clears the previous pick', async () => {
    const wrapper = mountBar()
    const picker = wrapper.findComponent(TagPicker)
    picker.vm.$emit('update:modelValue', ['tag-red'])
    await flushPromises()
    expect(picker.props('modelValue')).toEqual(['tag-red'])

    await addTagButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(TagPicker).props('modelValue')).toEqual([])
  })
})

describe('BulkActionBar — the language action is untouched', () => {
  it('still uses a plain single-value Select, not the tag picker', async () => {
    const wrapper = mountBar()
    // Exactly one TagPicker (the tag popover); the language popover keeps its Select.
    expect(wrapper.findAllComponents(TagPicker)).toHaveLength(1)
    const selects = wrapper.findAllComponents({ name: 'Select' })
    expect(selects).toHaveLength(1)
    expect(selects[0].props('optionValue')).toBe('value')
  })

  it('Apply on the language popover emits setLanguage once with a single string', async () => {
    const wrapper = mountBar()
    const select = wrapper.findComponent({ name: 'Select' })
    select.vm.$emit('update:modelValue', 'fra')
    await flushPromises()

    await applyButton(wrapper, 1).trigger('click')
    await flushPromises()

    expect(wrapper.emitted('setLanguage')).toEqual([['fra']])
  })
})

// #294: bulk duplicate. The bar is the ONLY place this action is reachable from, and
// the view is its only listener, so the emit's name and the button's presence have to
// be pinned here — an inert or renamed button would otherwise ship green.
describe('BulkActionBar — the duplicate action (#294)', () => {
  function duplicateButton(wrapper: ReturnType<typeof mountBar>) {
    return wrapper
      .findAll('.bulk-actions button')
      .find((b) => b.text().includes('ui.bulk.duplicate'))
  }

  it('renders a Duplicate action alongside the other bulk actions', () => {
    expect(duplicateButton(mountBar())).toBeDefined()
  })

  it('clicking Duplicate emits `duplicate` exactly once, with no payload', async () => {
    const wrapper = mountBar()
    await duplicateButton(wrapper)!.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('duplicate')).toEqual([[]])
  })

  it('is disabled while another bulk op is in flight, so a batch cannot be double-started', () => {
    const inProgress = mountBar({ progress: [1, 2] as [number, number] })
    expect(duplicateButton(inProgress)!.attributes('disabled')).toBeDefined()
    const zipping = mountBar({ downloading: true })
    expect(duplicateButton(zipping)!.attributes('disabled')).toBeDefined()
  })
})

// #293 — the tag-reduction run. The bar is where the reporter asked for it ("add a button to the
// document overview top bar … select the documents … run the tag-cleanup on them"), and it renders
// only while something is selected, so the action costs the default list view no DOM at all.
describe('BulkActionBar — the tag reduction action (#293)', () => {
  function reduceButton(wrapper: ReturnType<typeof mountBar>) {
    return wrapper
      .findAll('.bulk-actions button')
      .find((button) => button.text().includes('ui.bulk.reduce_tags'))
  }

  it('offers the reduction and asks for it exactly once per click', async () => {
    const wrapper = mountBar()
    const button = reduceButton(wrapper)
    expect(button, 'the bar carries a reduce-tags action').toBeDefined()

    await button!.trigger('click')

    // No payload: the selection is the view's, and the dialog reads it from there.
    expect(wrapper.emitted('reduceTags')).toEqual([[]])
  })

  it('is unavailable while another bulk operation is in flight', () => {
    const wrapper = mountBar({ progress: [1, 4] as [number, number] })
    expect((reduceButton(wrapper)!.element as HTMLButtonElement).disabled).toBe(true)
  })
})
