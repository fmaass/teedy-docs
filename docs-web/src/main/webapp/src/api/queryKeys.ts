/**
 * Single source of truth for TanStack Vue Query cache keys.
 *
 * Query keys are strings scattered across stores/composables/views; when a
 * mutation must invalidate a read, both sides have to agree on the exact key
 * prefix or the invalidation silently no-ops (stale sidebar/facet counts were
 * the recorded symptom). Centralising the keys here makes "which mutation
 * invalidates which read" auditable and refactor-safe.
 *
 * Prefixes intentionally match the string literals already used by the wider
 * app (`'documents'`, `['document', id]`, `'tags'`, `'trash'`, `'app-info'`)
 * so this module can be adopted incrementally without breaking untouched views.
 */
export const queryKeys = {
  /** Paginated document list (the DocumentList table). */
  documents: () => ['documents'] as const,
  /** A single document detail by id (slide-over, document view). */
  document: (id: string) => ['document', id] as const,
  /** Full tag list. */
  tags: () => ['tags'] as const,
  /** Per-tag document counts (sidebar tree counts). */
  tagStats: () => ['tagStats'] as const,
  /** Facet counts for the current selection/exclusion. */
  tagFacets: () => ['tagFacets'] as const,
  /** Tag co-occurrence pairs (facets-mode tree). */
  tagCoOccurrence: () => ['tagCoOccurrence'] as const,
  /** Trashed documents. */
  trash: () => ['trash'] as const,
  /** Application info. */
  app: () => ['app-info'] as const,
  /** Theme configuration (custom app name / color / css). */
  theme: () => ['theme'] as const,
} as const

/**
 * Every query whose result depends on the set of tags on documents. A tag
 * add/remove/bulk edit or a document CRUD staling these keeps sidebar and facet
 * counts in sync. Passed as the partial-match prefix to `invalidateQueries`.
 */
export const tagCountKeys = [
  queryKeys.tagStats(),
  queryKeys.tagFacets(),
  queryKeys.tagCoOccurrence(),
] as const
