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

// The tag list rows are stateless "add this tag" buttons rendered by the component itself — no
// Listbox, so nothing to stub. Leaving them REAL is deliberate: a stateful single-select
// Listbox toggled its sticky selection and swallowed a re-add of the just-added tag (the
// regression that motivated TEEDY-86's second iteration); plain buttons cannot. The
// InputText/IconField/InputIcon search box is likewise left real — the owned control this
// rework is about.

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
      stubs: { Popover: PopoverStub },
    },
  })
}

// The add-action row for a given tag name (the list rows carry the tag name as their text).
function optionByText(wrapper: ReturnType<typeof mountMenu>, name: string) {
  return wrapper.findAll('.tqm-option').find((o) => o.text() === name)
}

beforeEach(() => {
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
// so the menu has to really be in it for an event to reach both, and focus only moves for an
// element that is connected. Unmounted after every case so no document-level listener (or
// stray input) outlives the test that armed it.
const attachedMenus: ReturnType<typeof mountMenu>[] = []

function mountAttachedMenu(props: Partial<InstanceType<typeof TagQuickMenu>['$props']> = {}) {
  const wrapper = mountMenu(props, document.body)
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
  it('offers only assignable (not-yet-assigned) tags in the tag list', () => {
    const wrapper = mountMenu({ document: makeDoc(['t1', 't3']) })
    const names = wrapper.findAll('.tqm-option').map((o) => o.text())
    // 6 total - 2 assigned = 4 assignable; the two assigned tags are absent.
    expect(names).toHaveLength(4)
    expect(names).not.toContain('Invoice') // t1, assigned
    expect(names).not.toContain('Bank') // t3, assigned
  })

  it('narrows the tag list through the owned search box, case-insensitively (TEEDY-86)', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    // All five assignable tags before any search text.
    expect(wrapper.findAll('.tqm-option')).toHaveLength(5)

    await wrapper.find('input.tqm-filter-input').setValue('rec')
    const names = wrapper.findAll('.tqm-option').map((o) => o.text())
    expect(names).toEqual(['Receipt']) // only "Receipt" matches "rec"
  })

  // #284 — the row is a single ellipsised line (`white-space: nowrap; overflow: hidden;
  // text-overflow: ellipsis`) inside a panel of bounded width, so a long tag name is CLIPPED.
  // The only other place the full name lives on that row is the aria-label, which a sighted
  // user never sees. The native tooltip is what makes a truncated name recoverable, and it is
  // the half of the fix that holds for names longer than any width we could pick.
  it('carries the full tag name as the row tooltip, so a clipped name stays readable (#284)', () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    const rows = wrapper.findAll('.tqm-option')
    // Guard the loop below against passing vacuously on an empty list.
    expect(rows).toHaveLength(5)
    for (const row of rows) {
      expect(row.attributes('title')).toBe(row.text())
    }
    expect(optionByText(wrapper, 'Contract')!.attributes('title')).toBe('Contract')
  })

  it('shows a no-results message and no rows when the search matches no tag', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    await wrapper.find('input.tqm-filter-input').setValue('zzz')
    expect(wrapper.findAll('.tqm-option')).toHaveLength(0)
    expect(wrapper.find('.tqm-option-empty').exists()).toBe(true)
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

  it('emits addTag when a tag is chosen from the tag list', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    await optionByText(wrapper, 'Contract')!.trigger('click') // t5
    expect(wrapper.emitted('addTag')).toEqual([['t5']])
  })

  it('re-adds the SAME tag when the reused menu instance is clicked again (no toggle-swallow, TEEDY-86)', async () => {
    // Regression guard: a stateful single-select Listbox kept a sticky selection and toggled
    // it off on a re-click, emitting a null "deselect" that the add-handler swallowed — so
    // batch-tagging several documents with the same tag (right-click doc B during doc A's
    // menu fade, click the same tag) silently failed on the reused instance. The row buttons
    // hold no selection, so the same tag adds every time. hide() here does not unmount the
    // stubbed popover's slot, so the second click lands on the very same rendered rows.
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    await optionByText(wrapper, 'Contract')!.trigger('click')
    await optionByText(wrapper, 'Contract')!.trigger('click')
    expect(wrapper.emitted('addTag')).toEqual([['t5'], ['t5']])
  })

  it('adds the top filtered match when Enter is pressed in the search box (#171/#204)', async () => {
    // Keyboard-only entry: type a name, press Enter, the top match is committed without ever
    // leaving the search box.
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    const input = wrapper.find('input.tqm-filter-input')
    await input.setValue('rec')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('addTag')).toEqual([['t2']])
  })

  it('does nothing on Enter when the search box matches no tag', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    const input = wrapper.find('input.tqm-filter-input')
    await input.setValue('zzz')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('addTag')).toBeUndefined()
  })

  it('offers a clear (×) only once the owned search box has text, and empties it on click (#274)', async () => {
    // The clear lives against OUR InputText — no reach into PrimeVue's private filter DOM.
    const wrapper = mountAttachedMenu({ document: makeDoc(['t1']) })
    const input = wrapper.find('input.tqm-filter-input')
    expect(input.exists()).toBe(true)
    // Empty box → just the magnifier, no clear affordance.
    expect(wrapper.find('.tqm-filter-clear').exists()).toBe(false)

    await input.setValue('rec')
    const clear = wrapper.find('.tqm-filter-clear')
    expect(clear.exists(), 'a clear button appears once text is typed').toBe(true)
    // A labelled button (its visible text is the accessible name), reusing the main search
    // bar's clear label — the affordance the reporter compared against.
    expect(clear.text()).toContain('document.search_clear')

    const focusSpy = vi.spyOn(input.element as HTMLInputElement, 'focus')
    await clear.trigger('click')
    // The click empties the real search box and the tracked text, so the clear affordance
    // reverts to the magnifier, and the caret returns to the field.
    expect((input.element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('.tqm-filter-clear').exists()).toBe(false)
    expect(focusSpy, 'the caret returns to the search box').toHaveBeenCalled()
    focusSpy.mockRestore()
  })

  it('drops the clear (×) again when the menu hides with search text still in the box (#274)', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    await wrapper.find('input.tqm-filter-input').setValue('rec')
    expect(wrapper.find('.tqm-filter-clear').exists()).toBe(true)

    // The popover's leave resets the owned filter, so a reopened menu never shows a clear ×
    // over an empty box.
    wrapper.findComponent(PopoverStub).vm.$emit('hide')
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

  it('offers an "open in new tab" link to the document view, ABOVE the tag search (#194)', () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    const link = wrapper.find('a.tqm-open-link')
    expect(link.exists()).toBe(true)
    // A genuine href to the document's full view, opened in a new browsing context
    // without handing it a live window.opener reference.
    expect(link.attributes('href')).toContain('/document/view/doc1')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    expect(link.text()).toContain('ui.open_in_new_tab')
    // It must precede the search box and list, the natural reading order for the menu.
    const html = wrapper.html()
    expect(html.indexOf('tqm-open-link')).toBeLessThan(html.indexOf('tqm-filter-input'))
    expect(html.indexOf('tqm-open-link')).toBeLessThan(html.indexOf('tqm-tag-list'))
  })

  it('renders no "open in new tab" link when there is no document bound', () => {
    const wrapper = mountMenu({ document: null })
    expect(wrapper.find('a.tqm-open-link').exists()).toBe(false)
  })

  it('does not steal focus on popover show (#234 follow-up)', async () => {
    // The reporter read the earlier auto-opened Select overlay as a second floating panel
    // under the popover (#234). The menu now presents as a single panel and `show` arms only
    // the outside-right-click dismissal — nothing grabs focus. (The slide-over keeps its own
    // auto-focus — a separate surface, covered in tag-add-focus.spec.ts.)
    const focusSpy = vi.spyOn(HTMLInputElement.prototype, 'focus')
    const wrapper = mountMenu()
    wrapper.findComponent(PopoverStub).vm.$emit('show')
    await flushPromises()
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

  it('shows an all-assigned notice and no search or chips when every tag is already on the doc', () => {
    const wrapper = mountMenu({ document: makeDoc(['t1', 't2', 't3', 't4', 't5', 't6']) })
    expect(wrapper.find('.tqm-tag-list').exists()).toBe(false)
    expect(wrapper.findAll('.tqm-option')).toHaveLength(0)
    expect(wrapper.find('input.tqm-filter-input').exists()).toBe(false)
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

  it('leaves the menu alone when the right-click lands in the inline search box (TEEDY-86)', () => {
    // The search box is now a real DOM descendant of the popover (no teleported Select
    // overlay), so a right-click in it — the gesture that reaches "paste" — is inside the
    // menu and must not take it away.
    const wrapper = mountAttachedMenu()
    wrapper.findComponent(PopoverStub).vm.$emit('show')

    dispatchContextMenu(wrapper.find('input.tqm-filter-input').element)

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

// #280 — the same synonym search the document editor's picker does. A tag offered because one
// of its SYNONYMS matched has to say so on the row: offering "Invoice" to somebody who typed
// "Rechnung", with nothing to explain it, reads as a broken search. (The i18n stub echoes the
// key, so the reason shows up as `ui.tag_menu.via` rather than the rendered wording.)
describe('TagQuickMenu — synonym matches (#280)', () => {
  const synonymTags: Tag[] = [
    { id: 't1', name: 'Invoice', color: '#123456', parent: null, synonyms: ['Rechnung'] },
    { id: 't2', name: 'Receipt', color: '#123456', parent: null },
  ]

  function mountSynonymMenu() {
    return mountMenu({
      document: { id: 'doc1', title: 'Doc', tags: [] } as unknown as DocumentListItem,
      allTags: synonymTags,
    })
  }

  it('finds a tag by one of its synonyms and says which one on the row', async () => {
    const wrapper = mountSynonymMenu()
    await wrapper.find('input.tqm-filter-input').setValue('Rechnung')

    const rows = wrapper.findAll('.tqm-option')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('Invoice')
    expect(rows[0].text()).toContain('ui.tag_menu.via')
    expect(rows[0].find('.tqm-via').exists()).toBe(true)
  })

  it('leaves a row matched by the tag name alone', async () => {
    const wrapper = mountSynonymMenu()
    await wrapper.find('input.tqm-filter-input').setValue('Invo')

    const rows = wrapper.findAll('.tqm-option')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toBe('Invoice')
    expect(rows[0].find('.tqm-via').exists()).toBe(false)
  })

  it('adds the CANONICAL tag when a synonym row is chosen', async () => {
    const wrapper = mountSynonymMenu()
    await wrapper.find('input.tqm-filter-input').setValue('Rechnung')
    await wrapper.findAll('.tqm-option')[0].trigger('click')

    expect(wrapper.emitted('addTag')).toEqual([['t1']])
  })

  it('commits the top synonym match on Enter, by the tag id', async () => {
    const wrapper = mountSynonymMenu()
    const input = wrapper.find('input.tqm-filter-input')
    await input.setValue('Rechnung')
    await input.trigger('keydown.enter')

    expect(wrapper.emitted('addTag')).toEqual([['t1']])
  })
})
