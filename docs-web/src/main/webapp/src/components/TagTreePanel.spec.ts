import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import TagTreePanel from './TagTreePanel.vue'
import { useCoOccurrenceTree, MAX_FACET_CHILDREN } from '../composables/useCoOccurrenceTree'
import type { Tag, CoOccurrencePair } from '../api/tag'

// vue-i18n stub: return the key (with count baked in for overflow) so assertions
// target logic, not copy.
import { vi } from 'vitest'
vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (k: string, params?: Record<string, unknown>) =>
      params && 'count' in params ? `${k}:${params.count}` : k,
  }),
}))

// Build a dense fixture: `roots` tags, all pairwise co-occurring, distinct
// counts. Identical in shape to the composable spec's dense fixture.
function denseFixture(roots: number) {
  const tags: Tag[] = Array.from({ length: roots }, (_, i) => ({
    id: `t${i}`,
    name: `tag-${String(i).padStart(4, '0')}`,
    color: '#123456',
    parent: null,
  }))
  const stats: Record<string, number> = {}
  for (let i = 0; i < roots; i++) stats[`t${i}`] = roots - i
  const pairs: CoOccurrencePair[] = []
  for (let i = 0; i < roots; i++) {
    for (let j = i + 1; j < roots; j++) {
      pairs.push({ tagA: `t${i}`, tagB: `t${j}`, count: i * roots + j })
    }
  }
  return { tags, stats, pairs }
}

const modeOptions = [
  { label: 'AND', value: 'and' as const },
  { label: 'OR', value: 'or' as const },
]

function mountPanel(
  tagTreeNodes: unknown[],
  extra: Partial<Record<string, unknown>> = {},
) {
  return mount(TagTreePanel, {
    props: {
      tagMode: 'and',
      modeOptions,
      tagTreeNodes,
      expandedKeys: {},
      selectedTagIds: new Set<string>(),
      excludedTagIds: new Set<string>(),
      tagCounts: {},
      ...extra,
    } as never,
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: 'none' }]] },
  })
}

describe('TagTreePanel — bounded dense render (#12)', () => {
  // The #12 freeze cause was the CHILD combinatorial explosion (352 roots ×
  // ~351 children each ≈ 123k child nodes), not the flat root count. The cap
  // bounds the child surface; the mounted render test proves that when a
  // densely-connected root is EXPANDED, its rendered child DOM is capped at
  // MAX_FACET_CHILDREN regardless of how many tags it co-occurs with.
  //
  // Root-COUNT rendering (PrimeVue Tree renders every root row eagerly) is
  // intentionally exercised with a moderate root count here: in jsdom the
  // per-row cost is ~40ms and scales super-linearly, so mounting all 352 roots
  // is a TEST-ENVIRONMENT artifact (~15s), not a production freeze. That
  // residual root-eager-render cost is the recorded v3.4 virtualization
  // escalation (see fence report); this phase's fix is the child bound, proven
  // both structurally (useCoOccurrenceTree.spec.ts on the full 352-tag fixture)
  // and here on the mounted render.
  it('caps the rendered child DOM of a densely-connected root at MAX_FACET_CHILDREN', async () => {
    // One root co-occurring with 300 neighbours — the exact child-explosion the
    // cap fixes — plus a small set of sibling roots to keep the mount cheap.
    const NEIGHBOURS = 300
    const tags: Tag[] = [{ id: 'r', name: 'root', color: '#111', parent: null }]
    const stats: Record<string, number> = { r: 500 }
    const pairs: CoOccurrencePair[] = []
    for (let i = 0; i < NEIGHBOURS; i++) {
      const id = `n${i}`
      tags.push({ id, name: `n-${String(i).padStart(4, '0')}`, color: '#000', parent: null })
      stats[id] = 1
      pairs.push({ tagA: 'r', tagB: id, count: i + 1 })
    }
    const { treeNodes } = useCoOccurrenceTree(ref(tags), ref(stats), ref(pairs))
    const nodes = treeNodes.value
    // Expand the dense root so its children actually render (a collapsed tree
    // renders nothing and proves nothing).
    const wrapper = mountPanel(nodes, { expandedKeys: { r: true } })
    await flushPromises()

    const rootRow = nodes.find((n) => n.key === 'r')!
    // Structural: the root's own child array is bounded.
    expect((rootRow.children ?? []).length).toBe(MAX_FACET_CHILDREN)

    // Rendered DOM under the expanded root: exactly MAX_FACET_CHILDREN rows for
    // it — (MAX_FACET_CHILDREN - 1) interactive tag rows + 1 non-interactive
    // overflow row. The root itself is one more interactive row; there are
    // NEIGHBOURS other roots too (each with docCount 1), but NONE of them are
    // expanded, so only the dense root's children render.
    const overflowEls = wrapper.findAll('.tag-tree-node.tag-overflow')
    expect(overflowEls.length).toBe(1)

    // The dense root's rendered children = 19 interactive + 1 overflow = 20.
    // Total interactive tag rows = (1 + NEIGHBOURS) roots + 19 expanded children.
    const interactive = wrapper.findAll('.tag-tree-node[role="button"]')
    expect(interactive.length).toBe(1 + NEIGHBOURS + (MAX_FACET_CHILDREN - 1))
  }, 60000)

  it('builds the full 352-tag dense fixture within a generous wall-clock bound', () => {
    // The actual fix: the tree BUILD (cap + memoize) does not blow up on the
    // #12 reporter's ~350-tag scenario. Rendering is asserted structurally
    // above; the build is the algorithmic bound.
    const { tags, stats, pairs } = denseFixture(352)
    const start = performance.now()
    const { treeNodes } = useCoOccurrenceTree(ref(tags), ref(stats), ref(pairs))
    // Force evaluation of the whole tree + every child array.
    let total = 0
    for (const n of treeNodes.value) total += 1 + (n.children?.length ?? 0)
    const elapsed = performance.now() - start
    expect(total).toBe(352 * (1 + MAX_FACET_CHILDREN))
    expect(elapsed).toBeLessThan(2000)
  })
})

describe('TagTreePanel — overflow node is non-interactive (#12 blocker)', () => {
  // A single root with 25 co-occurring neighbours => 19 real + 1 overflow.
  function overflowFixture() {
    const tags: Tag[] = [{ id: 'r', name: 'root', color: '#111', parent: null }]
    const stats: Record<string, number> = { r: 100 }
    const pairs: CoOccurrencePair[] = []
    for (let i = 0; i < 25; i++) {
      const id = `n${i}`
      tags.push({ id, name: `n${i}`, color: '#000', parent: null })
      stats[id] = 1
      pairs.push({ tagA: 'r', tagB: id, count: i + 1 })
    }
    const { treeNodes } = useCoOccurrenceTree(ref(tags), ref(stats), ref(pairs))
    return treeNodes.value
  }

  it('renders the overflow node with the localized "…and K more" label', async () => {
    const nodes = overflowFixture()
    const wrapper = mountPanel(nodes, { expandedKeys: { r: true } })
    await flushPromises()
    const overflow = wrapper.find('.tag-tree-node.tag-overflow')
    expect(overflow.exists()).toBe(true)
    // 25 eligible, 19 shown => 6 hidden; stubbed t() returns "ui.facets_overflow:6".
    expect(overflow.text()).toContain('ui.facets_overflow:6')
  }, 15000)

  it('overflow node carries NO button role, tabindex, or aria-pressed', async () => {
    const nodes = overflowFixture()
    const wrapper = mountPanel(nodes, { expandedKeys: { r: true } })
    await flushPromises()
    const overflow = wrapper.find('.tag-tree-node.tag-overflow')
    expect(overflow.attributes('role')).toBeUndefined()
    expect(overflow.attributes('tabindex')).toBeUndefined()
    expect(overflow.attributes('aria-pressed')).toBeUndefined()
  }, 15000)

  it('click / enter / space on the overflow node emit NO selectTag', async () => {
    const nodes = overflowFixture()
    const wrapper = mountPanel(nodes, { expandedKeys: { r: true } })
    await flushPromises()
    const overflow = wrapper.find('.tag-tree-node.tag-overflow')

    await overflow.trigger('click')
    await overflow.trigger('keydown.enter')
    await overflow.trigger('keydown.space')

    expect(wrapper.emitted('selectTag')).toBeUndefined()
  }, 15000)

  it('a REAL child node still emits selectTag on click (guard is overflow-only)', async () => {
    const nodes = overflowFixture()
    const wrapper = mountPanel(nodes, { expandedKeys: { r: true } })
    await flushPromises()
    // Every interactive (role=button) node — roots + expanded real children.
    const interactive = wrapper.findAll('.tag-tree-node[role="button"]')
    expect(interactive.length).toBeGreaterThan(0)
    for (const el of interactive) await el.trigger('click')
    const emits = wrapper.emitted('selectTag') as Array<[string]> | undefined
    expect(emits).toBeTruthy()
    // At least one real compound child key was emitted…
    expect(emits!.some(([key]) => key.includes('__'))).toBe(true)
    // …and the overflow key was NEVER emitted (guard is overflow-only).
    expect(emits!.some(([key]) => key.startsWith('facet-overflow:'))).toBe(false)
  }, 15000)
})
