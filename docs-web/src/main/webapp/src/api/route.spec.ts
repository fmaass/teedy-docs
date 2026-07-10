import { describe, it, expect, beforeEach, vi } from 'vitest'

const clientMock = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('./client', () => ({ default: clientMock }))

import { getRoutes, startRoute, validateRoute, cancelRoute, routeKeys } from './route'

describe('route api module', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: { routes: [] } })
    clientMock.post.mockReset().mockResolvedValue({ data: { readable: true } })
    clientMock.delete.mockReset().mockResolvedValue({ data: { status: 'ok' } })
  })

  it('getRoutes GETs /route with the documentId query param', async () => {
    await getRoutes('doc1')
    expect(clientMock.get).toHaveBeenCalledWith('/route', { params: { documentId: 'doc1' } })
  })

  it('startRoute POSTs /route/start with documentId and routeModelId form params', async () => {
    await startRoute('doc1', 'model1')
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/route/start')
    expect(body).toBeInstanceOf(URLSearchParams)
    expect((body as URLSearchParams).get('documentId')).toBe('doc1')
    expect((body as URLSearchParams).get('routeModelId')).toBe('model1')
  })

  it('validateRoute ALWAYS submits routeStepId (B3 step-id guard) plus transition', async () => {
    await validateRoute('doc1', 'VALIDATED', 'step7')
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/route/validate')
    const p = body as URLSearchParams
    expect(p.get('documentId')).toBe('doc1')
    expect(p.get('transition')).toBe('VALIDATED')
    expect(p.get('routeStepId')).toBe('step7')
    // No comment supplied -> the param is omitted, not sent empty
    expect(p.has('comment')).toBe(false)
  })

  it('validateRoute includes comment only when provided', async () => {
    await validateRoute('doc1', 'REJECTED', 'step9', 'not good enough')
    const p = clientMock.post.mock.calls[0][1] as URLSearchParams
    expect(p.get('comment')).toBe('not good enough')
    expect(p.get('routeStepId')).toBe('step9')
  })

  it('cancelRoute DELETEs /route with the documentId query param', async () => {
    await cancelRoute('doc1')
    expect(clientMock.delete).toHaveBeenCalledWith('/route', { params: { documentId: 'doc1' } })
  })

  it('routeKeys produce a stable per-document cache key', () => {
    expect(routeKeys.all('doc1')).toEqual(['routes', 'doc1'])
  })
})
