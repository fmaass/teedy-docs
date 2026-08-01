// Transient, view-only file sorting shared by the two file views (#211).
//
// The LIST view sorts through PrimeVue DataTable's own `sortSingle`, which is internal to the
// component and cannot be handed a comparator or reused from outside. The GRID is a plain CSS
// grid with no such machinery, so it sorts here — and the two views MUST agree, or the same
// document reads differently depending on a toggle.
//
// Rather than paraphrase DataTable's behaviour, this reproduces its three load-bearing
// properties exactly (see `sortSingle` → @primeuix/utils `sort`/`compare`, which
// fileSort.spec.ts pins pairwise as an oracle):
//
//   1. STRINGS compare through `Intl.Collator(undefined, { numeric: true })` — locale-aware and
//      digit-aware, so "file2" precedes "file10". Anything else compares with `<`/`>`.
//   2. EMPTY values (null / undefined / '') sort LAST in BOTH directions. DataTable's default
//      `nullSortOrder` is 1, which makes the empty-vs-present result independent of the sort
//      direction — descending does not float nameless files to the top.
//   3. Comparison reads the RAW field, never a rendered fallback. The grid tile shows
//      `displayName(name)` ("Untitled file") for a nameless file, but sorting on that string
//      would interleave those files among the U's instead of parking them at the end — and
//      would disagree with the list, whose Column binds `field="name"`.
//
// Ties keep their input order: `Array.prototype.sort` is stable (ES2019+), so equal keys stay in
// the document's manual order in both directions.

export type FileSortField = 'name' | 'create_date' | 'size'
export type FileSortDirection = 'asc' | 'desc'

// The subset of a file a sort reads. Deliberately structural and permissive: the grid sorts
// `DocumentDetail['files'][number]` while the list's rows are `FilePanelFile`, and both satisfy
// this without either importing the other.
export interface SortableFile {
  name?: string | null
  create_date?: number | null
  size?: number | null
}

type SortValue = string | number | null | undefined

// The ONE place a sortable field is mapped to the value it compares on. The control's options and
// the comparator both go through it, so a new criterion cannot be wired to a different accessor
// in the two places.
export const FILE_SORT_ACCESSORS: Record<FileSortField, (file: SortableFile) => SortValue> = {
  name: (file) => file.name,
  create_date: (file) => file.create_date,
  size: (file) => file.size,
}

// Created once: constructing an Intl.Collator per comparison is the expensive part, and a sort
// over a few hundred tiles calls this O(n log n) times.
let collator: Intl.Collator | null = null
function compareStrings(a: string, b: string): number {
  collator ??= new Intl.Collator(undefined, { numeric: true })
  return collator.compare(a, b)
}

// PrimeVue's `isEmpty` also treats an empty array/object as empty; no sortable file field is
// ever either, so this covers the reachable cases and nothing more.
// A type predicate, not just a boolean: it is what lets the comparison below narrow `a`/`b` to
// the non-empty branch instead of asserting the narrowing with a cast.
function isEmptyValue(value: SortValue): value is null | undefined | '' {
  return value === null || value === undefined || value === ''
}

/**
 * Compare two already-resolved field values. Exported so the parity test can drive it directly
 * against PrimeVue's primitive.
 */
export function compareFileValues(
  a: SortValue,
  b: SortValue,
  direction: FileSortDirection,
): number {
  const order = direction === 'desc' ? -1 : 1
  const aEmpty = isEmptyValue(a)
  const bEmpty = isEmptyValue(b)
  // `order * order === 1`, so an empty value lands after a present one whichever way the sort
  // runs. That is the DataTable behaviour, reproduced rather than approximated.
  if (aEmpty && bEmpty) return 0
  if (aEmpty) return order * order
  if (bEmpty) return order * -order
  if (typeof a === 'string' && typeof b === 'string') return order * compareStrings(a, b)
  return order * (a < b ? -1 : a > b ? 1 : 0)
}

/**
 * A sorted CLONE. The caller's array — for the grid, the optimistic manual order that the
 * reorder contract is built on — is never mutated.
 */
export function sortFiles<T extends SortableFile>(
  files: readonly T[],
  field: FileSortField,
  direction: FileSortDirection,
): T[] {
  const accessor = FILE_SORT_ACCESSORS[field]
  return [...files].sort((a, b) => compareFileValues(accessor(a), accessor(b), direction))
}
