import { describe, it, expect } from 'vitest'
import { createGeneration } from './staleGuard'

describe('createGeneration', () => {
  it('starts with no current generation claimed', () => {
    const g = createGeneration()
    // Nothing claimed yet: the unclaimed sentinel (0) is NOT current, and no
    // issued generation exists to be current either.
    expect(g.isCurrent(0)).toBe(false)
    expect(g.isCurrent(1)).toBe(false)
  })

  it('makes the first real claim current (FileVersionsDialog first-open path)', () => {
    const g = createGeneration()
    const first = g.next()
    expect(first).toBe(1)
    expect(g.isCurrent(first)).toBe(true)
    // The unclaimed sentinel stays non-current after a real claim.
    expect(g.isCurrent(0)).toBe(false)
  })

  it('treats the most recently claimed generation as current', () => {
    const g = createGeneration()
    const gen = g.next()
    expect(gen).toBe(1)
    expect(g.isCurrent(gen)).toBe(true)
  })

  it('marks an earlier generation stale once a newer one is claimed', () => {
    const g = createGeneration()
    const first = g.next()
    const second = g.next()
    expect(first).toBe(1)
    expect(second).toBe(2)
    // The stale-response scenario: first run resolves after second was claimed.
    expect(g.isCurrent(first)).toBe(false)
    expect(g.isCurrent(second)).toBe(true)
  })

  it('models the reused-dialog race: file A load superseded by file B', () => {
    // Reproduces FileVersionsDialog: open A (genA), open B (genB) before A resolves.
    const g = createGeneration()
    const genA = g.next()
    const genB = g.next()

    // B's response resolves first and is current.
    expect(g.isCurrent(genB)).toBe(true)
    // A's slower response resolves afterwards — it must be recognised as stale so
    // it does not overwrite B's versions.
    expect(g.isCurrent(genA)).toBe(false)
  })

  it('each next() strictly increments and invalidates all prior generations', () => {
    const g = createGeneration()
    const gens = [g.next(), g.next(), g.next(), g.next()]
    expect(gens).toEqual([1, 2, 3, 4])
    // Only the last is current; every prior one is stale.
    expect(g.isCurrent(gens[3])).toBe(true)
    expect(gens.slice(0, 3).every((n) => g.isCurrent(n))).toBe(false)
  })
})
