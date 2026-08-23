// Transient, view-only ordering of a document's related documents (#296).
//
// The backend already returns relations ordered by title (`RelationDao.getByDocumentId`
// → `order by d.DOC_TITLE_C`, in the DATABASE's collation), which is the ascending case here;
// this exists so the reader can flip that order — and get their own locale's collation for it —
// without a round trip. The whole list arrives with the document,
// so this is a pure client-side projection — no fetch, no persistence, no server sort param.
//
// It deliberately does NOT reuse `fileSort.ts`. That helper is pinned to a different contract:
// reproducing PrimeVue DataTable's `sortSingle` for the file LIST view, including a
// nullSortOrder rule for empty values and three sortable fields. A relation carries exactly one
// orderable value — a title the backend requires and length-caps, so never empty — and there is
// no DataTable to stay in parity with. The one property the two genuinely share is the collator,
// which both take from the platform rather than from each other:
//
//   Titles compare through `Intl.Collator(undefined, { numeric: true })` — the SAME comparator
//   PrimeVue collates strings with (`localeComparator()`), so "Invoice 2" precedes "Invoice 10"
//   and "Äpfel" lands among the A's instead of after "Zebra". relationSort.spec.ts pins that
//   agreement pairwise against the real primitive.
//
// Ties keep their input order: `Array.prototype.sort` is stable (ES2019+), so two documents
// sharing a title stay in the order the server sent them, in both directions.

export type RelationSortDirection = 'asc' | 'desc'

// The subset of a relation an ordering reads. Structural on purpose: the view sorts
// `DocumentDetail['relations'][number]`, which carries id/source as well, and satisfies this
// without either side importing the other.
export interface SortableRelation {
  title: string
}

// Created once: constructing an Intl.Collator per comparison is the expensive part.
let collator: Intl.Collator | null = null

/**
 * Compare two relation titles in the given direction. Exported so the parity test can drive it
 * directly against PrimeVue's own comparator.
 */
export function compareRelationTitles(
  a: string,
  b: string,
  direction: RelationSortDirection,
): number {
  collator ??= new Intl.Collator(undefined, { numeric: true })
  return (direction === 'desc' ? -1 : 1) * collator.compare(a, b)
}

/**
 * A sorted CLONE. The caller's array — for the view, a computed slice of the document's own
 * `relations` — is never mutated.
 */
export function sortRelations<T extends SortableRelation>(
  relations: readonly T[],
  direction: RelationSortDirection,
): T[] {
  return [...relations].sort((a, b) => compareRelationTitles(a.title, b.title, direction))
}
