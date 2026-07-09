/**
 * Generation guard for out-of-order async completion.
 *
 * When one component instance is reused across rapidly-changing targets (e.g. a
 * single dialog opened for file A then file B, or a router-reused edit form), a
 * slower earlier request can resolve AFTER a newer one and clobber the newer
 * target's state. Each async run calls `next()` at entry to claim a generation,
 * then `isCurrent(gen)` after every await; a false result means a newer run has
 * superseded it, so the stale run stops without mutating state.
 *
 * This is the extracted, unit-testable core of the inline `initGen` pattern in
 * DocumentEdit.vue.
 */
export interface Generation {
  /** Claim and return a fresh generation for a run about to start. */
  next(): number
  /** True only if `gen` is still the latest claimed generation. */
  isCurrent(gen: number): boolean
}

export function createGeneration(): Generation {
  // `current` is 0 before any claim; issued generations start at 1. isCurrent must
  // report false for the unclaimed state (gen 0), so a stray 0 never counts as the
  // latest claim.
  let current = 0
  return {
    next: () => ++current,
    isCurrent: (gen: number) => gen > 0 && gen === current,
  }
}
