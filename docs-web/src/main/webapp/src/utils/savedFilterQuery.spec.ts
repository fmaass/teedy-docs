import { describe, it, expect } from 'vitest'
import { FILTER_KEYS, serialize, parse, equals } from './savedFilterQuery'

// #297: the saved-filter query oracle. `serialize` captures a route query VERBATIM,
// `parse` turns a stored string back into a route query, and `equals` answers the only
// question the highlight asks: "do these two query strings select the same documents?"
// The comparison must be ORDER-INSENSITIVE — the stored string carries whatever key
// order the URL had when it was saved, while the current URL is rebuilt by
// tagFilter.buildFilterQuery in ITS insertion order, so naive string equality misses
// matches that are in fact the same filter.

describe('savedFilterQuery — FILTER_KEYS', () => {
  it('covers the five route filter dimensions and EXCLUDES favorites', () => {
    // The reporter's decision (#297, 2026-08-23): favourites are an informal
    // collection (#209), not part of a saved filter's identity. Excluded BY
    // CONSTRUCTION — it is not a member of the key set every function iterates.
    expect([...FILTER_KEYS]).toEqual(['tags', 'exclude', 'mode', 'search', 'workflow'])
    expect([...FILTER_KEYS]).not.toContain('favorites')
  })
})

describe('savedFilterQuery — serialize', () => {
  it('serializes all five dimensions VERBATIM in FILTER_KEYS order, dropping non-filter keys', () => {
    expect(
      serialize({
        search: 'acme',
        foo: 'bar',
        tags: 't1,t2',
        workflow: 'me',
        exclude: 't3',
        mode: 'or',
      }),
    ).toBe('tags=t1%2Ct2&exclude=t3&mode=or&search=acme&workflow=me')
  })

  it('preserves empty values and repeated keys verbatim (no normalization)', () => {
    expect(serialize({ mode: '', search: ['a', 'b'] })).toBe('mode=&search=a&search=b')
  })

  it('never captures favorites, even when the route carries it', () => {
    expect(serialize({ tags: 'a', favorites: 'me' })).toBe('tags=a')
  })
})

describe('savedFilterQuery — parse', () => {
  it('round-trips a stored query string into a route query', () => {
    expect(parse('tags=t1%2Ct2&search=acme&workflow=me')).toEqual({
      tags: 't1,t2',
      search: 'acme',
      workflow: 'me',
    })
  })

  it('returns an empty query for an empty string', () => {
    expect(parse('')).toEqual({})
  })
})

describe('savedFilterQuery — equals', () => {
  it('is ORDER-INSENSITIVE across keys', () => {
    // The same filter, saved from a URL whose keys were in the other order.
    expect(equals('search=x&tags=a', 'tags=a&search=x')).toBe(true)
  })

  it('ignores favorites on either side', () => {
    expect(equals('tags=a&favorites=me', 'tags=a')).toBe(true)
    expect(equals('tags=a', 'favorites=me&tags=a')).toBe(true)
  })

  it('ignores keys that are not filter dimensions', () => {
    expect(equals('tags=a&foo=bar', 'tags=a')).toBe(true)
  })

  it('is FALSE when a dimension VALUE differs', () => {
    expect(equals('tags=a&search=x', 'tags=a&search=y')).toBe(false)
  })

  it('is FALSE when one side carries an extra dimension', () => {
    expect(equals('tags=a&mode=or', 'tags=a')).toBe(false)
  })

  it('treats an empty value as no value (mode= is the default mode)', () => {
    // tagFilter.buildFilterQuery emits `mode` only for 'or' and drops empty values, so a
    // filter saved from a URL carrying `mode=` still IS the filter the canonical URL
    // describes. Only the VERBATIM capture keeps empties; the comparison is semantic.
    expect(equals('tags=a&mode=', 'tags=a')).toBe(true)
    expect(equals('', 'mode=&search=')).toBe(true)
  })

  it('compares repeated keys as a multiset, not by position', () => {
    expect(equals('tags=a&tags=b', 'tags=b&tags=a')).toBe(true)
    expect(equals('tags=a&tags=b', 'tags=a')).toBe(false)
  })

  it('compares the comma-joined ID SETS as sets, not by position', () => {
    // `tags`/`exclude` are ONE comma-joined value built from an insertion-ordered Set
    // (tagFilter.buildFilterQuery), and toggleTag's 3-state cycle moves a re-added tag to
    // the end — so the same selection legitimately serialises as `tags=a,b` today and
    // `tags=b,a` after ordinary toggling. Same documents, so the same filter.
    expect(equals('tags=a,b', 'tags=b,a')).toBe(true)
    expect(equals('exclude=a,b', 'exclude=b,a')).toBe(true)
    expect(equals('tags=a,b&exclude=c,d', 'tags=b,a&exclude=d,c')).toBe(true)
    // Still a different SET, not just a different order.
    expect(equals('tags=a,b', 'tags=a,c')).toBe(false)
    expect(equals('tags=a,b', 'tags=a')).toBe(false)
  })

  it('does NOT reorder a comma inside single-valued keys (a comma in `search` is user text)', () => {
    // Over-canonicalising here would call two different full-text searches the same
    // filter: "b,a" and "a,b" are different query strings to the backend.
    expect(equals('search=b,a', 'search=a,b')).toBe(false)
    expect(equals('mode=or,and', 'mode=and,or')).toBe(false)
    expect(equals('workflow=b,a', 'workflow=a,b')).toBe(false)
  })

  it('does not confuse a value containing a separator with two dimensions', () => {
    // A tag value that itself contains "&exclude=" must not compare equal to the two
    // real dimensions it looks like once re-joined.
    expect(equals('tags=a%26exclude%3Db', 'tags=a&exclude=b')).toBe(false)
  })
})
