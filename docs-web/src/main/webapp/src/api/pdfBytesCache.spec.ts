import { describe, it, expect, afterEach, vi } from 'vitest'
import { getPdfBytes } from './pdfBytesCache'

// #247 — getPdfBytes shares ONE network fetch of a PDF body between CONCURRENT callers, and only
// while the load is in flight: the entry is evicted on settle so the (multi-MB) bytes are not
// retained for the SPA's lifetime. The two invariants: concurrent callers of one src → ONE fetch;
// a call AFTER the previous settled → a fresh fetch (in-flight-only, not a lifetime cache). fetch is
// the DEPENDENCY, mocked at the global boundary. Every test uses a UNIQUE url so nothing leaks
// between tests.

const realFetch = global.fetch

afterEach(() => {
  global.fetch = realFetch
})

function okResponse(bytes: ArrayBuffer) {
  return { ok: true, status: 200, arrayBuffer: async () => bytes } as unknown as Response
}

describe('getPdfBytes — in-flight-only shared fetch (#247)', () => {
  it('fetches the body ONCE for concurrent callers of the same src', async () => {
    const body = new Uint8Array([1, 2, 3, 4]).buffer
    const fetchMock = vi.fn(async () => okResponse(body))
    global.fetch = fetchMock as unknown as typeof fetch
    const url = 'api/file/concurrent/data'

    const [a, b, c] = await Promise.all([getPdfBytes(url), getPdfBytes(url), getPdfBytes(url)])

    // The dedup invariant: three concurrent callers, ONE network fetch.
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith(url, { credentials: 'include' })
    // Every caller resolves to the same (canonical) bytes — which is exactly why the consumer
    // must copy before handing the buffer to a detaching parser.
    expect(a).toBe(b)
    expect(b).toBe(c)
    expect(new Uint8Array(a)).toEqual(new Uint8Array([1, 2, 3, 4]))
  })

  it('is in-flight-only: a call AFTER the previous load settled re-fetches (no lifetime retention)', async () => {
    // Locks the memory-leak fix: once a load settles its entry is evicted, so a completed PDF's
    // bytes are NOT held for the SPA's lifetime. A later call therefore issues a fresh fetch rather
    // than replaying a retained multi-MB buffer. (Reverting to a keep-forever cache makes this 1.)
    const url = 'api/file/settled/data'
    const fetchMock = vi.fn(async () => okResponse(new Uint8Array([5]).buffer))
    global.fetch = fetchMock as unknown as typeof fetch

    await getPdfBytes(url) // settles → evicted
    await getPdfBytes(url) // must re-fetch, not reuse a retained buffer

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('fetches separately for different srcs', async () => {
    const fetchMock = vi.fn(async (u: string) => okResponse(new Uint8Array([u.length]).buffer))
    global.fetch = fetchMock as unknown as typeof fetch

    await Promise.all([getPdfBytes('api/file/alpha/data'), getPdfBytes('api/file/bravo-2/data')])

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('does NOT cache a rejected fetch — a later call re-fetches', async () => {
    const url = 'api/file/retry/data'
    const good = new Uint8Array([9]).buffer
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce(okResponse(good))
    global.fetch = fetchMock as unknown as typeof fetch

    await expect(getPdfBytes(url)).rejects.toThrow('network down')
    // The failure must not have poisoned the cache: a retry issues a NEW fetch and succeeds.
    const bytes = await getPdfBytes(url)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(new Uint8Array(bytes)).toEqual(new Uint8Array([9]))
  })

  it('rejects (and does not cache) a non-ok HTTP response', async () => {
    const url = 'api/file/notfound/data'
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 404, arrayBuffer: async () => new ArrayBuffer(0) })
      .mockResolvedValueOnce(okResponse(new Uint8Array([7]).buffer))
    global.fetch = fetchMock as unknown as typeof fetch

    await expect(getPdfBytes(url)).rejects.toThrow(/404/)
    // A 404 is a failure too — it must not be cached, so a retry re-fetches.
    const bytes = await getPdfBytes(url)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(new Uint8Array(bytes)).toEqual(new Uint8Array([7]))
  })
})
