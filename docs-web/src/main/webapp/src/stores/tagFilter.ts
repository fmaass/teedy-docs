import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { listTags, getTagStats, getTagFacets, getTagCoOccurrence, isMetaTag, type Tag, type CoOccurrencePair } from '../api/tag'
import { useCoOccurrenceTree } from '../composables/useCoOccurrenceTree'
import { queryKeys } from '../api/queryKeys'

export const useTagFilterStore = defineStore('tagFilter', () => {
  const router = useRouter()
  const route = useRoute()

  const selectedTagIds = ref(new Set<string>())
  const excludedTagIds = ref(new Set<string>())
  const searchText = ref('')
  const debouncedText = ref('')
  const tagMode = ref<'and' | 'or'>('and')

  const viewMode = ref<'tree' | 'facets'>(
    (localStorage.getItem('teedy_tag_view_mode') as 'tree' | 'facets') || 'tree'
  )

  watch(viewMode, (v) => localStorage.setItem('teedy_tag_view_mode', v))

  // --- Tag data ---

  // `tagsSettled` is the SETTLED-SUCCESS signal: true once the tags query has
  // SUCCESSFULLY returned at least once, INCLUDING a legitimately empty ([])
  // result. Load-state must be driven from this, never inferred from tagMap.size
  // (which cannot tell "still loading" apart from "loaded but empty").
  //
  // BL-024: it is gated on isSuccess, NOT isFetched. isFetched is true after an
  // ERROR too — so a transient /api/tag/list 500 on a cold deep-link would look
  // "settled", resolve the URL ids against an empty tagMap (dropping them all),
  // and let syncUrl rewrite the URL to a bare ?search=. Gating on isSuccess keeps
  // hydration DEFERRED on error (hydrating stays true → syncUrl suppressed → the
  // raw ids round-trip in the URL untouched), and a later successful refetch
  // completes hydration. A failed fetch must NEVER trigger a URL rewrite.
  const { data: tagsData, isSuccess: tagsSettled } = useQuery({
    queryKey: queryKeys.tags(),
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
    queryKey: queryKeys.tagStats(),
    queryFn: () => getTagStats().then((r) => r.data.stats),
    staleTime: 30_000,
  })

  const { data: facetData } = useQuery({
    queryKey: computed(() => [...queryKeys.tagFacets(), selectedTagIdArray.value, tagMode.value, excludedTagIdArray.value]),
    queryFn: () => getTagFacets(selectedTagIdArray.value, tagMode.value, excludedTagIdArray.value).then((r) => r.data),
    staleTime: 15_000,
    enabled: computed(() => selectedTagIds.value.size > 0),
  })

  const { data: coOccurrenceData } = useQuery({
    queryKey: queryKeys.tagCoOccurrence(),
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

  // Facet co-occurrence suggestion pills. Meta-tags (`__`-prefixed) are hidden
  // from suggestions; `tagCounts` itself is left intact so Tree-mode counts are
  // unaffected. Already-selected meta-tags still render via `selectedTags` (the
  // removable active chips), so the user can always deselect them. Excluded tags
  // are filtered client-side (belt-and-suspenders; the server already omits their
  // documents from the counts) so a `!tag:` never appears as a suggestion.
  const relatedTags = computed(() => {
    if (selectedTagIds.value.size === 0) return []
    return Object.entries(tagCounts.value)
      .map(([id, count]) => ({ tag: tagMap.value.get(id), count }))
      .filter((e): e is { tag: Tag; count: number } =>
        !!e.tag && e.count > 0 && !selectedTagIds.value.has(e.tag.id)
        && !excludedTagIds.value.has(e.tag.id) && !isMetaTag(e.tag.name),
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

  const manualExpandedKeys = ref<Record<string, boolean>>({})

  const expandedKeys = computed(() => {
    const keys: Record<string, boolean> = { ...manualExpandedKeys.value }
    for (const id of selectedTagIds.value) {
      let tag = tagMap.value.get(id)
      while (tag?.parent) {
        keys[tag.parent] = true
        tag = tagMap.value.get(tag.parent)
      }
    }
    return keys
  })

  function setExpandedKeys(keys: Record<string, boolean>) {
    manualExpandedKeys.value = keys
  }

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
    searchText.value = ''
    debouncedText.value = ''
    tagMode.value = 'and'
  }

  /**
   * The single canonical filter → route-query serializer. Every site that
   * navigates to (or returns to) the document list — `navigateToDocuments`,
   * `syncUrl`, and DocumentList's full-view `returnTo` — must build the query
   * from this one function so a filter dimension (notably `exclude`) can never
   * be dropped in one site but preserved in another.
   */
  function buildFilterQuery(): Record<string, string> {
    const query: Record<string, string> = {}
    if (selectedTagIds.value.size) query.tags = [...selectedTagIds.value].join(',')
    if (excludedTagIds.value.size) query.exclude = [...excludedTagIds.value].join(',')
    if (tagMode.value === 'or') query.mode = 'or'
    if (debouncedText.value.trim()) query.search = debouncedText.value.trim()
    // `workflow=me` (the "Assigned to me" filter) is owned by DocumentList's
    // component state, NOT this serializer — but this serializer is the SINGLE
    // source of the canonical URL every navigation + the syncUrl `router.replace`
    // build from. If it dropped the key, a tag/text/mode change would silently
    // strip the active workflow filter from the URL. So we PRESERVE the validated
    // key present in the current route query. Contract: only the scalar string
    // "me" activates; arrays/empty/unknown values are inactive and canonicalized
    // away (never re-emitted).
    if (route.query.workflow === 'me') query.workflow = 'me'
    return query
  }

  function navigateToDocuments() {
    router.push({ name: 'documents', query: buildFilterQuery() })
  }

  // --- URL sync ---

  // True from the moment hydration begins until it fully completes (all ids
  // resolved). While set, the URL is authoritative and syncUrl is suppressed —
  // otherwise the PARTIAL state initFromUrl writes (search/mode applied before
  // tags settle) would trigger syncUrl → router.replace with a query missing the
  // not-yet-resolved tags/exclude, dropping them from the URL before they can
  // hydrate. This closes the write-back feedback loop at its source.
  let hydrating = false

  function syncUrl() {
    if (hydrating) return
    if (!route.path.startsWith('/document') || route.path.startsWith('/document/')) return
    router.replace({ name: 'documents', query: buildFilterQuery() })
  }

  watch([selectedTagIdArray, excludedTagIdArray, tagMode, debouncedText], syncUrl)

  // Fully reflect the route query as the source of truth: EVERY filter dimension
  // is set from the query, and a dimension ABSENT from the query is reset to its
  // default (empty selection/exclusion, 'and' mode, empty search). Applying only
  // present dimensions would leave stale state when navigating back from a
  // filtered URL to a bare /document.
  //
  // Tag-INDEPENDENT dimensions (search, mode, and the clears) always apply.
  // Tag/exclude id resolution needs the tags query to have SETTLED: while the
  // query is still in flight AND the URL carries ids, we cannot know which ids
  // are valid, so we return false to DEFER. Once the query has settled — even to
  // an empty [] — we resolve whatever ids match (possibly none) and return true.
  // Never infer load-state from tagMap.size (loaded-empty looks identical to
  // still-loading).
  function initFromUrl(): boolean {
    const raw = (route.query.tags as string) || ''
    const rawExcl = (route.query.exclude as string) || ''
    const mode = (route.query.mode as string) || 'and'
    const search = (route.query.search as string) || ''

    // Tag-INDEPENDENT dimensions apply IMMEDIATELY and unconditionally, ABOVE the
    // defer point — a cold-load of /document?tags=a&search=invoice must run the
    // document query WITH the known search right away, not wait for tags to
    // settle. Only tag-id RESOLUTION below is gated on the settled signal.
    tagMode.value = mode === 'or' ? 'or' : 'and'
    searchText.value = search
    debouncedText.value = search

    // Defer only the id resolution: while the tags query is in flight and the URL
    // carries ids, we cannot know which ids are valid. Return false so the
    // signature stays pending and the settled-signal watch re-runs this (which
    // re-applies the tag-independent dims idempotently and then resolves the ids).
    const hasIdsToResolve = !!(raw || rawExcl)
    if (hasIdsToResolve && !tagsSettled.value) return false

    const selIds = raw ? raw.split(',').filter(Boolean) : []
    selectedTagIds.value = new Set(selIds.filter((id) => tagMap.value.has(id)))

    const exclIds = rawExcl ? rawExcl.split(',').filter(Boolean) : []
    excludedTagIds.value = new Set(exclIds.filter((id) => tagMap.value.has(id)))

    return true
  }

  // Hydration must be driven by the ROUTE QUERY, not by tag data. A ['tags']
  // refetch (tag CRUD invalidates it; 60s stale) must NOT re-read the route and
  // clobber the user's live selection. But a genuine route-query change
  // (back-button, in-session nav to /document?tags=…&exclude=…) MUST re-hydrate.
  //
  // We key hydration on a signature of the route query and re-run initFromUrl
  // only when that signature changes. The signature is committed ONLY once
  // hydration actually applied — if ids are present but the tags query has not
  // settled yet, it stays pending so the settled-signal watch retries.
  function routeQuerySignature(): string {
    const q = route.query
    return JSON.stringify([q.tags ?? '', q.exclude ?? '', q.mode ?? '', q.search ?? ''])
  }

  let hydratedSignature: string | null = null
  function hydrateFromRoute() {
    // BL-023: hydration is SCOPED to the documents list. The route query is the
    // source of truth ONLY for that view; on any other route (Settings, Tags,
    // doc-add) vue-router carries an empty query, and hydrating from it would wipe
    // the store — killing the "Back to documents" filter-preserving affordance
    // (AppLayout) and the new-doc active-filter pre-seed (DocumentEdit). Preserve
    // the store state elsewhere; re-hydrate on entry back to the documents route
    // (this fires again via the route-signature and settled-signal watches).
    if (route.name !== 'documents') return
    const sig = routeQuerySignature()
    if (sig === hydratedSignature) return
    // The URL is authoritative for the whole hydration span. Suppress syncUrl
    // (via `hydrating`) so the partial state initFromUrl writes before ids
    // resolve is never written back to the URL — otherwise unresolved
    // tags/exclude would be dropped from the query mid-hydration.
    hydrating = true
    // Commit the signature only if hydration COMPLETED. A false return means ids
    // were present but the tags query had not settled — leave the signature
    // pending AND `hydrating` true so the settled-signal retry finishes it.
    // Because settle is keyed on isSuccess (true even for [], but NOT on error),
    // initFromUrl returns true once the query SUCCESSFULLY settles, so `hydrating`
    // clears then. On error it stays true (URL preserved) until a later success —
    // that is the BL-024 no-rewrite-on-failure guarantee, not a stuck-true bug.
    if (initFromUrl()) {
      hydratedSignature = sig
      hydrating = false
    }
  }

  watch(
    () => routeQuerySignature(),
    () => hydrateFromRoute(),
  )

  // Drive hydration off the SETTLED signal, not `tags?.length`. `immediate: true`
  // runs it at store creation: even while the tags query is still in flight, this
  // applies the tag-INDEPENDENT dims (search/mode/clears) immediately and defers
  // only id resolution (signature left pending). When the query later settles —
  // even to an empty [] — the watch fires again and completes hydration (commits
  // the signature). Called unconditionally: the signature guard in
  // hydrateFromRoute makes an already-completed hydration a no-op, so a bare
  // ['tags'] refetch never clobbers the live selection.
  watch(
    tagsSettled,
    () => hydrateFromRoute(),
    { immediate: true },
  )

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
    setExpandedKeys,
    activeTreeNodes,
    activeExpandedKeys,
    resolveCompoundKey,
    toggleTag,
    removeTag,
    clearFilters,
    navigateToDocuments,
    buildFilterQuery,
  }
})
