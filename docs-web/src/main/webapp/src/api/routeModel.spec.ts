import { describe, it, expect, beforeEach, vi } from 'vitest'

const clientMock = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('./client', () => ({ default: clientMock }))

import {
  listRouteModels,
  getRouteModel,
  createRouteModel,
  updateRouteModel,
  deleteRouteModel,
  routeModelKeys,
} from './routeModel'

describe('routeModel api module', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: { routemodels: [] } })
    clientMock.put.mockReset().mockResolvedValue({ data: { id: 'rm1' } })
    clientMock.post.mockReset().mockResolvedValue({ data: { status: 'ok' } })
    clientMock.delete.mockReset().mockResolvedValue({ data: { status: 'ok' } })
  })

  it('listRouteModels GETs /routemodel with no params by default', async () => {
    await listRouteModels()
    expect(clientMock.get).toHaveBeenCalledWith('/routemodel', { params: {} })
  })

  it('listRouteModels forwards sort_column and asc when given', async () => {
    await listRouteModels(1, true)
    expect(clientMock.get).toHaveBeenCalledWith('/routemodel', { params: { sort_column: 1, asc: true } })
  })

  it('getRouteModel GETs /routemodel/:id', async () => {
    clientMock.get.mockResolvedValueOnce({ data: { id: 'rm1', steps: '[]', acls: [] } })
    await getRouteModel('rm1')
    expect(clientMock.get).toHaveBeenCalledWith('/routemodel/rm1')
  })

  it('createRouteModel PUTs /routemodel with name and steps as form params', async () => {
    await createRouteModel('Invoice', '[{"x":1}]')
    expect(clientMock.put).toHaveBeenCalledTimes(1)
    const [url, body] = clientMock.put.mock.calls[0]
    expect(url).toBe('/routemodel')
    expect(body).toBeInstanceOf(URLSearchParams)
    expect((body as URLSearchParams).get('name')).toBe('Invoice')
    expect((body as URLSearchParams).get('steps')).toBe('[{"x":1}]')
  })

  it('updateRouteModel POSTs /routemodel/:id with name and steps', async () => {
    await updateRouteModel('rm7', 'Renamed', '[]')
    expect(clientMock.post).toHaveBeenCalledTimes(1)
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/routemodel/rm7')
    expect((body as URLSearchParams).get('name')).toBe('Renamed')
    expect((body as URLSearchParams).get('steps')).toBe('[]')
  })

  it('deleteRouteModel DELETEs /routemodel/:id', async () => {
    await deleteRouteModel('rm9')
    expect(clientMock.delete).toHaveBeenCalledWith('/routemodel/rm9')
  })

  it('routeModelKeys produce stable cache keys', () => {
    expect(routeModelKeys.all()).toEqual(['route-models'])
    expect(routeModelKeys.detail('rm1')).toEqual(['route-model', 'rm1'])
  })
})
