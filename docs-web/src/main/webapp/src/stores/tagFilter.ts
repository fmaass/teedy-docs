import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { listTags, getTagStats, getTagFacets, getTagCoOccurrence, type Tag, type CoOccurrencePair } from '../api/tag'
import { useCoOccurrenceTree } from '../composables/useCoOccurrenceTree'

export const useTagFilterStore = defineStore('tagFilter', () => {
  const router = useRouter()
  const route = useRoute()

  const selectedTagIds = ref(new Set<string>())
  const excludedTagIds = ref(new Set<string>())
  const showUntagged = ref(false)
  const searchText = ref('')
  const debouncedText = ref('')
  const tagMode = ref<'and' | 'or'>('and')

  const viewMode = ref<'tree' | 'facets'>(
    (localStorage.getItem('teedy_tag_view_mode') as 'tree' | 'facets') || 'tree'
  )

  watch(viewMode, (v) => localStorage.setItem('teedy_tag_view_mode', v))

  // --- Tag data ---

  const { data: tagsData } = useQuery({
    queryKey: ['tags'],
    queryFn: () => listTags().then((r) => r.data.tags),
    staleTime: 60_000,
  })

  const allTags = computed(() => tagsData.value ?? [])
  const tagMap = computed(() => {
    const m = new Map<string, Tag>()
    for (const t of allTags.value) m.set(t.id, t)
    return m
  })

  // --- Facet counts (two-endpoint strategy) ---

  const selectedTagIdArray = computed(() => [...selectedTagIds.value])
  const excludedTagIdArray = computed(() => [...excludedTagIds.value])

  const { data: statsData } = useQuery({
    queryKey: ['tagStats'],
    queryFn: () => getTagStats().then((r) => r.data.stats),
    staleTime: 30_000,
  })

  const { data: facetData } = useQuery({
    queryKey: computed(() => ['tagFacets', selectedTagIdArray.value, tagMode.value]),
    queryFn: () => getTagFacets(selectedTagIdArray.value, tagMode.value).then((r) => r.data),
    staleTime: 15_000,
    enabled: computed(() => selectedTagIds.value.size > 0),
  })

  const { data: coOccurrenceData } = useQuery({
    queryKey: ['tagCoOccurrence'],
    queryFn: () => getTagCoOccurrence().then((r) => r.data.pairs),
    staleTime: 60_000,
    enabled: computed(() => viewMode.value === 'facets'),
  })

  const coOccurrencePairs = computed<CoOccurrencePair[]>(() => coOccurrenceData.value ?? [])
  const {
    treeNodes: facetTreeNodes,
  } = useCoOccurrenceTree(allTags, computed(() => statsData.value ?? {}), coOccurrencePairs)

  const tagCounts = computed<Record<string, number>>(() => {
    if (selectedTagIds.value.size > 0 && facetData.value) {
      return facetData.value.facets
    }
    return statsData.value ?? {}
  })

  // --- Derived state ---

  const selectedTags = computed(() =>
    [...selectedTagIds.value]
      .map((id) => tagMap.value.get(id))
      .filter((t): t is Tag => !!t),
  )

  const excludedTags = computed(() =>
    [...excludedTagIds.value]
      .map((id) => tagMap.value.get(id))
      .filter((t): t is Tag => !!t),
  )

  const combinedSearch = computed(() => {
    const parts: string[] = selectedTags.value.map((t) => `tag:${t.name}`)
    for (const t of excludedTags.value) parts.push(`!tag:${t.name}`)
    const text = debouncedText.value.trim()
    if (text) parts.push(text)
    return parts.join(' ')
  })

  const hasActiveFilters = computed(() =>
    selectedTagIds.value.size > 0 || excludedTagIds.value.size > 0 || debouncedText.value.trim().length > 0,
  )

  const relatedTags = computed(() => {
    if (selectedTagIds.value.size === 0) return []
    return Object.entries(tagCounts.value)
      .map(([id, count]) => ({ tag: tagMap.value.get(id), count }))
      .filter((e): e is { tag: Tag; count: number } =>
        !!e.tag && e.count > 0 && !selectedTagIds.value.has(e.tag.id),
      )
      .sort((a, b) => b.count - a.count)
      .slice(0, 8)
  })

  // --- Tag tree ---

  interface TreeNode {
    key: string
    label: string
    data: Tag
    children: TreeNode[]
  }

  const tagTreeNodes = computed<TreeNode[]>(() => {
    const roots = allTags.value.filter((t) => !t.parent)
    function buildNode(tag: Tag): TreeNode {
      const children = allTags.value.filter((t) => t.parent === tag.id)
      return { key: tag.id, label: tag.name, data: tag, children: children.map(buildNode) }
    }
    return roots.map(buildNode)
  })

  const expandedKeys = computed(() => {
    const keys: Record<string, boolean> = {}
    if (selectedTagIds.value.size === 0) return keys
    for (const id of selectedTagIds.value) {
      let tag = tagMap.value.get(id)
      while (tag?.parent) {
        keys[tag.parent] = true
        tag = tagMap.value.get(tag.parent)
      }
    }
    return keys
  })

  const activeTreeNodes = computed<TreeNode[]>(() =>
    viewMode.value === 'facets' ? (facetTreeNodes.value as TreeNode[]) : tagTreeNodes.value,
  )

  const facetExpandedKeys = computed(() => {
    const keys: Record<string, boolean> = {}
    if (selectedTagIds.value.size === 0) return keys
    for (const node of facetTreeNodes.value) {
      const hasSelectedChild = node.children?.some((c: any) => {
        const childTagId = resolveCompoundKey(c.key as string)
        return selectedTagIds.value.has(childTagId)
      })
      if (hasSelectedChild || selectedTagIds.value.has(node.key as string)) {
        keys[node.key as string] = true
      }
    }
    return keys
  })

  const activeExpandedKeys = computed(() =>
    viewMode.value === 'facets' ? facetExpandedKeys.value : expandedKeys.value,
  )

  function resolveCompoundKey(key: string): string {
    const idx = key.indexOf('__')
    return idx >= 0 ? key.slice(idx + 2) : key
  }

  // --- Actions ---

  function toggleTag(tagId: string) {
    const resolvedId = resolveCompoundKey(tagId)

    if (selectedTagIds.value.has(resolvedId)) {
      const next = new Set(selectedTagIds.value)
      next.delete(resolvedId)
      selectedTagIds.value = next
      const excl = new Set(excludedTagIds.value)
      excl.add(resolvedId)
      excludedTagIds.value = excl
    } else if (excludedTagIds.value.has(resolvedId)) {
      const excl = new Set(excludedTagIds.value)
      excl.delete(resolvedId)
      excludedTagIds.value = excl
    } else {
      const next = new Set(selectedTagIds.value)
      next.add(resolvedId)
      // In Tree mode, also include ancestors (existing behavior)
      if (viewMode.value === 'tree') {
        let tag = tagMap.value.get(resolvedId)
        while (tag?.parent) {
          next.add(tag.parent)
          tag = tagMap.value.get(tag.parent)
        }
      }
      selectedTagIds.value = next
    }

    if (route.path !== '/document') {
      navigateToDocuments()
    }
  }

  function removeTag(tagId: string) {
    const sel = new Set(selectedTagIds.value)
    sel.delete(tagId)
    selectedTagIds.value = sel
    const excl = new Set(excludedTagIds.value)
    excl.delete(tagId)
    excludedTagIds.value = excl
  }

  function clearFilters() {
    selectedTagIds.value = new Set()
    excludedTagIds.value = new Set()
    showUntagged.value = false
    searchText.value = ''
    debouncedText.value = ''
    tagMode.value = 'and'
  }

  function navigateToDocuments() {
    const query: Record<string, string> = {}
    if (selectedTagIds.value.size) query.tags = [...selectedTagIds.value].join(',')
    if (excludedTagIds.value.size) query.exclude = [...excludedTagIds.value].join(',')
    if (tagMode.value === 'or') query.mode = 'or'
    if (debouncedText.value.trim()) query.search = debouncedText.value.trim()
    router.push({ name: 'documents', query })
  }

  // --- URL sync ---

  function syncUrl() {
    if (!route.path.startsWith('/document') || route.path.startsWith('/document/')) return
    const query: Record<string, string> = {}
    if (selectedTagIds.value.size) query.tags = [...selectedTagIds.value].join(',')
    if (excludedTagIds.value.size) query.exclude = [...excludedTagIds.value].join(',')
    if (tagMode.value === 'or') query.mode = 'or'
    if (debouncedText.value.trim()) query.search = debouncedText.value.trim()
    router.replace({ name: 'documents', query: Object.keys(query).length ? query : {} })
  }

  watch([selectedTagIdArray, excludedTagIdArray, tagMode, debouncedText], syncUrl)

  function initFromUrl() {
    const raw = (route.query.tags as string) || ''
    const rawExcl = (route.query.exclude as string) || ''
    const mode = (route.query.mode as string) || 'and'
    const search = (route.query.search as string) || ''

    if (raw) {
      const ids = raw.split(',').filter(Boolean)
      selectedTagIds.value = new Set(ids.filter((id) => tagMap.value.has(id)))
    }
    if (rawExcl) {
      const ids = rawExcl.split(',').filter(Boolean)
      excludedTagIds.value = new Set(ids.filter((id) => tagMap.value.has(id)))
    }
    if (mode === 'or') tagMode.value = 'or'
    if (search) {
      searchText.value = search
      debouncedText.value = search
    }
  }

  watch(tagsData, (tags) => {
    if (tags?.length) initFromUrl()
  }, { immediate: true })

  // --- Debounce ---

  let searchTimeout: ReturnType<typeof setTimeout>
  watch(searchText, (val) => {
    clearTimeout(searchTimeout)
    searchTimeout = setTimeout(() => {
      debouncedText.value = val
    }, 300)
  })

  return {
    selectedTagIds,
    excludedTagIds,
    searchText,
    debouncedText,
    tagMode,
    allTags,
    tagMap,
    selectedTagIdArray,
    excludedTagIdArray,
    tagCounts,
    selectedTags,
    excludedTags,
    combinedSearch,
    hasActiveFilters,
    relatedTags,
    viewMode,
    tagTreeNodes,
    expandedKeys,
    activeTreeNodes,
    activeExpandedKeys,
    resolveCompoundKey,
    toggleTag,
    removeTag,
    clearFilters,
    navigateToDocuments,
  }
})
