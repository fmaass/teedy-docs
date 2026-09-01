import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'
import type { Tag } from '../../api/tag'

// #298 part 3: the tag-management tree is where tags get cleaned up, and that job
// needs the per-tag document count on the node itself — including the ZEROES, which
// are exactly the tags a cleanup pass is looking for (the sidebar facet panel omits
// them, because there a zero means "not in this result set", not "unused").

const TAGS: Tag[] = [
  { id: 'tag-a', name: 'Alpha', color: '#111111', parent: null },
  { id: 'tag-b', name: 'Bravo', color: '#222222', parent: null },
  { id: 'tag-child', name: 'Charlie', color: '#333333', parent: 'tag-a' },
]

// The counts are DIRECT per-tag document counts: Alpha's own 7 documents, with the
// child's 3 deliberately NOT rolled into it.
const STATS: Record<string, number> = { 'tag-a': 7, 'tag-child': 3 }

const tagApiMock = vi.hoisted(() => ({
  listTags: vi.fn(),
  createTag: vi.fn(),
  getTagStats: vi.fn(),
  // #298 parts 1+2 added the maintenance reads to this page. They are stubbed here rather than
  // asserted on — the delete affordances have their own spec (TagList.maintenance.spec.ts); this
  // one stays about the counts.
  getTagMaintenance: vi.fn(),
  deleteTagSubtree: vi.fn(),
  deleteUnusedTags: vi.fn(),
  // Since #324 the compact create row hosts the icon field, so every mount of this page reads
  // the uploaded icon set.
  listTagIcons: vi.fn().mockResolvedValue({ data: { icons: [] } }),
}))
vi.mock('../../api/tag', () => tagApiMock)

import TagList from './TagList.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: { template: '<div/>' } },
    { path: '/tags', name: 'tags', component: { template: '<div/>' } },
    { path: '/tags/:id', name: 'tag-edit', component: { template: '<div/>' }, props: true },
  ],
})

async function mountList() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router.push('/tags')
  await router.isReady()
  const wrapper = mount(TagList, {
    global: {
      // Pinia: the create card reads the signed-in username from the auth store (#306) to show
      // the owner grant the server will create. A real, empty store answers "nobody", which is
      // all this spec's tree assertions need.
      plugins: [i18n, router, createPinia(), PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
    },
  })
  await flushPromises()
  return wrapper
}

// Map a rendered tag node's label -> its count text (null when the node renders no
// count at all), so an assertion names the tag rather than a DOM position.
function countByLabel(wrapper: Awaited<ReturnType<typeof mountList>>): Record<string, string | null> {
  const out: Record<string, string | null> = {}
  for (const node of wrapper.findAll('.tag-node')) {
    const label = node.find('.tag-label').text()
    const count = node.find('.tag-count')
    out[label] = count.exists() ? count.text().trim() : null
  }
  return out
}

describe('TagList — per-tag document count in the management tree (#298)', () => {
  beforeEach(() => {
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.createTag.mockReset().mockResolvedValue({ data: { id: 'new' } })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: STATS } })
    tagApiMock.getTagMaintenance.mockReset().mockResolvedValue({ data: { tags: [] } })
    tagApiMock.deleteTagSubtree.mockReset()
    tagApiMock.deleteUnusedTags.mockReset()
  })

  it("renders a tag node's own document count", async () => {
    const wrapper = await mountList()
    expect(countByLabel(wrapper).Alpha).toBe('7')
  })

  it('renders 0 for a tag no document carries (the cleanup case)', async () => {
    const wrapper = await mountList()
    // Bravo is absent from the stats map entirely — the tree must still show a zero.
    expect(countByLabel(wrapper).Bravo).toBe('0')
  })

  it('shows the direct count, not a subtree roll-up', async () => {
    const wrapper = await mountList()
    // Both halves are load-bearing: the positive one reds on a broken render (an
    // absent or empty badge would satisfy the negative one on its own), and the
    // negative one names the specific wrong answer — Alpha 7 + Charlie 3 = 10 is
    // what a subtree roll-up would print.
    expect(countByLabel(wrapper).Alpha).toBe('7')
    expect(countByLabel(wrapper).Alpha).not.toBe('10')
  })

  it('fetches the stats once for the whole tree, not per node', async () => {
    await mountList()
    expect(tagApiMock.getTagStats).toHaveBeenCalledTimes(1)
  })

  // A zero on this screen is an instruction to delete the tag. If the stats request
  // failed, the honest answer is "unknown", so the badge must be absent — rendering
  // the `?? 0` fallback would invite deleting tags that hold documents. The tree
  // itself is fed by a different query and must survive the failure intact.
  it('renders no count at all when the stats request fails', async () => {
    tagApiMock.getTagStats.mockReset().mockRejectedValue(new Error('boom'))
    const wrapper = await mountList()
    expect(wrapper.findAll('.tag-count')).toHaveLength(0)
    expect(Object.keys(countByLabel(wrapper)).sort()).toEqual(['Alpha', 'Bravo'])
  })
})
