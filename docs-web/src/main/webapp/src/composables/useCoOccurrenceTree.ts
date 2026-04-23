import { computed, type Ref } from 'vue'
import type { Tag, CoOccurrencePair } from '../api/tag'
import type { TreeNode } from 'primevue/treenode'

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

  const treeNodes = computed<TreeNode[]>(() => {
    return allTags.value
      .map((tag) => {
        const count = stats.value[tag.id] ?? 0
        const coTags = adjacency.value.get(tag.id)
        const children: TreeNode[] = coTags
          ? [...coTags.entries()]
              .map(([otherId, coCount]) => {
                const other = tagMap.value.get(otherId)
                if (!other) return null
                return {
                  key: `${tag.id}__${otherId}`,
                  label: other.name,
                  data: { ...other, coCount },
                  children: [],
                } as TreeNode
              })
              .filter((n): n is TreeNode => n !== null)
              .sort((a, b) => (a.label ?? '').localeCompare(b.label ?? ''))
          : []
        return {
          key: tag.id,
          label: tag.name,
          data: { ...tag, docCount: count },
          children,
        } as TreeNode
      })
      .filter((n) => (n.data as any).docCount > 0)
      .sort((a, b) => (a.label ?? '').localeCompare(b.label ?? ''))
  })

  return { treeNodes, adjacency, tagMap }
}
