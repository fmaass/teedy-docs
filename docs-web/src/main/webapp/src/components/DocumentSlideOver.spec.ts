import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import DocumentSlideOver from './DocumentSlideOver.vue'
import type { DocumentDetail } from '../api/document'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))

// PrimeVue Tabs uses ResizeObserver and the component probes window.matchMedia;
// jsdom provides neither. Stub both (default: desktop / no-match).
beforeAll(() => {
  if (typeof globalThis.ResizeObserver !== 'function') {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
  forceDesktop()
})

// A title long enough to overflow the header row — the #68 collision scenario.
const LONG_TITLE =
  'A very very long document title that would otherwise run straight into the close button and get cut off mid-word'

function makeDoc(overrides: Partial<DocumentDetail> = {}): DocumentDetail {
  return {
    id: 'doc-1',
    title: LONG_TITLE,
    tags: [],
    files: [],
    create_date: 0,
    ...overrides,
  } as unknown as DocumentDetail
}

function mountSlideOver(doc: DocumentDetail = makeDoc()) {
  return mount(DocumentSlideOver, {
    props: {
      visible: true,
      loading: false,
      document: doc,
      availableTags: [],
    },
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: 'none' }]] },
  })
}

// Force desktop so the resize handle renders (matchMedia defaults to no-match in
// jsdom, which is already "desktop", but pin it explicitly for clarity).
function forceDesktop() {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
    onchange: null,
  })) as unknown as typeof window.matchMedia
}

describe('DocumentSlideOver — long-title header layout (#68)', () => {
  it('renders the header slot and the drawer close button as separate siblings', async () => {
    mountSlideOver()
    await flushPromises()

    // Drawer teleports to <body>; query the rendered document.
    const header = document.querySelector('.slide-over-header')
    const title = document.querySelector('.slide-over-title')
    const closeBtn = document.querySelector('.p-drawer-close-button')

    expect(header).not.toBeNull()
    expect(title?.textContent).toContain('A very very long document title')
    // The close button must exist and NOT be nested inside our truncating header
    // slot — it is a separate flex child of .p-drawer-header, so it stays a
    // fixed, clickable target next to the ellipsised title.
    expect(closeBtn).not.toBeNull()
    expect(header?.contains(closeBtn as Node)).toBe(false)
  })

  it('title is nested inside the shrinkable header slot, close button is outside it', async () => {
    mountSlideOver()
    await flushPromises()

    // jsdom performs no layout and does not resolve scoped-CSS into
    // getComputedStyle, so assert the STRUCTURE that the CSS acts on: the title
    // lives inside .slide-over-header (which the stylesheet gives flex:1;
    // min-width:0 so it truncates), and the close button is a separate sibling
    // in .p-drawer-header — never inside the truncating slot.
    const drawerHeader = document.querySelector('.p-drawer-header') as HTMLElement
    const header = drawerHeader.querySelector('.slide-over-header') as HTMLElement
    const title = header.querySelector('.slide-over-title')
    const closeBtn = drawerHeader.querySelector('.p-drawer-close-button')

    expect(title).not.toBeNull()
    expect(closeBtn).not.toBeNull()
    // Close button is a direct sibling of the header slot, not nested inside it.
    expect(header.contains(closeBtn as Node)).toBe(false)
    expect(closeBtn?.parentElement).toBe(drawerHeader)
  })
})

describe('DocumentSlideOver — drag-resizable width (#68)', () => {
  beforeEach(() => {
    forceDesktop()
    localStorage.clear()
  })

  it('renders a resize separator handle on desktop and drives the drawer width from the composable', async () => {
    mountSlideOver()
    await flushPromises()

    const handle = document.querySelector('.slide-over-resizer') as HTMLElement
    expect(handle).not.toBeNull()
    expect(handle.getAttribute('role')).toBe('separator')
    expect(handle.getAttribute('aria-orientation')).toBe('vertical')
    expect(handle.getAttribute('aria-label')).toBe('ui.resize_panel')

    // Default composable width (500) drives BOTH the drawer root width and the
    // handle's right offset — proving the width is composable-driven, not static.
    const drawer = document.querySelector('.doc-slide-over.p-drawer') as HTMLElement
    expect(drawer).not.toBeNull()
    expect(drawer.style.width).toBe('500px')
    expect(handle.style.right).toBe('500px')
    expect(handle.getAttribute('aria-valuenow')).toBe('500')
  })

  it('keyboard resize widens the drawer (ArrowLeft on the inverted left-edge handle)', async () => {
    mountSlideOver()
    await flushPromises()

    const handle = document.querySelector('.slide-over-resizer') as HTMLElement
    const drawer = document.querySelector('.doc-slide-over.p-drawer') as HTMLElement
    expect(drawer.style.width).toBe('500px')

    // Left-edge handle is inverted: ArrowLeft must WIDEN (+16 => 516).
    handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }))
    await flushPromises()
    expect(drawer.style.width).toBe('516px')
    expect(handle.style.right).toBe('516px')
  })

  it('persists the chosen width under the slide-over storage key', async () => {
    mountSlideOver()
    await flushPromises()
    const handle = document.querySelector('.slide-over-resizer') as HTMLElement
    handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }))
    await flushPromises()
    expect(localStorage.getItem('teedy_slide_over_width')).toBe('516')
  })
})

describe('DocumentSlideOver — delete action (#172)', () => {
  beforeEach(() => {
    forceDesktop()
  })

  it('renders the Delete button only when the document is writable', async () => {
    const wrapper = mountSlideOver(makeDoc({ writable: true }))
    await flushPromises()
    const delBtn = document.querySelector('.slide-delete-btn')
    expect(delBtn).not.toBeNull()
    expect(delBtn?.textContent).toContain('delete')
    wrapper.unmount()
  })

  it('omits the Delete button for a read-only document', async () => {
    // writable: false is the read-only-ACL case; undefined (the field absent) must
    // also hide it — a document is never deletable unless writable is explicitly true.
    const wrapperRO = mountSlideOver(makeDoc({ writable: false }))
    await flushPromises()
    expect(document.querySelector('.slide-delete-btn')).toBeNull()
    wrapperRO.unmount()

    const wrapperAbsent = mountSlideOver(makeDoc())
    await flushPromises()
    expect(document.querySelector('.slide-delete-btn')).toBeNull()
    wrapperAbsent.unmount()
  })

  it('emits deleteDocument with the document id when clicked', async () => {
    const wrapper = mountSlideOver(makeDoc({ id: 'doc-42', writable: true }))
    await flushPromises()
    const delBtn = document.querySelector('.slide-delete-btn') as HTMLElement
    expect(delBtn).not.toBeNull()
    delBtn.click()
    await flushPromises()
    expect(wrapper.emitted('deleteDocument')).toEqual([['doc-42']])
    wrapper.unmount()
  })
})
