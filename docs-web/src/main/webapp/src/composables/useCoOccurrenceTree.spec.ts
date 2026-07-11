import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import {
  useCoOccurrenceTree,
  MAX_FACET_CHILDREN,
  type FacetNodeData,
  type OverflowNodeData,
} from './useCoOccurrenceTree'
import type { Tag, CoOccurrencePair } from '../api/tag'

const TAGS: Tag[] = [
  { id: 'a', name: 'alpha', color: '#111', parent: null },
  { id: 'b', name: 'beta', color: '#222', parent: null },
  { id: 'm', name: '__recent', color: '#999', parent: null },
]

function build(
  tags: Tag[],
  stats: Record<string, number>,
  pairs: CoOccurrencePair[],
) {
  return useCoOccurrenceTree(ref(tags), ref(stats), ref(pairs))
}

function isOverflow(data: unknown): data is OverflowNodeData {
  return !!data && (data as { kind?: string }).kind === 'overflow'
}

describe('useCoOccurrenceTree — meta-tag exclusion (facets only)', () => {
  it('excludes `__`-prefixed tags from the top-level facet roots', () => {
    const { treeNodes } = build(
      TAGS,
      { a: 3, b: 2, m: 9 },
      [],
    )
    expect(treeNodes.value.map((n) => n.key).sort()).toEqual(['a', 'b'])
    expect(treeNodes.value.some((n) => n.label === '__recent')).toBe(false)
  })

  it('excludes a `__`-prefixed tag from a normal tag co-occurrence children', () => {
    // alpha co-occurs with beta AND __recent; only beta should surface as a child.
    const { treeNodes } = build(
      TAGS,
      { a: 3, b: 2, m: 9 },
      [
        { tagA: 'a', tagB: 'b', count: 2 },
        { tagA: 'a', tagB: 'm', count: 5 },
      ],
    )
    const alpha = treeNodes.value.find((n) => n.key === 'a')!
    expect(alpha.children?.map((c) => c.key)).toEqual(['a__b'])
    expect(alpha.children?.some((c) => c.label === '__recent')).toBe(false)
  })

  it('still builds normal roots and children when no meta-tags are present', () => {
    const { treeNodes } = build(
      [TAGS[0], TAGS[1]],
      { a: 3, b: 2 },
      [{ tagA: 'a', tagB: 'b', count: 2 }],
    )
    expect(treeNodes.value.map((n) => n.key).sort()).toEqual(['a', 'b'])
    expect(treeNodes.value.find((n) => n.key === 'a')!.children?.[0].key).toBe('a__b')
  })
})

// ── #12: bounded facet tree ──────────────────────────────────────────────────
// Builds a dense fixture: `roots` real root tags, each co-occurring with EVERY
// other tag, so every adjacency is at its theoretical max and the cap must bite.
function denseFixture(roots: number) {
  const tags: Tag[] = Array.from({ length: roots }, (_, i) => ({
    id: `t${i}`,
    name: `tag-${String(i).padStart(4, '0')}`,
    color: '#123456',
    parent: null,
  }))
  const stats: Record<string, number> = {}
  for (let i = 0; i < roots; i++) stats[`t${i}`] = roots - i // every root has docs, distinct counts
  const pairs: CoOccurrencePair[] = []
  for (let i = 0; i < roots; i++) {
    for (let j = i + 1; j < roots; j++) {
      // Give each pair a distinct co-occurrence count so the descending sort +
      // truncation is deterministic and the "top-N" selection is observable.
      pairs.push({ tagA: `t${i}`, tagB: `t${j}`, count: i * roots + j })
    }
  }
  return { tags, stats, pairs }
}

describe('useCoOccurrenceTree — bounded children (#12 dense fixture)', () => {
  const ROOTS = 352
  const { tags, stats, pairs } = denseFixture(ROOTS)

  it('exposes MAX_FACET_CHILDREN as the TOTAL child-array bound (19 real + 1 overflow)', () => {
    expect(MAX_FACET_CHILDREN).toBe(20)
  })

  it('caps every child array at MAX_FACET_CHILDREN total (real + overflow)', () => {
    const { treeNodes } = build(tags, stats, pairs)
    expect(treeNodes.value.length).toBe(ROOTS)
    for (const node of treeNodes.value) {
      const children = node.children ?? []
      expect(children.length).toBeLessThanOrEqual(MAX_FACET_CHILDREN)
      // In this dense fixture every root co-occurs with ROOTS-1 > 19 others, so
      // each MUST truncate to exactly 19 real + 1 overflow.
      expect(children.length).toBe(MAX_FACET_CHILDREN)
      const overflows = children.filter((c) => isOverflow(c.data))
      expect(overflows.length).toBe(1)
      // The overflow node is the LAST child.
      expect(isOverflow(children[children.length - 1].data)).toBe(true)
    }
  })

  it('bounds the total emitted node count on the dense fixture', () => {
    const { treeNodes } = build(tags, stats, pairs)
    let total = 0
    for (const node of treeNodes.value) {
      total += 1 + (node.children?.length ?? 0)
    }
    // roots + roots*MAX_FACET_CHILDREN is the hard ceiling.
    expect(total).toBeLessThanOrEqual(ROOTS * (1 + MAX_FACET_CHILDREN))
    expect(total).toBe(ROOTS * (1 + MAX_FACET_CHILDREN))
  })

  it('overflow hiddenCount is truthful (real children shown + hidden = total eligible)', () => {
    const { treeNodes } = build(tags, stats, pairs)
    for (const node of treeNodes.value) {
      const children = node.children ?? []
      const realChildren = children.filter((c) => !isOverflow(c.data))
      const overflow = children.find((c) => isOverflow(c.data))!
      const data = overflow.data as OverflowNodeData
      // Every root co-occurs with ROOTS-1 others (all non-meta here).
      const eligible = ROOTS - 1
      expect(realChildren.length).toBe(MAX_FACET_CHILDREN - 1)
      expect(data.hiddenCount).toBe(eligible - realChildren.length)
      expect(data.hiddenCount).toBeGreaterThan(0)
    }
  })

  it('selects the top-N children by DESCENDING co-occurrence count before truncation', () => {
    const { treeNodes } = build(tags, stats, pairs)
    // Root t0 co-occurs with t1..t351 at counts 0*352+j = j (j=1..351).
    // Descending => the highest-count neighbours (t351, t350, …) survive.
    const root0 = treeNodes.value.find((n) => n.key === 't0')!
    const realChildren = (root0.children ?? []).filter((c) => !isOverflow(c.data))
    const coCounts = realChildren.map((c) => (c.data as FacetNodeData).coCount ?? 0)
    // Sorted strictly descending.
    for (let i = 1; i < coCounts.length; i++) {
      expect(coCounts[i - 1]).toBeGreaterThan(coCounts[i])
    }
    // Highest neighbour of t0 is t351 with count 351.
    expect(coCounts[0]).toBe(351)
  })

  it('reserved overflow key is facet-overflow:<rootId> and is unique per root', () => {
    const { treeNodes } = build(tags, stats, pairs)
    const overflowKeys = new Set<string>()
    for (const node of treeNodes.value) {
      const overflow = (node.children ?? []).find((c) => isOverflow(c.data))!
      expect(overflow.key).toBe(`facet-overflow:${node.key}`)
      expect(overflowKeys.has(overflow.key as string)).toBe(false)
      overflowKeys.add(overflow.key as string)
    }
  })

  it('root docCounts are preserved (stats not mutated by the cap)', () => {
    const { treeNodes } = build(tags, stats, pairs)
    for (const node of treeNodes.value) {
      const data = node.data as FacetNodeData
      expect(data.docCount).toBe(stats[node.key as string])
    }
  })

  it('builds the dense tree within a generous wall-clock smoke bound', () => {
    const start = performance.now()
    const { treeNodes } = build(tags, stats, pairs)
    // Force the computed to evaluate.
    void treeNodes.value.length
    const elapsed = performance.now() - start
    expect(elapsed).toBeLessThan(2000)
  })
})

describe('useCoOccurrenceTree — no overflow below the cap', () => {
  it('emits no overflow node when co-occurring tags fit within the real slot budget', () => {
    // 3 real neighbours < 19 real slots => no overflow.
    const tags: Tag[] = [
      { id: 'r', name: 'root', color: '#111', parent: null },
      { id: 'x', name: 'x', color: '#222', parent: null },
      { id: 'y', name: 'y', color: '#333', parent: null },
      { id: 'z', name: 'z', color: '#444', parent: null },
    ]
    const { treeNodes } = build(
      tags,
      { r: 5, x: 1, y: 1, z: 1 },
      [
        { tagA: 'r', tagB: 'x', count: 3 },
        { tagA: 'r', tagB: 'y', count: 2 },
        { tagA: 'r', tagB: 'z', count: 1 },
      ],
    )
    const root = treeNodes.value.find((n) => n.key === 'r')!
    const children = root.children ?? []
    expect(children.length).toBe(3)
    expect(children.some((c) => isOverflow(c.data))).toBe(false)
    // Descending by count.
    expect((children[0].data as FacetNodeData).coCount).toBe(3)
  })

  it('does NOT emit overflow when exactly MAX_FACET_CHILDREN eligible (all fit, none hidden)', () => {
    // 20 eligible neighbours == the total bound: showing all 20 keeps the array
    // within bound and hides NOTHING, so no overflow node is warranted.
    const tags: Tag[] = [{ id: 'r', name: 'root', color: '#111', parent: null }]
    const stats: Record<string, number> = { r: 100 }
    const pairs: CoOccurrencePair[] = []
    for (let i = 0; i < MAX_FACET_CHILDREN; i++) {
      const id = `n${i}`
      tags.push({ id, name: `n${i}`, color: '#000', parent: null })
      stats[id] = 1
      pairs.push({ tagA: 'r', tagB: id, count: i + 1 })
    }
    const { treeNodes } = build(tags, stats, pairs)
    const root = treeNodes.value.find((n) => n.key === 'r')!
    const children = root.children ?? []
    expect(children.length).toBe(MAX_FACET_CHILDREN)
    expect(children.some((c) => isOverflow(c.data))).toBe(false)
  })

  it('emits overflow one past the bound (21 eligible => 19 real + 1 overflow, hiddenCount=2)', () => {
    const tags: Tag[] = [{ id: 'r', name: 'root', color: '#111', parent: null }]
    const stats: Record<string, number> = { r: 100 }
    const pairs: CoOccurrencePair[] = []
    for (let i = 0; i < MAX_FACET_CHILDREN + 1; i++) {
      const id = `n${i}`
      tags.push({ id, name: `n${i}`, color: '#000', parent: null })
      stats[id] = 1
      pairs.push({ tagA: 'r', tagB: id, count: i + 1 })
    }
    const { treeNodes } = build(tags, stats, pairs)
    const root = treeNodes.value.find((n) => n.key === 'r')!
    const children = root.children ?? []
    expect(children.length).toBe(MAX_FACET_CHILDREN)
    const realChildren = children.filter((c) => !isOverflow(c.data))
    expect(realChildren.length).toBe(MAX_FACET_CHILDREN - 1)
    const overflow = children.find((c) => isOverflow(c.data))!
    // 21 eligible, 19 shown => 2 hidden.
    expect((overflow.data as OverflowNodeData).hiddenCount).toBe(2)
  })
})
