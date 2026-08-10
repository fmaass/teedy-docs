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

// The imperative close, recorded so the outside-right-click dismissal (#234) can be asserted
// on the popover rather than on a private flag.
const popoverHide = vi.fn()

// Stub Popover so its content renders inline (no teleport/overlay in jsdom), and expose
// show/hide so the component's defineExpose contract still works. Declared out here so a
// test can grab the instance and emit the popover's own `show`/`hide` events.
const PopoverStub = {
  template: '<div class="popover-stub"><slot /></div>',
  data() {
    return { container: null as HTMLElement | null }
  },
  mounted(this: { $el: HTMLElement; container: HTMLElement | null }) {
    // The real Popover publishes its rendered root as `container` (its `containerRef`); the
    // outside-right-click dismissal hit-tests the event against it (#234).
    this.container = this.$el
  },
  methods: {
    show(event: Event, target?: HTMLElement) {
      popoverShow(event, target)
    },
    hide() {
      popoverHide()
    },
    toggle() {},
  },
}

// Records any imperative open of the Select. The right-click menu must NOT auto-open it on
// popover show (#234 follow-up) — this stays uncalled, so a regression that re-adds the
// auto-open trips the "does not auto-open" assertion below.
const selectShow = vi.fn()

// Stub Select so we can read the `options` it is handed without booting the full overlay;
// expose an update button to simulate a selection, and a `show()` that records an imperative
// open (which the component must no longer perform on popover show). It also renders the
// filter input and the `header` slot, and publishes its root as `overlay`, so the clear-×
// wiring (#274) — which finds the filter input through `overlay` — is exercisable.
const SelectStub = {
  props: ['options', 'modelValue'],
  emits: ['update:modelValue', 'filter', 'hide'],
  data() {
    return { overlay: null as HTMLElement | null }
  },
  mounted(this: { $el: HTMLElement; overlay: HTMLElement | null }) {
    // The real Select exposes its overlay root; the clear-× reaches the filter input through
    // it, so the stub exposes the same handle over its own rendered root.
    this.overlay = this.$el
  },
  methods: {
    show() {
      selectShow()
    },
  },
  template:
    '<div class="select-stub" :data-count="options.length">' +
    '<slot name="header" />' +
    '<input class="p-select-filter" @input="$emit(\'filter\', { value: $event.target.value })" />' +
    '<button v-for="o in options" :key="o.id" class="opt" :data-id="o.id" @click="$emit(\'update:modelValue\', o.id)">{{ o.name }}</button>' +
    '</div>',
}

function mountMenu(
  props: Partial<InstanceType<typeof TagQuickMenu>['$props']> = {},
  attachTo?: HTMLElement,
) {
  return mount(TagQuickMenu, {
    props: {
      document: makeDoc(['t1']),
      allTags,
      tagCounts: { t2: 30, t3: 10, t4: 5, t5: 2, t6: 1 },
      ...props,
    },
    attachTo,
    global: {
      plugins: [PrimeVue, makeRouter()],
      stubs: { Popover: PopoverStub, Select: SelectStub },
    },
  })
}

beforeEach(() => {
  selectShow.mockClear()
  popoverShow.mockClear()
  popoverHide.mockClear()
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

// Menus mounted INTO the document — the outside-right-click dismissal listens on `document`,
// so the menu has to really be in it for an event to reach both. Unmounted after every case so
// no document-level listener outlives the test that armed it.
const attachedMenus: ReturnType<typeof mountMenu>[] = []

function mountAttachedMenu() {
  const wrapper = mountMenu({}, document.body)
  attachedMenus.push(wrapper)
  return wrapper
}

afterEach(() => {
  attachedMenus.splice(0).forEach((wrapper) => wrapper.unmount())
  attachedRows.splice(0).forEach((row) => row.remove())
})

// A right-click as the browser delivers it: cancelable, so a test can read back whether
// anything claimed it.
function dispatchContextMenu(target: EventTarget): MouseEvent {
  const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
  target.dispatchEvent(event)
  return event
}

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

  it('offers a clear (×) only once the tag filter has text, and empties it on click (#274)', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    const filter = wrapper.find('.select-stub input.p-select-filter')
    expect(filter.exists()).toBe(true)
    // Empty box → just the magnifier, no clear affordance.
    expect(wrapper.find('.tqm-filter-clear').exists()).toBe(false)

    await filter.setValue('rec')
    const clear = wrapper.find('.tqm-filter-clear')
    expect(clear.exists(), 'a clear button appears once text is typed').toBe(true)
    // A labelled button (its visible text is the accessible name), reusing the main search
    // bar's clear label — the affordance the reporter compared against.
    expect(clear.text()).toContain('document.search_clear')

    await clear.trigger('click')
    // The click empties the real filter input and the tracked text, so the trailing icon
    // reverts to the magnifier.
    expect((filter.element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('.tqm-filter-clear').exists()).toBe(false)
  })

  it('drops the clear (×) again when the Select closes with filter text still tracked (#274)', async () => {
    // PrimeVue empties its filter on hide without raising `filter`; the component re-syncs on
    // the Select's `hide` so a reopened Select never shows a clear × over an empty box.
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    await wrapper.find('.select-stub input.p-select-filter').setValue('rec')
    expect(wrapper.find('.tqm-filter-clear').exists()).toBe(true)

    wrapper.findComponent(SelectStub).vm.$emit('hide')
    await flushPromises()
    expect(wrapper.find('.tqm-filter-clear').exists()).toBe(false)
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
    // It must precede the Select: the Select's overlay opens downward and would otherwise
    // cover an item placed below it once the user opens it.
    const html = wrapper.html()
    expect(html.indexOf('tqm-open-link')).toBeLessThan(html.indexOf('select-stub'))
  })

  it('renders no "open in new tab" link when there is no document bound', () => {
    const wrapper = mountMenu({ document: null })
    expect(wrapper.find('a.tqm-open-link').exists()).toBe(false)
  })

  it('does not auto-open the tag select or steal focus on popover show (#234 follow-up)', async () => {
    // The reporter read the auto-opened Select overlay as a second floating panel under the
    // popover (#234). The menu now presents as a single panel: `show` arms only the outside-
    // right-click dismissal, and the tag Select opens on a click. (The slide-over keeps its
    // own auto-focus — a separate surface, covered in tag-add-focus.spec.ts.)
    const focusSpy = vi.spyOn(HTMLInputElement.prototype, 'focus')
    const wrapper = mountMenu()
    wrapper.findComponent(PopoverStub).vm.$emit('show')
    await flushPromises()
    expect(selectShow, 'the Select must not auto-open on popover show').not.toHaveBeenCalled()
    expect(focusSpy, 'nothing steals focus on popover show').not.toHaveBeenCalled()
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

// --- #234: PrimeVue dismisses a Popover on an outside CLICK, and a right-click fires no
//     click. The menu therefore stayed up while the browser drew its own menu next to it —
//     two context menus at once. The dismissal has to answer `contextmenu` as well, without
//     taking the native menu away from the user. ---
describe('TagQuickMenu — outside right-click dismissal (#234)', () => {
  it('hides the menu when a right-click lands outside it, and lets the gesture through', () => {
    const wrapper = mountAttachedMenu()
    wrapper.findComponent(PopoverStub).vm.$emit('show')

    const event = dispatchContextMenu(document.body)

    expect(popoverHide, 'the open menu is dismissed by a right-click outside it').toHaveBeenCalledTimes(1)
    // The native menu stays the user's: the dismissal claims nothing, so the browser goes on
    // to draw its own menu (the same contract the #194 shift escape hatch rests on).
    expect(event.defaultPrevented, 'the right-click is left for the browser').toBe(false)
  })

  it('leaves the menu alone when the right-click lands inside it', () => {
    // Parity with the outside-CLICK dismissal it mirrors: a press inside the menu is not a
    // dismissal. It is also how the menu's own "open in new tab" link (#194) stays reachable
    // by the browser's "copy link address".
    const wrapper = mountAttachedMenu()
    wrapper.findComponent(PopoverStub).vm.$emit('show')

    dispatchContextMenu(wrapper.find('.tqm-body').element)

    expect(popoverHide).not.toHaveBeenCalled()
  })

  it('stops answering right-clicks once the menu has gone', () => {
    const wrapper = mountAttachedMenu()
    wrapper.findComponent(PopoverStub).vm.$emit('show')
    wrapper.findComponent(PopoverStub).vm.$emit('hide')

    dispatchContextMenu(document.body)

    expect(popoverHide, 'a closed menu leaves no listener behind').not.toHaveBeenCalled()
  })

  it('leaves no listener behind when the menu is unmounted while open', () => {
    // Unmounting plays no leave transition, so the popover's own `hide` never arrives.
    const wrapper = mountMenu({}, document.body)
    wrapper.findComponent(PopoverStub).vm.$emit('show')
    wrapper.unmount()

    dispatchContextMenu(document.body)

    expect(popoverHide).not.toHaveBeenCalled()
  })
})
