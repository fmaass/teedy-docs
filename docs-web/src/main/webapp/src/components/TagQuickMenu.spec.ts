import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import TagQuickMenu from './TagQuickMenu.vue'
import { type Tag } from '../api/tag'
import { type DocumentListItem } from '../api/document'

// vue-i18n stub: echo the key so assertions target logic, not copy.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

function tag(id: string, name: string): Tag {
  return { id, name, color: '#123456', parent: null }
}

const allTags = [
  tag('t1', 'Invoice'),
  tag('t2', 'Receipt'),
  tag('t3', 'Bank'),
  tag('t4', 'Archive'),
  tag('t5', 'Contract'),
  tag('t6', 'Draft'),
]

function makeDoc(tagIds: string[]): DocumentListItem {
  return {
    id: 'doc1',
    title: 'Doc',
    tags: tagIds.map((id) => allTags.find((t) => t.id === id)!),
  } as DocumentListItem
}

// The menu's "open in new tab" item is a <router-link> to the document view — the
// route must resolve for it to render a real href (#194).
function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/document', name: 'documents', component: { template: '<div />' } },
      { path: '/document/view/:id', name: 'document-view', component: { template: '<div />' } },
    ],
  })
}

// Records the imperative open PrimeVue's Popover exposes, with the arguments it was given
// (the anchor it must position against and hit-test clicks with).
const popoverShow = vi.fn()

// Stub Popover so its content renders inline (no teleport/overlay in jsdom), and expose
// show/hide so the component's defineExpose contract still works. Declared out here so a
// test can grab the instance and emit the popover's own `show`/`hide` events.
const PopoverStub = {
  template: '<div class="popover-stub"><slot /></div>',
  methods: {
    show(event: Event, target?: HTMLElement) {
      popoverShow(event, target)
    },
    hide() {},
    toggle() {},
  },
}

// Records the auto-open (#171) the component performs on the Select through its ref.
const selectShow = vi.fn()

// Whether the stubbed Select mounts its filter input when opened. Set false to model the
// overlay never coming up (a popover dismissed mid-open), which is the state the #204
// focus step must survive.
let selectMountsFilterInput = true

// Stands in for the InputText the real Select exposes as `$refs.filterInput` — a
// component ref, so the app reaches its element through `.$el` exactly as in production.
const FilterInputStub = { template: '<input class="stub-filter-input" />' }

// Stub Select so we can read the `options` it is handed without booting the full overlay;
// expose an update button to simulate a selection, and a `show()` matching the real
// component's imperative open (which mounts the filter one tick later).
const SelectStub = {
  props: ['options', 'modelValue'],
  emits: ['update:modelValue'],
  components: { FilterInputStub },
  data() {
    return { opened: false }
  },
  methods: {
    show(this: { opened: boolean }) {
      selectShow()
      this.opened = selectMountsFilterInput
    },
  },
  template:
    '<div class="select-stub" :data-count="options.length"><FilterInputStub v-if="opened" ref="filterInput" /><button v-for="o in options" :key="o.id" class="opt" :data-id="o.id" @click="$emit(\'update:modelValue\', o.id)">{{ o.name }}</button></div>',
}

function mountMenu(props: Partial<InstanceType<typeof TagQuickMenu>['$props']> = {}) {
  return mount(TagQuickMenu, {
    props: {
      document: makeDoc(['t1']),
      allTags,
      tagCounts: { t2: 30, t3: 10, t4: 5, t5: 2, t6: 1 },
      ...props,
    },
    global: {
      plugins: [PrimeVue, makeRouter()],
      stubs: { Popover: PopoverStub, Select: SelectStub },
    },
  })
}

beforeEach(() => {
  selectShow.mockClear()
  popoverShow.mockClear()
  selectMountsFilterInput = true
})

// Resolves after the animation-frame callbacks of one rendering update — the point the
// component's own open is scheduled for.
function afterFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()))
}

// A row standing in for the one that was right-clicked. It has to be IN the document: the
// deferred open refuses a detached anchor, because a detached row is one the list replaced
// while the open was waiting for its frame (#213). Cleared after each test so no stray rows
// outlive the case that made them.
const attachedRows: HTMLElement[] = []

function attachedRow(): HTMLElement {
  const row = document.createElement('tr')
  document.body.appendChild(row)
  attachedRows.push(row)
  return row
}

afterEach(() => {
  attachedRows.splice(0).forEach((row) => row.remove())
})

function rightClickOn(anchor: HTMLElement): Event {
  return { currentTarget: anchor, target: anchor } as unknown as Event
}

describe('TagQuickMenu', () => {
  it('offers only assignable (not-yet-assigned) tags in the search select', () => {
    const wrapper = mountMenu({ document: makeDoc(['t1', 't3']) })
    const select = wrapper.find('.select-stub')
    // 6 total - 2 assigned = 4 assignable.
    expect(select.attributes('data-count')).toBe('4')
    const ids = select.findAll('.opt').map((b) => b.attributes('data-id'))
    expect(ids).not.toContain('t1')
    expect(ids).not.toContain('t3')
  })

  it('renders the top-5 most-used assignable tags as quick-add chips, most-used first', () => {
    // Assigned t1 → assignable: t2..t6. Counts: t2:30 t3:10 t4:5 t5:2 t6:1.
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    const chips = wrapper.findAll('.tqm-chip')
    expect(chips).toHaveLength(5)
    expect(chips.map((c) => c.text())).toEqual([
      'Receipt',
      'Bank',
      'Archive',
      'Contract',
      'Draft',
    ])
  })

  it('emits addTag when a quick-add chip is clicked', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    await wrapper.findAll('.tqm-chip')[0].trigger('click')
    expect(wrapper.emitted('addTag')).toEqual([['t2']])
  })

  it('emits addTag when a tag is chosen from the search select', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    await wrapper.find('.opt[data-id="t5"]').trigger('click')
    expect(wrapper.emitted('addTag')).toEqual([['t5']])
  })

  it('shows the assigned tags with a remove affordance and emits removeTag', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1', 't3']) })
    const removeBtns = wrapper.findAll('.tqm-assigned .tag-remove-btn')
    expect(removeBtns).toHaveLength(2)
    await removeBtns[0].trigger('click')
    expect(wrapper.emitted('removeTag')).toEqual([['t1']])
  })

  it('offers an "open in new tab" link to the document view, ABOVE the tag select (#194)', () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    const link = wrapper.find('a.tqm-open-link')
    expect(link.exists()).toBe(true)
    // A genuine href to the document's full view, opened in a new browsing context
    // without handing it a live window.opener reference.
    expect(link.attributes('href')).toContain('/document/view/doc1')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    expect(link.text()).toContain('ui.open_in_new_tab')
    // It must precede the Select: the Select's overlay auto-opens on popover show
    // (#171) and would otherwise cover an item placed below it.
    const html = wrapper.html()
    expect(html.indexOf('tqm-open-link')).toBeLessThan(html.indexOf('select-stub'))
  })

  it('renders no "open in new tab" link when there is no document bound', () => {
    const wrapper = mountMenu({ document: null })
    expect(wrapper.find('a.tqm-open-link').exists()).toBe(false)
  })

  it('opens the tag select on popover show and puts focus in its filter itself (#171, #204)', async () => {
    // The focus is the component's own job now, not the Select's `autoFilterFocus` —
    // PrimeVue's version of it fires from an unguarded timer that throws when the popover
    // is dismissed first.
    const focusSpy = vi.spyOn(HTMLInputElement.prototype, 'focus')
    const wrapper = mountMenu()
    wrapper.findComponent(PopoverStub).vm.$emit('show')
    await flushPromises()
    expect(selectShow).toHaveBeenCalledTimes(1)
    expect(focusSpy).toHaveBeenCalledTimes(1)
    expect(focusSpy.mock.instances[0]).toBe(wrapper.find('input.stub-filter-input').element)
    focusSpy.mockRestore()
  })

  it('reaches for no filter at all when the overlay never came up (#204)', async () => {
    // What a mid-open dismissal leaves behind: the Select is opened but its overlay (and
    // filter input) is gone by the time the focus step runs. It must skip, not throw.
    selectMountsFilterInput = false
    const focusSpy = vi.spyOn(HTMLInputElement.prototype, 'focus')
    const wrapper = mountMenu()
    wrapper.findComponent(PopoverStub).vm.$emit('show')
    await flushPromises()
    expect(selectShow).toHaveBeenCalledTimes(1)
    expect(wrapper.find('input.stub-filter-input').exists()).toBe(false)
    expect(focusSpy).not.toHaveBeenCalled()
    focusSpy.mockRestore()
  })

  it('opens the popover a rendering step after the right-click, never inside it (#213)', async () => {
    // The menu must not be up while a scroll event queued before the right-click is still
    // undelivered: PrimeVue's Popover dismisses on the first scroll it sees, so an inline
    // open closes itself on a scroll the user had already finished. Waiting for an
    // animation frame is what puts the open after that delivery (see utils/nextFrame).
    const wrapper = mountMenu()
    wrapper.vm.show(rightClickOn(attachedRow()))

    await flushPromises()
    expect(popoverShow, 'not opened in the right-click task itself').not.toHaveBeenCalled()

    await afterFrame()
    await flushPromises()
    expect(popoverShow).toHaveBeenCalledTimes(1)
  })

  it('opens against the anchor captured at right-click time, not a stale event (#213)', async () => {
    // `currentTarget` is nulled once dispatch ends, so deferring the open must not defer
    // reading it: PrimeVue positions against the second argument and decides "was the
    // anchor itself clicked?" from the event's `currentTarget` — both must still be the row.
    const wrapper = mountMenu()
    const anchor = attachedRow()
    const event = rightClickOn(anchor)
    wrapper.vm.show(event)
    // What the browser does to the event object the moment dispatch finishes.
    Object.defineProperty(event, 'currentTarget', { value: null, configurable: true })

    await afterFrame()
    await flushPromises()
    const [passedEvent, passedTarget] = popoverShow.mock.calls[0] as [Event, HTMLElement]
    expect(passedTarget).toBe(anchor)
    expect(passedEvent.currentTarget).toBe(anchor)
  })

  // The deferral buys one frame in which the menu does not exist yet, so PrimeVue's own
  // dismissal cannot run — everything below is what has to stand in for it (#213).

  it('hide() during the deferred window cancels the open instead of being overtaken by it', async () => {
    const wrapper = mountMenu()
    wrapper.vm.show(rightClickOn(attachedRow()))
    wrapper.vm.hide()

    await afterFrame()
    await flushPromises()
    expect(popoverShow, 'a menu asked to go away must not appear a frame later').not.toHaveBeenCalled()
  })

  it('a press elsewhere during the deferred window cancels the open', async () => {
    const wrapper = mountMenu()
    wrapper.vm.show(rightClickOn(attachedRow()))
    document.dispatchEvent(new Event('pointerdown', { bubbles: true }))

    await afterFrame()
    await flushPromises()
    expect(popoverShow).not.toHaveBeenCalled()
  })

  it('Escape during the deferred window cancels the open', async () => {
    const wrapper = mountMenu()
    wrapper.vm.show(rightClickOn(attachedRow()))
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))

    await afterFrame()
    await flushPromises()
    expect(popoverShow).not.toHaveBeenCalled()
  })

  it('an unrelated keystroke during the deferred window does NOT cancel the open', async () => {
    // The cancel is Escape, not "any key" — typing must not swallow the menu.
    const wrapper = mountMenu()
    wrapper.vm.show(rightClickOn(attachedRow()))
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }))

    await afterFrame()
    await flushPromises()
    expect(popoverShow).toHaveBeenCalledTimes(1)
  })

  it('aborts when the row it was anchored to left the DOM during the deferred window', async () => {
    // What a background list refetch does: the row the user right-clicked is replaced, so
    // the captured anchor is detached and positioning against it would be meaningless.
    const wrapper = mountMenu()
    const anchor = attachedRow()
    wrapper.vm.show(rightClickOn(anchor))
    anchor.remove()

    await afterFrame()
    await flushPromises()
    expect(popoverShow, 'no menu is better than a menu anchored to a row that is gone').not.toHaveBeenCalled()
  })

  it('aborts when the document it was opened for is gone by the time the frame lands', async () => {
    // The parent resolves the bound document by id out of the live list (#142); a refetch
    // that drops it leaves this menu with nothing to tag.
    const wrapper = mountMenu()
    wrapper.vm.show(rightClickOn(attachedRow()))
    await wrapper.setProps({ document: null })

    await afterFrame()
    await flushPromises()
    expect(popoverShow).not.toHaveBeenCalled()
  })

  it('opens once, against the latest row, when two right-clicks land in the same frame', async () => {
    const wrapper = mountMenu()
    const first = attachedRow()
    const latest = attachedRow()
    wrapper.vm.show(rightClickOn(first))
    wrapper.vm.show(rightClickOn(latest))

    await afterFrame()
    await flushPromises()
    expect(popoverShow, 'the superseded open must not fire too').toHaveBeenCalledTimes(1)
    const [, passedTarget] = popoverShow.mock.calls[0] as [Event, HTMLElement]
    expect(passedTarget).toBe(latest)
  })

  it('shows an all-assigned notice and no chips when every tag is already on the doc', () => {
    const wrapper = mountMenu({ document: makeDoc(['t1', 't2', 't3', 't4', 't5', 't6']) })
    expect(wrapper.find('.select-stub').exists()).toBe(false)
    expect(wrapper.findAll('.tqm-chip')).toHaveLength(0)
    expect(wrapper.text()).toContain('ui.tag_menu.all_assigned')
  })
})
