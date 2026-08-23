import { describe, it, expect } from 'vitest'
// The comparator PrimeVue collates every string in this app with
// (`localeComparator()` → `new Intl.Collator(undefined, { numeric: true })`), imported here
// as an ORACLE only: the shipped helper does not depend on it, so the parity claim below is
// checked against the real primitive rather than against a paraphrase of it in a comment.
import { localeComparator } from '@primeuix/utils/object'
import { sortRelations, compareRelationTitles, type SortableRelation } from './relationSort'

function r(title: string): SortableRelation & { id: string; source: boolean } {
  return { id: `id-${title}`, title, source: true }
}

describe('relationSort — comparator semantics', () => {
  it('orders titles through a locale-aware NUMERIC collator, not by code point', () => {
    // The oracle for this phase: a plain code-point sort yields [A, a10, a2, b] — "a10"
    // before "a2" and a hard split between the cases. Both are exactly what a user notices
    // on a list of numbered documents.
    const relations = [r('b'), r('A'), r('a10'), r('a2')]
    expect(sortRelations(relations, 'asc').map((x) => x.title)).toEqual(['A', 'a2', 'a10', 'b'])
    expect([...relations.map((x) => x.title)].sort()).toEqual(['A', 'a10', 'a2', 'b'])
  })

  it('reverses exactly on desc', () => {
    const relations = [r('b'), r('A'), r('a10'), r('a2')]
    expect(sortRelations(relations, 'desc').map((x) => x.title)).toEqual(['b', 'a10', 'a2', 'A'])
  })

  it('collates accents by the locale, not by byte value', () => {
    // "Ärger" belongs among the A's; a code-point sort parks it after "Zeta" (U+00C4 > Z),
    // which is the German reporter's own alphabet coming out wrong.
    const relations = [r('Zeta'), r('Ärger'), r('Alpha')]
    expect(sortRelations(relations, 'asc').map((x) => x.title)).toEqual([
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
    expect(sortRelations(relations, 'asc').map((x) => x.id)).toEqual(['first', 'second', 'third'])
    expect(sortRelations(relations, 'desc').map((x) => x.id)).toEqual(['first', 'second', 'third'])
  })

  it('returns a CLONE — the caller’s array is never mutated', () => {
    const relations = [r('c'), r('a'), r('b')]
    const before = [...relations]
    const sorted = sortRelations(relations, 'asc')
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
