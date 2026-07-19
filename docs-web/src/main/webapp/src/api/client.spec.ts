import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock axios (a dependency) so we can capture the interceptor handlers that client.ts registers, then
// invoke them directly. The interceptor logic itself is the unit under test and runs for real.
// vi.hoisted keeps `capturedHandlers` in scope for the hoisted vi.mock factory.
const capturedHandlers = vi.hoisted(() => ({
  request: undefined as ((c: unknown) => unknown) | undefined,
  fulfilled: undefined as ((r: unknown) => unknown) | undefined,
  rejected: undefined as ((e: unknown) => unknown) | undefined,
  requestFn: vi.fn((c: unknown) => Promise.resolve({ retried: true, config: c })),
}))

vi.mock('axios', () => {
  const instance = {
    interceptors: {
      request: {
        use: (handler: (c: unknown) => unknown) => {
          capturedHandlers.request = handler
        },
      },
      response: {
        use: (fulfilled: (r: unknown) => unknown, rejected: (e: unknown) => unknown) => {
          capturedHandlers.fulfilled = fulfilled
          capturedHandlers.rejected = rejected
        },
      },
    },
    request: capturedHandlers.requestFn,
  }
  return { default: { create: () => instance } }
})

// Importing the module registers the interceptors against the mocked axios.
import './client'

function makeError(status?: number, data?: unknown, config?: unknown) {
  if (status === undefined) return { message: 'network' }
  return { response: { status, data }, config }
}

/** A minimal AxiosHeaders-like object exposing set() and capturing what was set. */
function makeHeaders() {
  const store: Record<string, unknown> = {}
  return {
    set: (name: string, value: unknown) => {
      store[name] = value
    },
    store,
  }
}

describe('api client interceptors', () => {
  beforeEach(() => {
    window.location.hash = ''
    // Clear cookies between tests.
    document.cookie.split(';').forEach((c) => {
      const name = c.split('=')[0].trim()
      if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT`
    })
    capturedHandlers.requestFn.mockClear()
  })

  it('registers request and response interceptors', () => {
    expect(typeof capturedHandlers.request).toBe('function')
    expect(typeof capturedHandlers.fulfilled).toBe('function')
    expect(typeof capturedHandlers.rejected).toBe('function')
  })

  it('request interceptor sets X-Csrf-Token from the csrf_token cookie', () => {
    document.cookie = 'csrf_token=abc123'
    const headers = makeHeaders()
    const config = { headers }
    const result = capturedHandlers.request!(config)
    expect(result).toBe(config)
    expect(headers.store['X-Csrf-Token']).toBe('abc123')
  })

  it('request interceptor sets no header when the cookie is absent', () => {
    const headers = makeHeaders()
    capturedHandlers.request!({ headers })
    expect(headers.store['X-Csrf-Token']).toBeUndefined()
    expect(headers.store['X-Csrf-Proxy']).toBeUndefined()
  })

  it('request interceptor forwards the __Host-csrf_proxy cookie as X-Csrf-Proxy', () => {
    // jsdom will not store a __Host--prefixed cookie over http (it requires Secure), so stub the
    // document.cookie getter to simulate a real browser exposing the (JS-readable) proxy cookie.
    const spy = vi
      .spyOn(document, 'cookie', 'get')
      .mockReturnValue('csrf_token=sess; __Host-csrf_proxy=proxytok')
    try {
      const headers = makeHeaders()
      capturedHandlers.request!({ headers })
      expect(headers.store['X-Csrf-Token']).toBe('sess')
      expect(headers.store['X-Csrf-Proxy']).toBe('proxytok')
    } finally {
      spy.mockRestore()
    }
  })

  it('passes successful responses through untouched', () => {
    const response = { data: { ok: true } }
    expect(capturedHandlers.fulfilled!(response)).toBe(response)
  })

  it('redirects to #/login on a 401 response', async () => {
    await expect(capturedHandlers.rejected!(makeError(401))).rejects.toBeDefined()
    expect(window.location.hash).toBe('#/login')
  })

  // A10 (R-013): a 403 is authenticated-but-forbidden. The interceptor must NOT
  // redirect to login — the caller shows "access denied" and stays on the page.
  it('does NOT redirect on a 403 response (access-denied, stay on page)', async () => {
    await expect(capturedHandlers.rejected!(makeError(403))).rejects.toBeDefined()
    expect(window.location.hash).toBe('')
  })

  it('retries once on a 403 CsrfError, re-reading the cookie into the header', async () => {
    document.cookie = 'csrf_token=fresh'
    const headers = makeHeaders()
    const config = { headers, _csrfRetried: undefined as boolean | undefined }
    const result = await capturedHandlers.rejected!(makeError(403, { type: 'CsrfError' }, config))
    // The request was retried exactly once with the re-read token.
    expect(capturedHandlers.requestFn).toHaveBeenCalledTimes(1)
    expect(config._csrfRetried).toBe(true)
    expect(headers.store['X-Csrf-Token']).toBe('fresh')
    expect((result as { retried: boolean }).retried).toBe(true)
    // Must not redirect to login.
    expect(window.location.hash).toBe('')
  })

  it('does NOT retry a second time on a repeated 403 CsrfError', async () => {
    const headers = makeHeaders()
    const config = { headers, _csrfRetried: true }
    await expect(
      capturedHandlers.rejected!(makeError(403, { type: 'CsrfError' }, config)),
    ).rejects.toBeDefined()
    expect(capturedHandlers.requestFn).not.toHaveBeenCalled()
  })

  it('does NOT redirect on a 500 response', async () => {
    await expect(capturedHandlers.rejected!(makeError(500))).rejects.toBeDefined()
    expect(window.location.hash).toBe('')
  })

  it('does NOT redirect on a network error with no response', async () => {
    await expect(capturedHandlers.rejected!(makeError(undefined))).rejects.toBeDefined()
    expect(window.location.hash).toBe('')
  })

  it('always rejects the promise so callers still see the error', async () => {
    const err = makeError(403)
    await expect(capturedHandlers.rejected!(err)).rejects.toBe(err)
  })
})
