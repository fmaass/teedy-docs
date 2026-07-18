import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import TagOverflow from './TagOverflow.vue'
import type { Tag } from '../api/tag'
import { useTagFilterStore } from '../stores/tagFilter'

// vue-i18n stub: return the key so assertions target logic, not copy.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

// The overflow popover chips are now clickable filter buttons (#34), so the
// component instantiates the tagFilter store. Stub the tag API so the store's
// useQuery calls never touch the network.
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

function makeTags(n: number): Pick<Tag, 'id' | 'name' | 'color'>[] {
  return Array.from({ length: n }, (_, i) => ({
    id: `t${i + 3}`,
    name: `tag-${i + 3}`,
    color: '#123456',
  }))
}

let router: Router

beforeEach(() => {
  setActivePinia(createPinia())
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/document', name: 'documents', component: { template: '<div />' } },
    ],
  })
})

async function mountOverflow(tags: Pick<Tag, 'id' | 'name' | 'color'>[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router.push('/document')
  await router.isReady()
  return mount(TagOverflow, {
    props: { tags },
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: 'none' }], router, [VueQueryPlugin, { queryClient }]],
    },
  })
}

// (Body cleanup is handled by the global enableAutoUnmount in vitest.setup.ts, which unmounts
// each wrapper — removing its attached root and teleported panel — inside the live environment.)

describe('TagOverflow — reveal panel (#24)', () => {
  it('renders a focusable +N trigger labelled with the hidden count', async () => {
    const wrapper = await mountOverflow(makeTags(3))
    const trigger = wrapper.find('.tag-overflow')
    expect(trigger.exists()).toBe(true)
    expect(trigger.text()).toContain('+3')
    expect(trigger.attributes('tabindex')).toBe('0')
    expect(trigger.attributes('role')).toBe('button')
    expect(trigger.attributes('aria-label')).toBe('ui.more_tags')
  })

  it('associates the trigger with the panel via aria-controls', async () => {
    const wrapper = await mountOverflow(makeTags(3))
    const trigger = wrapper.find('.tag-overflow')
    const controls = trigger.attributes('aria-controls')
    expect(controls).toBeTruthy()
    expect(trigger.attributes('aria-expanded')).toBe('false')
  })

  it('reveals the hidden tag names in a body-teleported panel on activation', async () => {
    const wrapper = await mountOverflow(makeTags(3))
    // Before activation the panel content is not in the DOM.
    expect(document.body.textContent).not.toContain('tag-3')
    // Keyboard activation (Enter) reveals — proving focus/Enter reachability.
    await wrapper.find('.tag-overflow').trigger('keydown.enter')
    await flushPromises()
    await nextTick()
    // The Popover teleports to <body>; the hidden names must be reachable there,
    // NOT clipped inside the table cell.
    const panelId = wrapper.find('.tag-overflow').attributes('aria-controls')!
    const panel = document.getElementById(panelId)
    expect(panel).not.toBeNull()
    for (let i = 3; i <= 5; i++) {
      expect(panel!.textContent).toContain(`tag-${i}`)
    }
    // aria-expanded reflects the open state.
    expect(wrapper.find('.tag-overflow').attributes('aria-expanded')).toBe('true')
  })

  it('does not render inline tag names before activation (panel escapes the cell)', async () => {
    // The trigger itself must not carry the hidden badges inline — a regression
    // to an in-cell CSS panel (clippable) would embed them in the trigger.
    const wrapper = await mountOverflow(makeTags(3))
    const trigger = wrapper.find('.tag-overflow')
    expect(trigger.text()).not.toContain('tag-3')
  })

  it('Space activates the trigger (reveals the panel) without scrolling the page', async () => {
    const wrapper = await mountOverflow(makeTags(3))
    await wrapper.find('.tag-overflow').trigger('keydown.space')
    await flushPromises()
    await nextTick()
    const panelId = wrapper.find('.tag-overflow').attributes('aria-controls')!
    expect(document.getElementById(panelId)).not.toBeNull()
    expect(wrapper.find('.tag-overflow').attributes('aria-expanded')).toBe('true')
  })
})

describe('TagOverflow — clickable chips filter and close (#34)', () => {
  it('clicking a revealed chip filters by that tag and closes the popover', async () => {
    const wrapper = await mountOverflow(makeTags(3))
    const store = useTagFilterStore()
    const selectSpy = vi.spyOn(store, 'selectTag').mockImplementation(() => {})

    // Open the popover.
    await wrapper.find('.tag-overflow').trigger('click')
    await flushPromises()
    await nextTick()
    const panelId = wrapper.find('.tag-overflow').attributes('aria-controls')!
    const panel = document.getElementById(panelId)!
    expect(wrapper.find('.tag-overflow').attributes('aria-expanded')).toBe('true')

    // The revealed chips are clickable filter buttons; the first hidden tag is t3.
    const chip = panel.querySelector('button.tag-clickable') as HTMLButtonElement
    expect(chip).not.toBeNull()
    chip.click()
    await flushPromises()
    await nextTick()

    expect(selectSpy).toHaveBeenCalledWith('t3')
    // Selecting closes the popover.
    expect(wrapper.find('.tag-overflow').attributes('aria-expanded')).toBe('false')
  })
})
