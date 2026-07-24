/**
 * Framework-agnostic building blocks for the OCR / raster-generation processing
 * indicator: the `shouldPoll` predicate plus the `createProcessingPoller` timer
 * lifecycle every document-view surface shares.
 *
 * The backend exposes a real per-file `processing` boolean on GET /file/list and
 * on the document detail (RestUtil.fileToJsonObjectBuilder -> FileUtil.isProcessingFile).
 * A view polls that endpoint only while something is still processing; the
 * predicate decides whether another poll is warranted, and the poller owns the
 * timer and its disposal. Both stay free of Vue so the polling logic can be
 * tested without mounting a component.
 */

/** A minimal shape carrying the backend's processing flag. */
export interface HasProcessing {
  processing?: boolean
}

/**
 * True if any file reports `processing === true`. Missing/undefined flags are
 * treated as not-processing, so an empty list or a list of settled files stops
 * polling.
 */
export function shouldPoll(files: readonly HasProcessing[] | null | undefined): boolean {
  if (!files?.length) return false
  return files.some((f) => f.processing === true)
}

/** How often to re-poll /file/list while any file is still processing. */
export const POLL_INTERVAL_MS = 2500

/** The lifecycle handle a view drives; the timer and its disposal live inside. */
export interface ProcessingPoller {
  /**
   * Reconcile the poll to the current file state.
   *
   * `active=true` starts polling if it is not already running; if a tick is
   * in flight it only records the intent (the running tick re-arms on
   * completion) so two ticks can never overlap. `active=false` cancels any
   * armed timer immediately — the bounded contract is that nothing polls once
   * nothing is processing, so a settled file set must not leave a trailing poll.
   */
  ensurePolling(active: boolean): void
  /** Clear any armed timer (and any pending re-arm intent) without disposing. */
  stop(): void
  /**
   * Permanently stop. Call from onUnmounted: an in-flight tick awaiting the
   * network can resolve after the component is gone (a large file processes for
   * minutes), and this guarantees a late resolution can never re-arm the loop.
   */
  dispose(): void
}

/**
 * Shared poll lifecycle for the processing indicator, factored out so every
 * document-view surface drives one identical timer/disposal state machine
 * instead of re-implementing it. `tick` performs one poll and resolves to
 * whether another poll is warranted (typically `shouldPoll` over the freshly
 * fetched files); it receives `isDisposed` so it can bail before mutating
 * component state when the await resolved after unmount.
 *
 * Single-flight is guaranteed: at most one tick runs at a time. A poll fired
 * while an earlier tick is still awaiting would let out-of-order /file/list
 * responses regress the processing map and re-enqueue a raster twice, so an
 * activation arriving mid-tick records intent rather than arming a second
 * concurrent timer.
 */
export function createProcessingPoller(
  tick: (isDisposed: () => boolean) => Promise<boolean>,
  intervalMs: number = POLL_INTERVAL_MS,
): ProcessingPoller {
  let timer: ReturnType<typeof setTimeout> | null = null
  let disposed = false
  // True from the moment a tick starts until it settles: the single-flight guard.
  let inFlight = false
  // Set when ensurePolling(true) arrives while a tick is in flight — the running
  // tick re-arms on completion instead of a second timer being armed alongside it.
  let rearmRequested = false
  // Bumped by every stop()/dispose(). A tick captures the epoch when it starts and
  // refuses to re-arm on its own keepPolling if the epoch moved while it awaited —
  // so an explicit stop (e.g. navigating to a settled document) wins over a stale
  // tick computed against the document we just left. Only a fresh ensurePolling(true)
  // can restart polling after such a stop.
  let epoch = 0
  const isDisposed = () => disposed

  function stop() {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
    rearmRequested = false
    epoch++
  }

  function arm() {
    if (disposed) return
    if (timer !== null) {
      clearTimeout(timer)
    }
    timer = setTimeout(run, intervalMs)
  }

  async function run() {
    // The timer has fired; clear the handle so a re-arm below (or later) is possible.
    timer = null
    if (disposed) return
    const startEpoch = epoch
    inFlight = true
    rearmRequested = false
    let keepPolling = false
    try {
      keepPolling = await tick(isDisposed)
    } finally {
      inFlight = false
    }
    if (disposed) return
    // A fresh activation that arrived during the tick always wins (ensurePolling(true)
    // records it and does NOT bump the epoch). Otherwise honor the tick's own result
    // only if no stop()/dispose() intervened while it awaited.
    if (rearmRequested) arm()
    else if (epoch === startEpoch && keepPolling) arm()
  }

  function ensurePolling(active: boolean) {
    if (disposed) return
    if (!active) {
      // Nothing to poll — cancel any armed timer (and drop a pending re-arm) at once.
      stop()
      return
    }
    if (inFlight) {
      // A tick is running; never start a second one. Its completion re-arms.
      rearmRequested = true
      return
    }
    if (timer === null) arm()
  }

  function dispose() {
    disposed = true
    stop()
  }

  return { ensurePolling, stop, dispose }
}
