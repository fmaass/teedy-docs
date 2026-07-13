import { describe, it, expect } from 'vitest'
import {
  clampOffset,
  lastPageOffset,
  clampPageSize,
  DEFAULT_PAGE_SIZE,
  PAGE_SIZE_OPTIONS,
} from './pagination'

const PAGE_SIZE = 20

describe('clampPageSize (#52)', () => {
  it('passes through each allowed option unchanged', () => {
    for (const opt of PAGE_SIZE_OPTIONS) {
      expect(clampPageSize(opt)).toBe(opt)
    }
  })

  it('clamps a below-range value up to the smallest option', () => {
    expect(clampPageSize(1)).toBe(PAGE_SIZE_OPTIONS[0])
    expect(clampPageSize(0)).toBe(PAGE_SIZE_OPTIONS[0])
    expect(clampPageSize(-5)).toBe(PAGE_SIZE_OPTIONS[0])
  })

  it('clamps an above-range value down to the largest option', () => {
    expect(clampPageSize(1000)).toBe(PAGE_SIZE_OPTIONS[PAGE_SIZE_OPTIONS.length - 1])
  })

  it('resolves an in-range-but-not-allowed value to the default', () => {
    expect(clampPageSize(37)).toBe(DEFAULT_PAGE_SIZE)
  })

  it('resolves a non-finite value to the default', () => {
    expect(clampPageSize(NaN)).toBe(DEFAULT_PAGE_SIZE)
    expect(clampPageSize(Number('nope'))).toBe(DEFAULT_PAGE_SIZE)
  })
})

describe('lastPageOffset', () => {
  it('returns 0 for an empty set', () => {
    expect(lastPageOffset(0, PAGE_SIZE)).toBe(0)
  })

  it('returns 0 when everything fits on the first page', () => {
    expect(lastPageOffset(1, PAGE_SIZE)).toBe(0)
    expect(lastPageOffset(20, PAGE_SIZE)).toBe(0)
  })

  it('returns the second-page offset once a 21st item exists', () => {
    expect(lastPageOffset(21, PAGE_SIZE)).toBe(20)
  })

  it('lands on the last full page for an exact multiple', () => {
    // 40 items -> pages [0..19] and [20..39]; last offset is 20.
    expect(lastPageOffset(40, PAGE_SIZE)).toBe(20)
  })

  it('lands on the partial last page', () => {
    // 41 items -> third page holds only item 41; offset 40.
    expect(lastPageOffset(41, PAGE_SIZE)).toBe(40)
  })

  it('guards against a non-positive page size', () => {
    expect(lastPageOffset(41, 0)).toBe(0)
  })
})

describe('clampOffset', () => {
  it('mid-page delete: rows still present, offset unchanged (no clamp)', () => {
    // Deleted one of many on page 2; 19 rows still render for offset 20.
    expect(clampOffset(20, 19, 39, PAGE_SIZE)).toBe(20)
  })

  it('last item of last page: clamps down to the previous page', () => {
    // Was on page 2 (offset 20) showing the sole 21st item; after delete
    // totalCount is 20 and the refetch for offset 20 returns 0 rows.
    expect(clampOffset(20, 0, 20, PAGE_SIZE)).toBe(0)
  })

  it('last item of a deep page: clamps to the new last page, not to zero', () => {
    // Was on page 3 (offset 40) with the sole 41st item; after delete
    // totalCount 40, offset 40 empty -> clamp to offset 20.
    expect(clampOffset(40, 0, 40, PAGE_SIZE)).toBe(20)
  })

  it('empty trash / everything gone: offset already 0, no clamp', () => {
    expect(clampOffset(0, 0, 0, PAGE_SIZE)).toBe(0)
  })

  it('offset already 0 with items present: never clamps', () => {
    expect(clampOffset(0, 20, 100, PAGE_SIZE)).toBe(0)
  })

  it('deleted last item overall while on page 2: total 0 → offset unchanged (empty-state owns it, no clamp)', () => {
    // When totalCount is 0 there is nothing to page to; clampOffset leaves the
    // offset as-is and the view renders its empty state rather than re-querying a
    // clamped page. So the offset stays 20 (not clamped to 0).
    expect(clampOffset(20, 0, 0, PAGE_SIZE)).toBe(20)
  })
})
