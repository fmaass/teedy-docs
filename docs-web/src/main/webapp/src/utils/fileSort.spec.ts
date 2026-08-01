import { describe, it, expect } from 'vitest'
// The ACTUAL primitive PrimeVue's DataTable sorts the list view with
// (primevue/datatable `sortSingle` → `sort(value1, value2, order, localeComparator())`).
// Imported here as an ORACLE only — the shipped helper does not depend on it — so the
// parity claim below is checked against the real thing rather than against a paraphrase
// of it in a comment.
import { sort as primeSort, localeComparator } from '@primeuix/utils/object'
import { sortFiles, compareFileValues, FILE_SORT_ACCESSORS, type SortableFile } from './fileSort'

function f(name: string | null, size: number, create_date: number): SortableFile & { id: string } {
  return { id: `${name}-${size}-${create_date}`, name, size, create_date }
}

describe('fileSort — comparator semantics', () => {
  it('orders strings with a NUMERIC collator, not by code point', () => {
    // "file10" after "file2" — a plain localeCompare/code-point sort puts it before, which is
    // exactly the difference a user notices on scanned pages.
    const files = [f('file10.txt', 1, 1), f('file2.txt', 2, 2), f('file1.txt', 3, 3)]
    expect(sortFiles(files, 'name', 'asc').map((x) => x.name)).toEqual([
      'file1.txt',
      'file2.txt',
      'file10.txt',
    ])
  })

  it('reverses on desc', () => {
    const files = [f('b', 1, 1), f('a', 2, 2), f('c', 3, 3)]
    expect(sortFiles(files, 'name', 'desc').map((x) => x.name)).toEqual(['c', 'b', 'a'])
  })

  it('sorts size and create_date numerically in both directions', () => {
    const files = [f('a', 300, 30), f('b', 100, 10), f('c', 200, 20)]
    expect(sortFiles(files, 'size', 'asc').map((x) => x.size)).toEqual([100, 200, 300])
    expect(sortFiles(files, 'size', 'desc').map((x) => x.size)).toEqual([300, 200, 100])
    expect(sortFiles(files, 'create_date', 'asc').map((x) => x.create_date)).toEqual([10, 20, 30])
    expect(sortFiles(files, 'create_date', 'desc').map((x) => x.create_date)).toEqual([30, 20, 10])
  })

  it('sorts a null name LAST in BOTH directions (raw field, never the display fallback)', () => {
    // The tile renders `displayName(name)` → "Untitled file", but the sort reads the RAW field,
    // exactly as the list's Column field="name" does. Sorting on the fallback string would
    // interleave nameless files among the U's instead of parking them at the end.
    const files = [f('b.txt', 1, 1), f(null, 2, 2), f('a.txt', 3, 3)]
    expect(sortFiles(files, 'name', 'asc').map((x) => x.name)).toEqual(['a.txt', 'b.txt', null])
    expect(sortFiles(files, 'name', 'desc').map((x) => x.name)).toEqual(['b.txt', 'a.txt', null])
  })

  it('is STABLE across equal keys — ties keep their manual order', () => {
    const files = [f('a', 5, 1), f('b', 5, 2), f('c', 5, 3)]
    expect(sortFiles(files, 'size', 'asc').map((x) => x.name)).toEqual(['a', 'b', 'c'])
    // Stability is direction-independent: desc must not silently reverse the tie group either.
    expect(sortFiles(files, 'size', 'desc').map((x) => x.name)).toEqual(['a', 'b', 'c'])
  })

  it('returns a CLONE — the caller’s manual order is never mutated', () => {
    const files = [f('c', 1, 1), f('a', 2, 2), f('b', 3, 3)]
    const before = [...files]
    const sorted = sortFiles(files, 'name', 'asc')
    expect(files).toEqual(before)
    expect(sorted).not.toBe(files)
  })
})

describe('fileSort — parity with the list view’s PrimeVue comparator', () => {
  // The two views MUST produce the same order. The list's order is produced by PrimeVue's
  // `sort` primitive over the raw column field; this asserts the helper agrees with it pairwise
  // over a corpus that covers every branch (collation, numerics, nulls, ties).
  const corpus: SortableFile[] = [
    f('file2.txt', 100, 20),
    f('file10.txt', 100, 10),
    f('File1.txt', 0, 30),
    f(null, 50, 40),
    f('', 50, 40),
    f('äpfel.txt', 25, 50),
    f('zebra.txt', 25, 5),
  ]

  const fields = ['name', 'create_date', 'size'] as const

  for (const field of fields) {
    for (const [direction, order] of [
      ['asc', 1],
      ['desc', -1],
    ] as const) {
      it(`matches PrimeVue pairwise for ${field} ${direction}`, () => {
        const comparer = localeComparator()
        const accessor = FILE_SORT_ACCESSORS[field]
        for (const a of corpus) {
          for (const b of corpus) {
            const mine = compareFileValues(accessor(a), accessor(b), direction)
            const theirs = primeSort(accessor(a), accessor(b), order, comparer as never, 1)
            // `|| 0` collapses -0 to 0. A descending comparison multiplies by -1, so an
            // "equal" verdict can surface as -0 on either side depending on which branch
            // produced it; Object.is separates them while Array.sort does not. What is being
            // asserted is the ORDERING verdict, so the sign is normalised before comparing.
            expect(
              Math.sign(mine) || 0,
              `${field}/${direction}: ${JSON.stringify(accessor(a))} vs ${JSON.stringify(accessor(b))}`,
            ).toBe(Math.sign(theirs) || 0)
          }
        }
      })
    }
  }
})
