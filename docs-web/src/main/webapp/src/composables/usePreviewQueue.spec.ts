import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { usePreviewQueue } from './usePreviewQueue'

function defer() {
  let resolve!: (v: Response) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<Response>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function blobResponse(text = 'data') {
  return new Response(text, { status: 200 })
}

describe('usePreviewQueue', () => {
  const fetches: Array<{ url: string; signal: AbortSignal; deferred: ReturnType<typeof defer> }> = []

  beforeEach(() => {
    fetches.length = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, opts?: RequestInit) => {
        const d = defer()
        const signal = opts!.signal!
        signal.addEventListener('abort', () => {
          d.reject(new DOMException('Aborted', 'AbortError'))
        })
        fetches.push({ url: _url, signal, deferred: d })
        return d.promise
      }),
    )
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('limits concurrency to 4', () => {
    const q = usePreviewQueue()
    for (let i = 0; i < 8; i++) {
      q.enqueue(`file-${i}`, 'web', 1)
    }
    expect(fetches).toHaveLength(4)
  })

  it('drains the next item when a slot frees', async () => {
    const q = usePreviewQueue()
    const promises: Promise<Blob | null>[] = []
    for (let i = 0; i < 6; i++) {
      promises.push(q.enqueue(`file-${i}`, 'web', 1))
    }
    expect(fetches).toHaveLength(4)

    fetches[0].deferred.resolve(blobResponse())
    await promises[0]

    expect(fetches).toHaveLength(5)
  })

  it('resolves to null on HTTP error', async () => {
    const q = usePreviewQueue()
    const p = q.enqueue('bad', 'web', 1)
    fetches[0].deferred.resolve(new Response('', { status: 500 }))
    const result = await p
    expect(result).toBeNull()
  })

  it('resolves to null on network error', async () => {
    const q = usePreviewQueue()
    const p = q.enqueue('err', 'web', 1)
    fetches[0].deferred.reject(new TypeError('Network error'))
    const result = await p
    expect(result).toBeNull()
  })

  it('cancel() aborts all pending and active, resolving to null', async () => {
    const q = usePreviewQueue()
    const promises: Promise<Blob | null>[] = []
    for (let i = 0; i < 6; i++) {
      promises.push(q.enqueue(`file-${i}`, 'web', 1))
    }
    q.cancel()

    const results = await Promise.all(promises)
    expect(results.every((r) => r === null)).toBe(true)
  })

  it('higher priority items (lower number) are dispatched first', async () => {
    const q = usePreviewQueue()

    for (let i = 0; i < 4; i++) {
      q.enqueue(`blocker-${i}`, 'web', 1)
    }

    q.enqueue('low', 'web', 10)
    q.enqueue('high', 'web', 0)

    fetches[0].deferred.resolve(blobResponse())
    await new Promise((r) => setTimeout(r, 0))

    expect(fetches[4].url).toContain('high')
  })

  it('reprioritize moves a queued item ahead', async () => {
    const q = usePreviewQueue()

    for (let i = 0; i < 4; i++) {
      q.enqueue(`blocker-${i}`, 'web', 1)
    }

    q.enqueue('later', 'web', 10)
    q.enqueue('sooner', 'web', 5)

    q.reprioritize('later', 0)

    fetches[0].deferred.resolve(blobResponse())
    await new Promise((r) => setTimeout(r, 0))

    expect(fetches[4].url).toContain('later')
  })

  it('constructs the correct URL with rotation cache-bust', () => {
    const q = usePreviewQueue()
    q.enqueue('abc', 'web', 1, undefined, 90)
    expect(fetches[0].url).toBe('api/file/abc/data?size=web&v=90')
  })

  it('constructs the correct URL without size', () => {
    const q = usePreviewQueue()
    q.enqueue('abc', undefined, 1)
    expect(fetches[0].url).toBe('api/file/abc/data')
  })
})
