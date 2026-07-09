import { describe, it, expect } from 'vitest'
import { daysUntilPurge, DEFAULT_RETENTION_DAYS } from './trashRetention'

const MS_PER_DAY = 24 * 60 * 60 * 1000
// Fixed reference instant so the tests are deterministic and decoupled from the clock.
const NOW = Date.UTC(2026, 0, 15, 12, 0, 0)

describe('daysUntilPurge', () => {
  it('returns the full retention window for a just-trashed document', () => {
    expect(daysUntilPurge(NOW, 30, NOW)).toBe(30)
  })

  it('counts down as the delete date recedes into the past', () => {
    // Trashed 10 days ago, 30-day window => 20 days remain.
    expect(daysUntilPurge(NOW - 10 * MS_PER_DAY, 30, NOW)).toBe(20)
  })

  it('rounds up a partial day so an item purged later today still shows 1', () => {
    // 0.5 days remain -> ceil -> 1.
    expect(daysUntilPurge(NOW - 29.5 * MS_PER_DAY, 30, NOW)).toBe(1)
  })

  it('clamps to 0 at the exact purge instant', () => {
    expect(daysUntilPurge(NOW - 30 * MS_PER_DAY, 30, NOW)).toBe(0)
  })

  it('clamps to 0 for an item already past its purge instant', () => {
    expect(daysUntilPurge(NOW - 45 * MS_PER_DAY, 30, NOW)).toBe(0)
  })

  it('honours a non-default retention window', () => {
    expect(daysUntilPurge(NOW - 3 * MS_PER_DAY, 7, NOW)).toBe(4)
  })

  it('defaults the retention window to DEFAULT_RETENTION_DAYS when the field is absent', () => {
    // undefined models an older server that omits trash_retention_days -> count down.
    expect(daysUntilPurge(NOW, undefined, NOW)).toBe(DEFAULT_RETENTION_DAYS)
  })

  it('returns null when retention is 0 (auto-purge disabled)', () => {
    expect(daysUntilPurge(NOW - 45 * MS_PER_DAY, 0, NOW)).toBeNull()
  })

  it('returns null when retention is negative (auto-purge disabled)', () => {
    expect(daysUntilPurge(NOW - 45 * MS_PER_DAY, -1, NOW)).toBeNull()
  })

  it('still counts down for a positive retention window (disabled path is not triggered)', () => {
    expect(daysUntilPurge(NOW - 5 * MS_PER_DAY, 30, NOW)).toBe(25)
  })

  it('DEFAULT_RETENTION_DAYS mirrors the backend default of 30', () => {
    expect(DEFAULT_RETENTION_DAYS).toBe(30)
  })
})
