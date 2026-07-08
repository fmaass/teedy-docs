import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock axios (a dependency) so we can capture the response interceptor handlers
// that client.ts registers, then invoke them directly. The interceptor logic
// itself is the unit under test and runs for real.
// vi.hoisted keeps `capturedHandlers` in scope for the hoisted vi.mock factory.
const capturedHandlers = vi.hoisted(() => ({
  fulfilled: undefined as ((r: unknown) => unknown) | undefined,
  rejected: undefined as ((e: unknown) => unknown) | undefined,
}))

vi.mock('axios', () => {
  const instance = {
    interceptors: {
      response: {
        use: (fulfilled: any, rejected: any) => {
          capturedHandlers.fulfilled = fulfilled
          capturedHandlers.rejected = rejected
        },
      },
    },
  }
  return { default: { create: () => instance } }
})

// Importing the module registers the interceptor against the mocked axios.
import './client'

function makeError(status?: number) {
  return status === undefined ? { message: 'network' } : { response: { status } }
}

describe('api client response interceptor', () => {
  beforeEach(() => {
    window.location.hash = ''
  })

  it('registers a response interceptor with success and error handlers', () => {
    expect(typeof capturedHandlers.fulfilled).toBe('function')
    expect(typeof capturedHandlers.rejected).toBe('function')
  })

  it('passes successful responses through untouched', () => {
    const response = { data: { ok: true } }
    expect(capturedHandlers.fulfilled!(response)).toBe(response)
  })

  it('redirects to #/login on a 401 response', async () => {
    await expect(capturedHandlers.rejected!(makeError(401))).rejects.toBeDefined()
    expect(window.location.hash).toBe('#/login')
  })

  // CURRENT behavior — A10 WILL FLIP THIS ASSERTION.
  // Today a 403 also redirects to #/login. Phase A10 changes 403 to an
  // "access-denied, stay on page" behavior; when it does, this assertion must
  // change from '#/login' to expecting NO redirect (hash stays '').
  it('redirects to #/login on a 403 response [A10 WILL FLIP THIS]', async () => {
    await expect(capturedHandlers.rejected!(makeError(403))).rejects.toBeDefined()
    expect(window.location.hash).toBe('#/login') // A10: change to expect '' (no redirect)
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
