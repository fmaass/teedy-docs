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

function mountPicker(
  props: Record<string, unknown> = {},
  attachTo?: HTMLElement,
  primeOptions?: Record<string, unknown>,
) {
  return mount(TagPicker, {
    props: {
      modelValue: [],
      tags: TAGS,
      placeholder: 'placeholder',
      // The i18n mock returns the key, so the rendered clear reads as its locale key.
      clearFilterLabel: 'document.search_clear',
      ...props,
    },
    // transition: false keeps the REAL <Transition> instead of VTU's stub — the
    // overlay's @enter hook is where PrimeVue applies autoFilterFocus, so stubbing
    // it out would silently skip the very behaviour the focus test asserts.
    global: {
      plugins: [primeOptions ? [PrimeVue, primeOptions] : PrimeVue],
      stubs: { transition: false },
    },
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

  it('owns the filter box instead of PrimeVue\'s, and keeps chip display', () => {
    // #286: PrimeVue's built-in filter keeps its text component-private, so no clear
    // control can reach it (the #274 wall). The picker therefore disables it and renders
    // its own search box in the overlay header.
    const multiselect = mountPicker().findComponent({ name: 'MultiSelect' })
    expect(multiselect.props('filter')).toBe(false)
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
  it('opens the overlay from the exposed show(), landing the caret in the owned filter box', async () => {
    // The two halves of #171's keyboard path: a container-driven open (no click
    // anywhere — the bulk popover cannot click its own picker) plus the caret landing
    // in the search box, which is what makes the next keystroke type into it.
    //
    // PrimeVue's own `autoFilterFocus` focuses ITS filter input, which no longer
    // exists (#286 owns the box), so the picker focuses the owned input on `show`.
    const host = document.createElement('div')
    document.body.appendChild(host)
    const wrapper = mountPicker({ filterPlaceholder: 'filter' }, host)

    const multiselect = wrapper.findComponent({ name: 'MultiSelect' })
    expect((multiselect.vm as unknown as { overlayVisible: boolean }).overlayVisible).toBe(false)

    ;(wrapper.vm as unknown as { show: () => void }).show()
    await flushPromises()

    expect((multiselect.vm as unknown as { overlayVisible: boolean }).overlayVisible).toBe(true)
    const filter = document.querySelector('input.tp-filter-input')
    expect(filter).not.toBeNull()
    expect(document.activeElement).toBe(filter)

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

// #286 — the reporter asked for the (×) the main search bar has on the TAG SEARCH box of
// the document-edit picker: it clears the TYPED TEXT only. The text deliberately survives
// adding a tag (that is how several tags get added from one search), so the × is the way
// out when that persistence is not what you want. PrimeVue keeps its own filter value
// component-private (the #274 wall), so the picker owns the box.
describe('TagPicker — owned tag search box and its clear (#286)', () => {
  async function openPicker(
    props: Record<string, unknown> = {},
    primeOptions?: Record<string, unknown>,
  ) {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const wrapper = mountPicker({ filterPlaceholder: 'filter', ...props }, host, primeOptions)
    ;(wrapper.vm as unknown as { show: () => void }).show()
    await flushPromises()
    return { wrapper, host }
  }

  // The overlay is teleported to <body>, so it is queried through the document rather
  // than the wrapper.
  const filterBox = () => document.querySelector('input.tp-filter-input') as HTMLInputElement | null
  const clearButton = () => document.querySelector('.tp-filter-clear') as HTMLElement | null
  const optionLabels = () =>
    Array.from(document.querySelectorAll('li[role="option"]')).map((li) => li.textContent?.trim())

  async function type(text: string) {
    const input = filterBox()
    expect(input, 'the picker renders its own search box').not.toBeNull()
    input!.value = text
    input!.dispatchEvent(new Event('input'))
    await flushPromises()
  }

  it('winnows the option list as text is typed into the owned search box', async () => {
    const { wrapper, host } = await openPicker()
    expect(optionLabels()).toEqual(['Invoice', 'Receipt', 'Contract'])

    // Lower-case fragment against a capitalised name: the match is case-insensitive,
    // as PrimeVue's built-in contains filter was.
    await type('rec')
    expect(optionLabels()).toEqual(['Receipt'])

    wrapper.unmount()
    host.remove()
  })

  it('keeps the search box wired to the option list, live result count included', async () => {
    // Everything PrimeVue attached to the filter input it no longer renders: the
    // searchbox role, the aria-owns link to the listbox, and the polite live region that
    // tells a screen-reader user how many options survived the search. Losing the region
    // would make the winnowing silent for exactly the users who cannot see it.
    const { wrapper, host } = await openPicker()
    const input = filterBox()!
    const list = document.querySelector('ul[role="listbox"]') as HTMLElement
    expect(input.getAttribute('role')).toBe('searchbox')
    expect(input.getAttribute('aria-owns')).toBe(list.id)

    const liveRegion = () => document.querySelector('[role="status"][aria-live="polite"]')
    expect(liveRegion()?.textContent).toContain('3')

    await type('rec')
    expect(liveRegion()?.textContent).toContain('1')

    await type('zzz')
    expect(liveRegion()?.textContent?.trim(), 'an empty result still announces').not.toBe('')

    wrapper.unmount()
    host.remove()
  })

  it('says nothing MATCHED when a search comes up empty, not that there are no tags', async () => {
    // PrimeVue chooses between the two messages off ITS filter value, which is now
    // permanently empty — left alone it would tell a user searching a well-stocked tag
    // list that no tags exist. Sentinel strings, so the assertion pins the CHOICE rather
    // than PrimeVue's shipped English.
    const { wrapper, host } = await openPicker(
      {},
      { locale: { emptyMessage: 'NO-TAGS-AT-ALL', emptySearchMessage: 'NOTHING-MATCHED' } },
    )
    await type('zzz')
    expect(optionLabels()).toEqual(['NOTHING-MATCHED'])

    clearButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(optionLabels()).toEqual(['Invoice', 'Receipt', 'Contract'])
    wrapper.unmount()
    host.remove()

    // The other arm: a genuinely empty tag list still says so.
    const empty = await openPicker(
      { tags: [] },
      { locale: { emptyMessage: 'NO-TAGS-AT-ALL', emptySearchMessage: 'NOTHING-MATCHED' } },
    )
    expect(optionLabels()).toEqual(['NO-TAGS-AT-ALL'])
    empty.wrapper.unmount()
    empty.host.remove()
  })

  it('still commits a tag from the search box by keyboard alone (type, ArrowDown, Enter)', async () => {
    // The keys PrimeVue's own filter input handled — arrow to a match, Enter to take it —
    // have to keep working from a box PrimeVue no longer renders, or #171's keyboard path
    // dies quietly: focus lands in the search box, typing narrows, and nothing commits.
    const { wrapper, host } = await openPicker()
    await type('rec')
    const input = filterBox()!
    for (const code of ['ArrowDown', 'Enter']) {
      input.dispatchEvent(new KeyboardEvent('keydown', { code, bubbles: true, cancelable: true }))
      await flushPromises()
    }
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([['tag-green']])

    wrapper.unmount()
    host.remove()
  })

  it('drops a stale option highlight when the search narrows under it', async () => {
    // PrimeVue's native filter resets `focusedOptionIndex` on every keystroke; an owned
    // box that only feeds the option list leaves the highlight parked on an index the
    // narrowed list no longer has — aria-activedescendant then names a row that is not
    // on screen, and Enter commits against that phantom index.
    const { wrapper, host } = await openPicker()
    const input = filterBox()!
    for (const code of ['ArrowDown', 'ArrowDown']) {
      input.dispatchEvent(new KeyboardEvent('keydown', { code, bubbles: true, cancelable: true }))
      await flushPromises()
    }
    expect(optionLabels()).toEqual(['Invoice', 'Receipt', 'Contract'])

    // Narrow to ONE option, which the old highlight (index 1) is past the end of.
    await type('con')
    expect(optionLabels()).toEqual(['Contract'])

    const activeId = input.getAttribute('aria-activedescendant')
    expect(
      activeId === null || document.getElementById(activeId) !== null,
      `aria-activedescendant "${activeId}" points at no rendered option`,
    ).toBe(true)

    input.dispatchEvent(new KeyboardEvent('keydown', { code: 'Enter', bubbles: true, cancelable: true }))
    await flushPromises()

    // Enter may commit the visible survivor or nothing at all, but never a tag the
    // search had already removed from view.
    const committed = wrapper.emitted('update:modelValue')?.at(-1)
    expect(committed === undefined || JSON.stringify(committed) === '[["tag-blue"]]').toBe(true)

    wrapper.unmount()
    host.remove()
  })

  it('shows a clear control labelled from document.search_clear only once text is typed', async () => {
    const { wrapper, host } = await openPicker()
    expect(clearButton(), 'an empty box offers no clear').toBeNull()

    await type('rec')
    const clear = clearButton()
    expect(clear, 'a clear appears once the box has text').not.toBeNull()
    // A REAL button in the accessibility tree with an accessible name — not an icon in
    // PrimeVue's aria-hidden InputIcon slot, which is where #274 first put it.
    expect(clear!.tagName).toBe('BUTTON')
    expect(clear!.textContent?.trim()).toBe('document.search_clear')
    expect(clear!.closest('[aria-hidden="true"]')).toBeNull()

    wrapper.unmount()
    host.remove()
  })

  it('clears the typed text, restores every option and keeps the caret in the box', async () => {
    const { wrapper, host } = await openPicker()
    await type('rec')
    expect(optionLabels()).toEqual(['Receipt'])

    // Pressing the clear is what takes the caret out of the box in a real browser, so
    // model that: blur first, then click. Without the restore this assertion would pass
    // on the focus the overlay-open already put there, proving nothing.
    filterBox()!.blur()
    expect(document.activeElement).not.toBe(filterBox())

    const mousedown = new MouseEvent('mousedown', { bubbles: true, cancelable: true })
    clearButton()!.dispatchEvent(mousedown)
    expect(mousedown.defaultPrevented, 'the press itself does not steal the caret').toBe(true)
    clearButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(filterBox()!.value, 'the clear empties the search box').toBe('')
    expect(optionLabels(), 'and the full option list is back').toEqual([
      'Invoice',
      'Receipt',
      'Contract',
    ])
    expect(document.activeElement, 'the caret stays in the box for the next search').toBe(
      filterBox(),
    )
    expect(clearButton(), 'the clear goes away with the text').toBeNull()

    wrapper.unmount()
    host.remove()
  })

  it('keeps the typed text when a tag is selected, so several tags can be added from one search', async () => {
    const { wrapper, host } = await openPicker()
    await type('rec')
    const option = document.querySelector('li[role="option"]') as HTMLElement
    option.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([['tag-green']])

    // The parent owns the model, so mirror the selection back the way a consumer does.
    await wrapper.setProps({ modelValue: ['tag-green'] })
    await flushPromises()

    expect(filterBox()!.value, 'the search text survives adding a tag').toBe('rec')
    expect(optionLabels(), 'and the list stays winnowed to that search').toEqual(['Receipt'])

    wrapper.unmount()
    host.remove()
  })
})

// #288 — creating a tag without leaving the document edit view starts HERE: when the typed
// search matches no existing tag, the overlay offers a create row beneath the (empty) result
// list. The row is opt-in per caller: the bulk action bar applies ONE existing tag and must
// not grow a create affordance, so it appears only when a caller supplies the label builder.
describe('TagPicker — create-tag row (#288)', () => {
  async function openPicker(props: Record<string, unknown> = {}) {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const wrapper = mountPicker({ filterPlaceholder: 'filter', ...props }, host)
    ;(wrapper.vm as unknown as { show: () => void }).show()
    await flushPromises()
    return { wrapper, host }
  }

  const filterBox = () => document.querySelector('input.tp-filter-input') as HTMLInputElement | null
  const createRow = () => document.querySelector('.tp-create-row') as HTMLElement | null

  async function type(text: string) {
    const input = filterBox()!
    input.value = text
    input.dispatchEvent(new Event('input'))
    await flushPromises()
  }

  // The label is built by the caller (TagPicker adds no locale keys of its own — its own
  // contract note), so the spec passes a sentinel builder and asserts the typed text reaches it.
  const label = (name: string) => `CREATE:${name}`

  it('offers no create row until something that matches nothing is typed', async () => {
    const { wrapper, host } = await openPicker({ createTagLabel: label })
    expect(createRow(), 'an empty search box offers no create row').toBeNull()

    // A search that still MATCHES an existing tag is a selection, not a creation.
    await type('rec')
    expect(createRow(), 'a search with results offers no create row').toBeNull()

    wrapper.unmount()
    host.remove()
  })

  it('offers the create row, labelled with the typed text, once nothing matches', async () => {
    const { wrapper, host } = await openPicker({ createTagLabel: label })
    await type('Insurance 2026')

    const row = createRow()
    expect(row, 'a search that matches no tag offers to create it').not.toBeNull()
    expect(row!.tagName, 'a real button, not a decorated div').toBe('BUTTON')
    expect(row!.textContent).toContain('CREATE:Insurance 2026')

    wrapper.unmount()
    host.remove()
  })

  it('trims the typed text before it becomes a tag name', async () => {
    const { wrapper, host } = await openPicker({ createTagLabel: label })
    await type('  Insurance 2026  ')
    expect(createRow()!.textContent).toContain('CREATE:Insurance 2026')
    wrapper.unmount()
    host.remove()
  })

  it('never offers the create row to a caller that did not ask for it (the bulk bar)', async () => {
    const { wrapper, host } = await openPicker()
    await type('Insurance 2026')
    expect(createRow(), 'no label builder, no create affordance').toBeNull()
    wrapper.unmount()
    host.remove()
  })

  it('emits the trimmed name and closes the overlay when the row is chosen', async () => {
    const { wrapper, host } = await openPicker({ createTagLabel: label })
    await type('  Insurance 2026  ')
    createRow()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    // PrimeVue defers the overlay close by a macrotask.
    await new Promise((resolve) => setTimeout(resolve, 0))
    await flushPromises()

    expect(wrapper.emitted('create')).toEqual([['Insurance 2026']])
    expect(wrapper.emitted('update:modelValue'), 'creating is not selecting').toBeUndefined()
    const multiselect = wrapper.findComponent({ name: 'MultiSelect' })
    expect(
      (multiselect.vm as unknown as { overlayVisible: boolean }).overlayVisible,
      'the overlay gets out of the way of the panel it opens',
    ).toBe(false)

    wrapper.unmount()
    host.remove()
  })

  it('keeps the typed text in the search box after the create row is chosen', async () => {
    // Deliberate: the text is what the panel pre-fills its Name with, cancelling the panel
    // must not throw the user's typing away, and after a successful create the very same
    // search now matches the new tag — so re-opening the picker shows it.
    const { wrapper, host } = await openPicker({ createTagLabel: label })
    await type('Insurance 2026')
    createRow()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await new Promise((resolve) => setTimeout(resolve, 0))
    await flushPromises()

    ;(wrapper.vm as unknown as { show: () => void }).show()
    await flushPromises()
    expect(filterBox()!.value).toBe('Insurance 2026')

    wrapper.unmount()
    host.remove()
  })
})
