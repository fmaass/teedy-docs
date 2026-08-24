import { describe, it, expect } from 'vitest'
// The comparator PrimeVue collates every string in this app with
// (`localeComparator()` → `new Intl.Collator(undefined, { numeric: true })`), imported here
// as an ORACLE only: the shipped helper does not depend on it, so the parity claim below is
// checked against the real primitive rather than against a paraphrase of it in a comment.
import { localeComparator } from '@primeuix/utils/object'
import {
  sortRelations,
  compareRelationTitles,
  compareRelationCreateDates,
  type SortableRelation,
} from './relationSort'

function r(title: string): SortableRelation & { id: string; source: boolean } {
  return { id: `id-${title}`, title, source: true }
}

describe('relationSort — comparator semantics', () => {
  it('orders titles through a locale-aware NUMERIC collator, not by code point', () => {
    // The oracle for this phase: a plain code-point sort yields [A, a10, a2, b] — "a10"
    // before "a2" and a hard split between the cases. Both are exactly what a user notices
    // on a list of numbered documents.
    const relations = [r('b'), r('A'), r('a10'), r('a2')]
    expect(sortRelations(relations, 'title', 'asc').map((x) => x.title)).toEqual(['A', 'a2', 'a10', 'b'])
    expect([...relations.map((x) => x.title)].sort()).toEqual(['A', 'a10', 'a2', 'b'])
  })

  it('reverses exactly on desc', () => {
    const relations = [r('b'), r('A'), r('a10'), r('a2')]
    expect(sortRelations(relations, 'title', 'desc').map((x) => x.title)).toEqual(['b', 'a10', 'a2', 'A'])
  })

  it('collates accents by the locale, not by byte value', () => {
    // "Ärger" belongs among the A's; a code-point sort parks it after "Zeta" (U+00C4 > Z),
    // which is the German reporter's own alphabet coming out wrong.
    const relations = [r('Zeta'), r('Ärger'), r('Alpha')]
    expect(sortRelations(relations, 'title', 'asc').map((x) => x.title)).toEqual([
      'Alpha',
      'Ärger',
      'Zeta',
    ])
    expect([...relations.map((x) => x.title)].sort()).toEqual(['Alpha', 'Zeta', 'Ärger'])
  })

  it('is STABLE across equal titles in BOTH directions', () => {
    // Two documents may legitimately share a title; a tie must keep the server order
    // (RelationDao already orders by title) rather than shuffle on every re-render.
    const relations = [
      { id: 'first', title: 'same', source: true },
      { id: 'second', title: 'same', source: true },
      { id: 'third', title: 'same', source: true },
    ]
    expect(sortRelations(relations, 'title', 'asc').map((x) => x.id)).toEqual(['first', 'second', 'third'])
    expect(sortRelations(relations, 'title', 'desc').map((x) => x.id)).toEqual(['first', 'second', 'third'])
  })

  it('returns a CLONE — the caller’s array is never mutated', () => {
    const relations = [r('c'), r('a'), r('b')]
    const before = [...relations]
    const sorted = sortRelations(relations, 'title', 'asc')
    expect(relations).toEqual(before)
    expect(sorted).not.toBe(relations)
  })

  it('compareRelationTitles reports the sign, not just an ordering', () => {
    expect(compareRelationTitles('a', 'b', 'asc')).toBeLessThan(0)
    expect(compareRelationTitles('b', 'a', 'asc')).toBeGreaterThan(0)
    expect(compareRelationTitles('a', 'a', 'asc')).toBe(0)
    expect(compareRelationTitles('a', 'b', 'desc')).toBeGreaterThan(0)
    expect(compareRelationTitles('b', 'a', 'desc')).toBeLessThan(0)
  })
})

describe('relationSort — parity with the app’s shared collator', () => {
  // Relations must read the same way the rest of the app collates strings. This asserts the
  // helper agrees PAIRWISE with PrimeVue's own comparator over a corpus covering every branch
  // (case, digits, accents, ties), in both directions.
  const corpus = [
    'file2',
    'file10',
    'File1',
    'Äpfel',
    'zebra',
    'a',
    'A',
    'same',
    'same',
  ].map(r)

  // `Math.sign` is avoided deliberately: it yields -0 for a negated tie, and Object.is(-0, 0)
  // is false — an artefact of the assertion, not of the ordering under test.
  const sign = (n: number) => (n > 0 ? 1 : n < 0 ? -1 : 0)

  for (const direction of ['asc', 'desc'] as const) {
    it(`agrees pairwise with localeComparator() on ${direction}`, () => {
      const compare = localeComparator()
      const order = direction === 'desc' ? -1 : 1
      for (const a of corpus) {
        for (const b of corpus) {
          expect(sign(compareRelationTitles(a.title, b.title, direction))).toBe(
            sign(order * compare(a.title, b.title)),
          )
        }
      }
    })
  }
})

describe('relationSort — creation-date semantics (#296, part 2)', () => {
  // The reporter asked for the LINKED document's own creation date, which the relation payload now
  // carries (RelationDao joins the other document's DOC_CREATEDATE_D). Every fixture below is
  // authored so the date order DISAGREES with the title order — otherwise a date assertion could
  // pass on a title sort by coincidence.
  function d(title: string, create_date: number | null): SortableRelation & { id: string } {
    return { id: `id-${title}`, title, create_date }
  }

  const dated = [d('Alpha', 300), d('Bravo', 100), d('Charlie', 200)]

  it('orders OLDEST first on asc — by the date, not the title', () => {
    expect(sortRelations(dated, 'create_date', 'asc').map((x) => x.title)).toEqual([
      'Bravo',
      'Charlie',
      'Alpha',
    ])
  })

  it('orders NEWEST first on desc', () => {
    expect(sortRelations(dated, 'create_date', 'desc').map((x) => x.title)).toEqual([
      'Alpha',
      'Charlie',
      'Bravo',
    ])
  })

  it('is STABLE across equal dates in BOTH directions', () => {
    // Two documents created in the same millisecond (a bulk import, a duplication) must keep the
    // server order rather than shuffle on every re-render.
    const sameInstant = [
      { id: 'first', title: 'z', create_date: 42 },
      { id: 'second', title: 'y', create_date: 42 },
      { id: 'third', title: 'x', create_date: 42 },
    ]
    expect(sortRelations(sameInstant, 'create_date', 'asc').map((x) => x.id)).toEqual([
      'first',
      'second',
      'third',
    ])
    expect(sortRelations(sameInstant, 'create_date', 'desc').map((x) => x.id)).toEqual([
      'first',
      'second',
      'third',
    ])
  })

  it('parks a MISSING date last in BOTH directions, without throwing', () => {
    // The wire field is non-null (DOC_CREATEDATE_D is NOT NULL), so this is the total-comparator
    // contract rather than an observed payload: an undated entry must never sort ABOVE a dated one
    // just because the list ran descending, and must never throw.
    const withGaps = [d('Alpha', 300), d('NoDate', null), d('Bravo', 100)]
    expect(sortRelations(withGaps, 'create_date', 'asc').map((x) => x.title)).toEqual([
      'Bravo',
      'Alpha',
      'NoDate',
    ])
    expect(sortRelations(withGaps, 'create_date', 'desc').map((x) => x.title)).toEqual([
      'Alpha',
      'Bravo',
      'NoDate',
    ])

    // `undefined` — the shape an older payload would produce — is the same case as null.
    const undated = [{ id: 'u', title: 'Undefined' }, d('Bravo', 100)]
    expect(sortRelations(undated, 'create_date', 'asc').map((x) => x.title)).toEqual([
      'Bravo',
      'Undefined',
    ])
    expect(sortRelations(undated, 'create_date', 'desc').map((x) => x.title)).toEqual([
      'Bravo',
      'Undefined',
    ])
  })

  it('compareRelationCreateDates reports the sign, not just an ordering', () => {
    expect(compareRelationCreateDates(1, 2, 'asc')).toBeLessThan(0)
    expect(compareRelationCreateDates(2, 1, 'asc')).toBeGreaterThan(0)
    expect(compareRelationCreateDates(1, 1, 'asc')).toBe(0)
    expect(compareRelationCreateDates(1, 2, 'desc')).toBeGreaterThan(0)
    expect(compareRelationCreateDates(2, 1, 'desc')).toBeLessThan(0)
    // Missing sorts last whichever way the list runs: the sign does NOT flip with the direction.
    expect(compareRelationCreateDates(null, 1, 'asc')).toBeGreaterThan(0)
    expect(compareRelationCreateDates(null, 1, 'desc')).toBeGreaterThan(0)
    expect(compareRelationCreateDates(1, undefined, 'asc')).toBeLessThan(0)
    expect(compareRelationCreateDates(1, undefined, 'desc')).toBeLessThan(0)
    expect(compareRelationCreateDates(null, undefined, 'asc')).toBe(0)
  })
})
