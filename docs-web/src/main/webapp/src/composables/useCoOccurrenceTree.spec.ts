import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useCoOccurrenceTree } from './useCoOccurrenceTree'
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
