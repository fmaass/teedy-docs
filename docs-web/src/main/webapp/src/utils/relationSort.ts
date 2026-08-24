// Transient, view-only ordering of a document's related documents (#296).
//
// The backend already returns relations ordered by title (`RelationDao.getByDocumentId`
// → `order by d.DOC_TITLE_C`, in the DATABASE's collation), which is the ascending title case here;
// this exists so the reader can flip that order — and get their own locale's collation for it —
// without a round trip, and can order by the linked document's AGE instead. Both values the
// ordering reads travel with the document (`RelationDao` joins the other document's title AND its
// `DOC_CREATEDATE_D`), so this is a pure client-side projection — no fetch, no persistence, no
// server sort param, and no request per link.
//
// It deliberately does NOT reuse `fileSort.ts`. That helper is pinned to a different contract:
// reproducing PrimeVue DataTable's `sortSingle` for the file LIST view, so that the grid and the
// list agree field for field. Relations have no DataTable to stay in parity with, and borrowing
// that comparator would silently re-point this ordering at every future change made for the file
// views' sake. The one property the two genuinely share is the collator, which both take from the
// platform rather than from each other:
//
//   Titles compare through `Intl.Collator(undefined, { numeric: true })` — the SAME comparator
//   PrimeVue collates strings with (`localeComparator()`), so "Invoice 2" precedes "Invoice 10"
//   and "Äpfel" lands among the A's instead of after "Zebra". relationSort.spec.ts pins that
//   agreement pairwise against the real primitive.
//
// A MISSING creation date sorts LAST in BOTH directions rather than as 0 (which would read as
// 1970 and float the entry to the top of an ascending list) and rather than throwing. The wire
// field is non-null — `DOC_CREATEDATE_D` is NOT NULL and the mapper always writes it — so this is
// the comparator's totality, not a payload we expect: a helper that a malformed or older payload
// can make throw takes the whole document view down with it.
//
// Ties keep their input order: `Array.prototype.sort` is stable (ES2019+), so two documents
// sharing a title, or created in the same millisecond, stay in the order the server sent them, in
// both directions.

export type RelationSortField = 'title' | 'create_date'
export type RelationSortDirection = 'asc' | 'desc'

// The subset of a relation an ordering reads. Structural on purpose: the view sorts
// `DocumentDetail['relations'][number]`, which carries id/source as well, and satisfies this
// without either side importing the other. `create_date` is optional HERE only so the comparator
// stays total (see above); the wire type declares it required.
export interface SortableRelation {
  title: string
  create_date?: number | null
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
 * Compare two linked documents' creation dates (epoch millis) in the given direction. A missing
 * date sorts last whichever way the list runs — the returned sign for that case is deliberately
 * NOT multiplied by the direction.
 */
export function compareRelationCreateDates(
  a: number | null | undefined,
  b: number | null | undefined,
  direction: RelationSortDirection,
): number {
  const aMissing = a === null || a === undefined
  const bMissing = b === null || b === undefined
  if (aMissing && bMissing) return 0
  if (aMissing) return 1
  if (bMissing) return -1
  return (direction === 'desc' ? -1 : 1) * (a < b ? -1 : a > b ? 1 : 0)
}

/**
 * A sorted CLONE. The caller's array — for the view, a computed slice of the document's own
 * `relations` — is never mutated.
 */
export function sortRelations<T extends SortableRelation>(
  relations: readonly T[],
  field: RelationSortField,
  direction: RelationSortDirection,
): T[] {
  const compare =
    field === 'create_date'
      ? (a: T, b: T) => compareRelationCreateDates(a.create_date, b.create_date, direction)
      : (a: T, b: T) => compareRelationTitles(a.title, b.title, direction)
  return [...relations].sort(compare)
}
