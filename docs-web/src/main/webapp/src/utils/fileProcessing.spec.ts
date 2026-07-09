import { describe, it, expect } from 'vitest'
import { shouldPoll } from './fileProcessing'

describe('shouldPoll', () => {
  it('returns false for null / undefined', () => {
    expect(shouldPoll(null)).toBe(false)
    expect(shouldPoll(undefined)).toBe(false)
  })

  it('returns false for an empty list', () => {
    expect(shouldPoll([])).toBe(false)
  })

  it('returns false when no file is processing', () => {
    expect(shouldPoll([{ processing: false }, { processing: false }])).toBe(false)
  })

  it('returns true when at least one file is processing', () => {
    expect(shouldPoll([{ processing: false }, { processing: true }])).toBe(true)
  })

  it('returns true when every file is processing', () => {
    expect(shouldPoll([{ processing: true }, { processing: true }])).toBe(true)
  })

  it('treats a missing processing flag as not-processing', () => {
    expect(shouldPoll([{}, {}])).toBe(false)
  })

  it('only counts a strict true, not other truthy values', () => {
    // Defensive: the backend emits a real boolean; guard against string "true".
    expect(shouldPoll([{ processing: 'true' as unknown as boolean }])).toBe(false)
  })
})
