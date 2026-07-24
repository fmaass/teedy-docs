import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { shouldPoll, createProcessingPoller, POLL_INTERVAL_MS } from './fileProcessing'

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

describe('createProcessingPoller', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('does not arm a timer or call tick when ensurePolling(false)', async () => {
    const tick = vi.fn().mockResolvedValue(true)
    const poller = createProcessingPoller(tick)
    poller.ensurePolling(false)
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3)
    expect(tick).not.toHaveBeenCalled()
  })

  it('arms once and calls tick after the interval when ensurePolling(true)', async () => {
    const tick = vi.fn().mockResolvedValue(false)
    const poller = createProcessingPoller(tick)
    poller.ensurePolling(true)
    expect(vi.getTimerCount()).toBe(1)
    expect(tick).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('does not double-arm when ensurePolling is called again while a timer is running', () => {
    const tick = vi.fn().mockResolvedValue(true)
    const poller = createProcessingPoller(tick)
    poller.ensurePolling(true)
    poller.ensurePolling(true)
    expect(vi.getTimerCount()).toBe(1)
  })

  it('re-arms while tick returns true and provably STOPS (no residual timer) when it returns false', async () => {
    const tick = vi.fn().mockResolvedValue(true)
    const poller = createProcessingPoller(tick)
    poller.ensurePolling(true)

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(1) // re-armed

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(1) // still re-arming

    // The next poll reports nothing left to do.
    tick.mockResolvedValue(false)
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(3)
    expect(vi.getTimerCount()).toBe(0) // stopped, no leaked timer

    // Time passing after the stop must not resurrect the loop.
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5)
    expect(tick).toHaveBeenCalledTimes(3)
  })

  it('stop() clears an armed timer without disposing (ensurePolling can re-arm later)', async () => {
    const tick = vi.fn().mockResolvedValue(true)
    const poller = createProcessingPoller(tick)
    poller.ensurePolling(true)
    expect(vi.getTimerCount()).toBe(1)
    poller.stop()
    expect(vi.getTimerCount()).toBe(0)
    // A later state change can start polling again.
    poller.ensurePolling(true)
    expect(vi.getTimerCount()).toBe(1)
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('dispose() during an in-flight tick prevents the re-arm even when the tick later resolves true', async () => {
    let resolveTick: (v: boolean) => void = () => {}
    const tick = vi.fn(() => new Promise<boolean>((r) => { resolveTick = r }))
    const poller = createProcessingPoller(tick)
    poller.ensurePolling(true)

    // Fire the timer; the tick is now awaiting (its promise is still pending).
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(1)

    // Unmount happens before the network resolves.
    poller.dispose()
    resolveTick(true) // would re-arm on a live poller
    await vi.advanceTimersByTimeAsync(0)

    expect(vi.getTimerCount()).toBe(0)
    // And no further ticks ever run.
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3)
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('never runs two concurrent ticks: ensurePolling(true) mid-tick records intent and re-arms on completion', async () => {
    // A single-flight regression (arming a second timer while a tick is in flight) would let
    // out-of-order polls overlap; assert at most one tick is ever active and the mid-tick
    // activation re-arms exactly one follow-up tick rather than a concurrent one.
    let concurrent = 0
    let maxConcurrent = 0
    const resolvers: Array<(v: boolean) => void> = []
    const tick = vi.fn(async () => {
      concurrent++
      maxConcurrent = Math.max(maxConcurrent, concurrent)
      const result = await new Promise<boolean>((r) => resolvers.push(r))
      concurrent--
      return result
    })
    const poller = createProcessingPoller(tick)

    poller.ensurePolling(true)
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(1) // first tick in flight, awaiting

    // A second activation arrives while the tick is still running.
    poller.ensurePolling(true)
    expect(tick).toHaveBeenCalledTimes(1) // no second concurrent tick started
    expect(vi.getTimerCount()).toBe(0) // no second timer armed alongside the in-flight tick

    // Complete the in-flight tick reporting "no work"; the recorded intent re-arms it.
    resolvers[0](false)
    await vi.advanceTimersByTimeAsync(0)
    expect(vi.getTimerCount()).toBe(1) // re-armed from the mid-tick intent, not from keepPolling

    // The re-armed timer fires exactly one more tick.
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(2)
    resolvers[1](false)
    await vi.advanceTimersByTimeAsync(0)

    expect(maxConcurrent).toBe(1) // provably single-flight the whole time
    expect(vi.getTimerCount()).toBe(0) // and it settles with no leaked timer
    poller.dispose()
  })

  it('ensurePolling(false) cancels an already-armed timer immediately (bounded: nothing polls when settled)', async () => {
    const tick = vi.fn().mockResolvedValue(true)
    const poller = createProcessingPoller(tick)
    poller.ensurePolling(true)
    expect(vi.getTimerCount()).toBe(1)
    // The file set became fully settled before the interval elapsed.
    poller.ensurePolling(false)
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3)
    expect(tick).not.toHaveBeenCalled()
  })

  it('an explicit stop mid-tick wins over a stale keepPolling=true, and a later ensurePolling(true) still restarts', async () => {
    // Navigating to a settled document while the previous document's poll is in flight must
    // NOT schedule another poll when that stale tick resolves "keep polling" — the epoch guard
    // makes the explicit stop win. But the guard must not brick a genuine restart.
    let resolveTick: (v: boolean) => void = () => {}
    const tick = vi.fn(() => new Promise<boolean>((r) => { resolveTick = r }))
    const poller = createProcessingPoller(tick)

    poller.ensurePolling(true)
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(1) // in flight, awaiting

    // Settled document arrives before the in-flight tick resolves.
    poller.ensurePolling(false)

    // The stale tick now resolves "still processing" (computed against the document we left).
    resolveTick(true)
    await vi.advanceTimersByTimeAsync(0)
    expect(vi.getTimerCount()).toBe(0) // explicit stop won — no re-arm from the stale tick
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3)
    expect(tick).toHaveBeenCalledTimes(1) // and no further poll

    // A fresh activation must still be able to resume polling.
    poller.ensurePolling(true)
    expect(vi.getTimerCount()).toBe(1)
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(tick).toHaveBeenCalledTimes(2)
    poller.dispose()
  })

  it('passes an isDisposed accessor that reports the disposed state to the tick', async () => {
    let seenBefore: boolean | null = null
    let seenAfter: boolean | null = null
    let resolveGate: () => void = () => {}
    const gate = new Promise<void>((r) => { resolveGate = r })
    const tick = vi.fn(async (isDisposed: () => boolean) => {
      seenBefore = isDisposed()
      await gate
      seenAfter = isDisposed()
      return false
    })
    const poller = createProcessingPoller(tick)
    poller.ensurePolling(true)
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(seenBefore).toBe(false)
    poller.dispose()
    resolveGate()
    await vi.advanceTimersByTimeAsync(0)
    expect(seenAfter).toBe(true)
  })
})
