// A module-level IN-FLIGHT-only promise cache for a PDF's raw body, so the SAME file is fetched
// over the network EXACTLY ONCE when two viewers open it at the same instant — without retaining
// the (multi-MB) bytes for the SPA's lifetime once the load has settled.
//
// The document view can mount two PdfViewer instances for one file simultaneously — the
// file-grid tile (DocumentViewContent) and the preview dialog (FilePreviewDialog) — with a
// byte-identical `src`. Left to pdf.js each would run its own getDocument({url}) GET of the
// same large body, and the two responses race the browser's disk cache: one aborts with
// ERR_CACHE_WRITE_FAILURE (observed on a 6.7 MB file, #247). Sharing ONE in-flight fetch removes
// the race — the body is fetched once here, and each viewer parses its own copy of it.
//
// The cache holds ONLY the in-flight promise, evicted the moment it settles: it is a dedup window
// for concurrent openers, NOT a lifetime store. Retaining resolved buffers would leak every large
// PDF the user ever previews. The map MUST be module-level (not per-component): the two viewers
// live in separate component trees, so a component-scoped cache — the idiom AboutDialog uses for
// its diagnostics fetch — could not dedup across them.
const inFlight = new Map<string, Promise<ArrayBuffer>>()

/**
 * Resolve the raw bytes of the PDF at `src`, sharing a single in-flight fetch across concurrent
 * callers. Concurrent callers for the same `src` receive the same promise (one network request)
 * and resolve to the same ArrayBuffer — callers that hand the bytes to pdf.js (which detaches the
 * buffer onto its worker) must therefore copy before parsing, never share the returned buffer.
 *
 * The entry is evicted as soon as the fetch settles (success OR failure), so a later call — after
 * the previous one finished — re-fetches rather than replaying stale or failed bytes.
 */
export function getPdfBytes(src: string): Promise<ArrayBuffer> {
  const cached = inFlight.get(src)
  if (cached) return cached

  const promise = fetch(src, { credentials: 'include' }).then((response) => {
    if (!response.ok) throw new Error(`Failed to fetch PDF (${response.status})`)
    return response.arrayBuffer()
  })
  inFlight.set(src, promise)

  // Evict on settle so the map is in-flight-only. Identity-guarded so a newer fetch for the same
  // src (started after this one settled) is not clobbered. Every concurrent caller already holds
  // `promise`, so eviction never retracts bytes already handed out. The `.catch` swallows the
  // failure this cleanup chain re-raises — the awaiting viewers own the real rejection via the
  // returned promise, so eviction must not surface as an unhandled rejection of its own.
  const evict = () => {
    if (inFlight.get(src) === promise) inFlight.delete(src)
  }
  void promise.finally(evict).catch(() => {})

  return promise
}
