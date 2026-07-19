import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import DocumentGallery from './DocumentGallery.vue'
import type { DocumentListItem } from '../api/document'
import { useTagFilterStore } from '../stores/tagFilter'

// vue-i18n stub: return the key so assertions target logic, not copy.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

// Stub the file API (thumb URLs) — no network in unit tests. The URL echoes the
// file id + size so a test can prove the card requests the THUMB of its own file.
vi.mock('../api/file', () => ({ getFileUrl: (id: string, size: string) => `/file/${id}/${size}` }))

// The gallery instantiates the tagFilter store (clickable tag chips), which hits
// the tag API on setup. Stub it so the store's useQuery calls never touch the
// network; the store logic itself is unit-tested in stores/tagFilter.spec.ts.
vi.mock('../api/tag', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/tag')>()
  return {
    ...actual,
    listTags: vi.fn().mockResolvedValue({ data: { tags: [] } }),
    getTagStats: vi.fn().mockResolvedValue({ data: { stats: {} } }),
    getTagFacets: vi.fn().mockResolvedValue({ data: { facets: {}, total: 0 } }),
    getTagCoOccurrence: vi.fn().mockResolvedValue({ data: { pairs: [] } }),
  }
})

function makeDoc(overrides: Partial<DocumentListItem> = {}): DocumentListItem {
  return {
    id: 'doc1',
    title: 'A Document',
    description: '',
    create_date: 0,
    update_date: 0,
    language: 'eng',
    file_id: 'file-abc',
    file_count: 1,
    tags: [],
    shared: false,
    ...overrides,
  }
}

let router: Router

beforeEach(() => {
  setActivePinia(createPinia())
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/document', name: 'documents', component: { template: '<div />' } },
      // The open action is a <router-link> to the document view — the route must
      // resolve for the link to render a real href.
      { path: '/document/view/:id', name: 'document-view', component: { template: '<div />' } },
    ],
  })
})

async function mountGallery(documents: DocumentListItem[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router.push('/document')
  await router.isReady()
  return mount(DocumentGallery, {
    props: { documents },
    global: {
      plugins: [[PrimeVue, { theme: 'none' }], router, [VueQueryPlugin, { queryClient }]],
      directives: { tooltip: Tooltip },
      // FavoriteStar has its own spec and pulls in Toast/mutation services this
      // gallery-focused unit test does not provide; stub it to a marker.
      stubs: { FavoriteStar: { template: '<button class="fav-star-stub" />' } },
    },
  })
}

describe('DocumentGallery — cards', () => {
  it('renders one card per document; the open link is a real router-link carrying the title as its accessible name', async () => {
    const wrapper = await mountGallery([makeDoc({ id: 'a', title: 'Alpha' }), makeDoc({ id: 'b', title: 'Beta' })])
    const cards = wrapper.findAll('article.doc-card')
    expect(cards).toHaveLength(2)
    // The open action is an <a> (router-link) covering thumb+title+date, NOT the
    // card container — so the card itself is a non-interactive <article>.
    const links = wrapper.findAll('a.card-open')
    expect(links).toHaveLength(2)
    expect(links[0].attributes('aria-label')).toBe('Alpha')
    expect(links[1].attributes('aria-label')).toBe('Beta')
    // The link carries a genuine href to the document view (real link semantics).
    expect(links[0].attributes('href')).toContain('/document/view/a')
  })

  it('has NO nested interactive elements — the star and tag controls are SIBLINGS of the open link', async () => {
    const wrapper = await mountGallery([
      makeDoc({ id: 'a', tags: [{ id: 't0', name: 'invoice', color: '#123456' }] }),
    ])
    // Real FavoriteStar (not the stub) so the assertion covers the shipped control.
    const link = wrapper.find('a.card-open')
    expect(link.exists()).toBe(true)
    // No interactive descendant lives inside the open link — no button, no anchor,
    // no second interactive control nested within it (invalid-HTML / a11y hazard).
    expect(link.find('button').exists()).toBe(false)
    expect(link.find('a').exists()).toBe(false)
    // The star and the tag chip are present in the card but OUTSIDE the link.
    const card = wrapper.find('article.doc-card')
    expect(card.find('.card-star').exists()).toBe(true)
    expect(card.find('.card-tags .tag-clickable').exists()).toBe(true)
    expect(link.find('.card-star').exists()).toBe(false)
    expect(link.find('.card-tags').exists()).toBe(false)
  })

  it('renders a thumbnail IMG per card sourced from the document file_id (thumb size)', async () => {
    const wrapper = await mountGallery([makeDoc({ id: 'a', file_id: 'file-xyz' })])
    const img = wrapper.find('.card-thumb img')
    expect(img.exists()).toBe(true)
    // The card requests the THUMB variant of its OWN file — the component's only
    // handle on the image (DocumentListItem carries no mimetype; real-vs-placeholder
    // discrimination is an e2e byte/dimension concern, not this unit's).
    expect(img.attributes('src')).toBe('/file/file-xyz/thumb')
  })

  it('falls back to a file icon (no img) when the document has no file', async () => {
    const wrapper = await mountGallery([makeDoc({ file_id: null })])
    expect(wrapper.find('.card-thumb img').exists()).toBe(false)
    expect(wrapper.find('.card-thumb i.pi-file').exists()).toBe(true)
  })

  it('emits cardClick with the document on a plain click of the open link (slide-over)', async () => {
    const doc = makeDoc({ id: 'clicked' })
    const wrapper = await mountGallery([doc])
    // A plain left click on the open link is intercepted (preventDefault) and
    // re-emitted as cardClick — the parent routes it to the slide-over.
    await wrapper.find('a.card-open').trigger('click', { button: 0 })
    const emitted = wrapper.emitted('cardClick')
    expect(emitted).toBeTruthy()
    expect((emitted as unknown[][])[0][0]).toMatchObject({ id: 'clicked' })
  })

  it('emits cardDblclick with the document on a double-click of the open link (full view)', async () => {
    const doc = makeDoc({ id: 'dbl' })
    const wrapper = await mountGallery([doc])
    await wrapper.find('a.card-open').trigger('dblclick')
    const emitted = wrapper.emitted('cardDblclick')
    expect(emitted).toBeTruthy()
    expect((emitted as unknown[][])[0][0]).toMatchObject({ id: 'dbl' })
  })

  it('a modifier click (ctrl/cmd) is NOT intercepted — the native link href handles open-in-new-tab', async () => {
    const doc = makeDoc({ id: 'mod' })
    const wrapper = await mountGallery([doc])
    // A ctrl/cmd click must fall through to the native link (open in new tab), so
    // the component does NOT emit cardClick and does NOT preventDefault.
    await wrapper.find('a.card-open').trigger('click', { button: 0, ctrlKey: true })
    expect(wrapper.emitted('cardClick')).toBeUndefined()
  })

  it('renders the first three tags inline and delegates the rest to the overflow control', async () => {
    const tags = Array.from({ length: 6 }, (_, i) => ({ id: `t${i}`, name: `tag-${i}`, color: '#123456' }))
    const wrapper = await mountGallery([makeDoc({ tags })])
    const html = wrapper.html()
    for (let i = 0; i < 3; i++) expect(html).toContain(`tag-${i}`)
    const overflow = wrapper.find('.tag-overflow')
    expect(overflow.exists()).toBe(true)
    expect(overflow.text()).toContain('+3')
  })

  it('clicking an inline tag chip filters by that tag and does NOT open the card', async () => {
    const wrapper = await mountGallery([makeDoc({ tags: [{ id: 't0', name: 'invoice', color: '#123456' }] })])
    const store = useTagFilterStore()
    const selectSpy = vi.spyOn(store, 'selectTag').mockImplementation(() => {})

    const chip = wrapper.find('.card-tags .tag-clickable')
    expect(chip.exists()).toBe(true)
    await chip.trigger('click')
    expect(selectSpy).toHaveBeenCalledWith('t0')
    // The chip is a SIBLING of the open link (not a descendant), so its click can
    // never reach the open handler — no cardClick is emitted.
    expect(wrapper.emitted('cardClick')).toBeUndefined()
  })

  it('renders nothing (no cards) for an empty document set', async () => {
    const wrapper = await mountGallery([])
    expect(wrapper.findAll('article.doc-card')).toHaveLength(0)
  })

  it('emits cardContextMenu with the event + document on a right-click of the card (#50)', async () => {
    const doc = makeDoc({ id: 'rc' })
    const wrapper = await mountGallery([doc])
    // Right-clicking anywhere on the card surfaces the quick-tag context menu; the
    // component forwards the native event (so the parent can position the menu) and
    // the document the menu should act on.
    await wrapper.find('article.doc-card').trigger('contextmenu')
    const emitted = wrapper.emitted('cardContextMenu')
    expect(emitted).toBeTruthy()
    const [event, emittedDoc] = (emitted as unknown[][])[0]
    expect(event).toBeInstanceOf(Event)
    expect(emittedDoc).toMatchObject({ id: 'rc' })
  })

  // #80: a TRANSIENT thumbnail load error (server raster still settling under load) must NOT
  // permanently hide the thumbnail. The card retries with a cache-busted src a couple of times
  // before giving up — so a hiccup recovers on the next attempt instead of forcing a page reload.
  describe('thumbnail transient-error recovery (#80)', () => {
    beforeEach(() => vi.useFakeTimers())
    afterEach(() => vi.useRealTimers())

    it('retries with a cache-busted src on error instead of hiding the thumbnail', async () => {
      const wrapper = await mountGallery([makeDoc({ id: 'a', file_id: 'file-r' })])
      const img = wrapper.find('.card-thumb img')
      expect(img.attributes('src')).toBe('/file/file-r/thumb') // first attempt: unmodified

      // A transient error schedules a retry; the img is still in the DOM (not hidden).
      await img.trigger('error')
      vi.advanceTimersByTime(500)
      await wrapper.vm.$nextTick()

      const retried = wrapper.find('.card-thumb img')
      expect(retried.exists()).toBe(true) // still present — NOT permanently hidden
      // The src changed (cache-busting token) so the browser actually re-fetches.
      expect(retried.attributes('src')).toContain('_thumbretry=1')
      // A successful (re)load leaves the thumbnail visible: no icon fallback.
      expect(wrapper.find('.card-thumb i.pi-file').exists()).toBe(false)
    })

    it('falls back to the file icon only after the retries are exhausted', async () => {
      const wrapper = await mountGallery([makeDoc({ id: 'a', file_id: 'file-dead' })])

      // Error → retry (attempt 1), error → retry (attempt 2), error → give up (MAX_THUMB_RETRIES=2).
      for (let i = 0; i < 3; i++) {
        const img = wrapper.find('.card-thumb img')
        expect(img.exists()).toBe(true) // still retrying up to the bound
        await img.trigger('error')
        vi.advanceTimersByTime(500)
        await wrapper.vm.$nextTick()
      }

      // Retries exhausted: the img is removed and the file-icon fallback renders.
      expect(wrapper.find('.card-thumb img').exists()).toBe(false)
      expect(wrapper.find('.card-thumb i.pi-file').exists()).toBe(true)
    })

    // BLOCKING-3 regression: a pending retry timer must be cancelled on unmount so its callback
    // never fires after teardown and mutates stale reactive state (leaked-timer hazard).
    it('cancels a pending retry timer on unmount (no fire-after-teardown)', async () => {
      const clearSpy = vi.spyOn(globalThis, 'clearTimeout')
      const wrapper = await mountGallery([makeDoc({ id: 'a', file_id: 'file-u' })])

      // Trigger an error → schedules a retry timer, then unmount before it fires.
      await wrapper.find('.card-thumb img').trigger('error')
      wrapper.unmount()
      expect(clearSpy).toHaveBeenCalled() // the pending timer was cancelled during teardown

      // Advancing past the delay must not throw (the guarded callback was cleared).
      expect(() => vi.advanceTimersByTime(500)).not.toThrow()
      clearSpy.mockRestore()
    })
  })
})
