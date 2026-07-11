import { computed, type Ref } from 'vue'
import { isMetaTag, type Tag, type CoOccurrencePair } from '../api/tag'
import type { TreeNode } from 'primevue/treenode'

/**
 * TOTAL bound on a facet node's child array (#12). This is the whole array,
 * NOT just the real children: at most (MAX_FACET_CHILDREN - 1) real
 * co-occurrence children plus one terminal overflow node. Capping the child
 * arrays keeps the dense-tag facet view (the #12 reporter hit ~350 nested
 * tags) from building — and PrimeVue Tree from rendering — an unbounded tree.
 */
export const MAX_FACET_CHILDREN = 20

/** A real facet node: a tag, plus its per-context stats. */
export interface FacetNodeData extends Tag {
  /** Documents carrying this tag (root nodes only; 0 for children). */
  docCount?: number
  /** Co-occurrence count with the parent tag (child nodes only). */
  coCount?: number
}

/** The terminal "…and K more" overflow node's data. */
export interface OverflowNodeData {
  kind: 'overflow'
  /** Number of eligible co-occurrence children hidden behind this node. */
  hiddenCount: number
}

/** Discriminated union of the two node-data shapes the facet tree emits. */
export type FacetTreeNodeData = FacetNodeData | OverflowNodeData

export function useCoOccurrenceTree(
  allTags: Ref<Tag[]>,
  stats: Ref<Record<string, number>>,
  pairs: Ref<CoOccurrencePair[]>,
) {
  const tagMap = computed(() => {
    const m = new Map<string, Tag>()
    for (const t of allTags.value) m.set(t.id, t)
    return m
  })

  const adjacency = computed(() => {
    const adj = new Map<string, Map<string, number>>()
    for (const p of pairs.value) {
      if (!adj.has(p.tagA)) adj.set(p.tagA, new Map())
      if (!adj.has(p.tagB)) adj.set(p.tagB, new Map())
      adj.get(p.tagA)!.set(p.tagB, p.count)
      adj.get(p.tagB)!.set(p.tagA, p.count)
    }
    return adj
  })

  // Per-tag child arrays, built once per pairs/allTags change and reused by the
  // final tree computed. Depends ONLY on adjacency (pairs) and tagMap (allTags)
  // — NOT on stats — so re-selection (which changes stats/counts, not pairs)
  // does not rebuild or re-sort the full adjacency. Each array is already capped
  // to MAX_FACET_CHILDREN total (real + overflow) via top-N-by-co-occurrence.
  const childrenByTag = computed(() => {
    const byTag = new Map<string, TreeNode[]>()
    for (const [tagId, coTags] of adjacency.value.entries()) {
      const owner = tagMap.value.get(tagId)
      // Skip meta-tag roots entirely — they never surface as facet roots, so
      // their child arrays are never consumed.
      if (!owner || isMetaTag(owner.name)) continue

      // Eligible co-occurrence neighbours: non-meta, resolvable tags.
      const eligible = [...coTags.entries()]
        .map(([otherId, coCount]) => ({ otherId, coCount, other: tagMap.value.get(otherId) }))
        .filter(
          (e): e is { otherId: string; coCount: number; other: Tag } =>
            !!e.other && !isMetaTag(e.other.name),
        )
        // Descending co-occurrence count; deterministic tie-break on tag name
        // then id so truncation is stable across builds.
        .sort(
          (a, b) =>
            b.coCount - a.coCount ||
            a.other.name.localeCompare(b.other.name) ||
            a.otherId.localeCompare(b.otherId),
        )

      const children: TreeNode[] = []
      if (eligible.length <= MAX_FACET_CHILDREN) {
        // Fits without an overflow slot: emit all eligible children.
        for (const e of eligible) {
          children.push({
            key: `${tagId}__${e.otherId}`,
            label: e.other.name,
            data: { ...e.other, coCount: e.coCount } as FacetNodeData,
            children: [],
          } as TreeNode)
        }
      } else {
        // Overflow: keep (MAX_FACET_CHILDREN - 1) real children + 1 overflow node.
        const shown = eligible.slice(0, MAX_FACET_CHILDREN - 1)
        for (const e of shown) {
          children.push({
            key: `${tagId}__${e.otherId}`,
            label: e.other.name,
            data: { ...e.other, coCount: e.coCount } as FacetNodeData,
            children: [],
          } as TreeNode)
        }
        const hiddenCount = eligible.length - shown.length
        children.push({
          key: `facet-overflow:${tagId}`,
          label: '',
          data: { kind: 'overflow', hiddenCount } as OverflowNodeData,
          children: [],
          selectable: false,
        } as TreeNode)
      }
      byTag.set(tagId, children)
    }
    return byTag
  })

  const treeNodes = computed<TreeNode[]>(() => {
    // Facets hides meta-tags (`__`-prefixed) from BOTH the top-level roots and the
    // co-occurrence children. They still appear in Tree mode / search / picker.
    // This computed layers per-root stats onto the memoized child arrays; it does
    // NOT rebuild or re-sort the adjacency.
    return allTags.value
      .filter((tag) => !isMetaTag(tag.name))
      .map((tag) => {
        const count = stats.value[tag.id] ?? 0
        return {
          key: tag.id,
          label: tag.name,
          data: { ...tag, docCount: count } as FacetNodeData,
          children: childrenByTag.value.get(tag.id) ?? [],
        } as TreeNode
      })
      .filter((n) => ((n.data as FacetNodeData).docCount ?? 0) > 0)
      .sort((a, b) => (a.label ?? '').localeCompare(b.label ?? ''))
  })

  return { treeNodes, adjacency, tagMap }
}
