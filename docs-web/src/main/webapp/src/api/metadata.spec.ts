import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the axios client instance (a dependency of metadata.ts). The functions
// under test — URL construction, verb selection, and form-param encoding — run
// for real against the mock and we assert on what they pass to axios.
const clientMock = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('./client', () => ({ default: clientMock }))

import {
  listMetadata,
  createMetadata,
  updateMetadata,
  deleteMetadata,
  METADATA_TYPES,
} from './metadata'

describe('metadata api module', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: { metadata: [] } })
    clientMock.put.mockReset().mockResolvedValue({ data: {} })
    clientMock.post.mockReset().mockResolvedValue({ data: {} })
    clientMock.delete.mockReset().mockResolvedValue({ data: { status: 'ok' } })
  })

  it('exposes the six backend metadata types', () => {
    expect(METADATA_TYPES).toEqual(['STRING', 'INTEGER', 'FLOAT', 'DATE', 'BOOLEAN', 'VOCABULARY'])
  })

  it('createMetadata sends the vocabulary param for a VOCABULARY field', async () => {
    await createMetadata('Kind', 'VOCABULARY', 'type')
    const [, body] = clientMock.put.mock.calls[0]
    expect((body as URLSearchParams).get('type')).toBe('VOCABULARY')
    expect((body as URLSearchParams).get('vocabulary')).toBe('type')
  })

  it('createMetadata omits vocabulary for a non-VOCABULARY field even if one is passed', async () => {
    await createMetadata('Priority', 'STRING', 'type')
    const [, body] = clientMock.put.mock.calls[0]
    expect((body as URLSearchParams).has('vocabulary')).toBe(false)
  })

  it('listMetadata GETs /metadata', async () => {
    await listMetadata()
    expect(clientMock.get).toHaveBeenCalledWith('/metadata')
  })

  it('listMetadata returns the response payload', async () => {
    const payload = { metadata: [{ id: 'm1', name: 'Invoice no.', type: 'INTEGER' }] }
    clientMock.get.mockResolvedValueOnce({ data: payload })
    const res = await listMetadata()
    expect(res.data).toEqual(payload)
  })

  it('createMetadata PUTs /metadata with name and type as form params', async () => {
    await createMetadata('Priority', 'STRING')
    expect(clientMock.put).toHaveBeenCalledTimes(1)
    const [url, body] = clientMock.put.mock.calls[0]
    expect(url).toBe('/metadata')
    expect(body).toBeInstanceOf(URLSearchParams)
    expect((body as URLSearchParams).get('name')).toBe('Priority')
    expect((body as URLSearchParams).get('type')).toBe('STRING')
  })

  it('updateMetadata POSTs /metadata/:id with name (type is immutable)', async () => {
    await updateMetadata('m-7', 'Renamed')
    expect(clientMock.post).toHaveBeenCalledTimes(1)
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/metadata/m-7')
    expect(body).toBeInstanceOf(URLSearchParams)
    expect((body as URLSearchParams).get('name')).toBe('Renamed')
    expect((body as URLSearchParams).has('type')).toBe(false)
  })

  it('updateMetadata sends the vocabulary param when one is passed (VOCABULARY rename)', async () => {
    await updateMetadata('m-8', 'Renamed', 'coverage')
    const [, body] = clientMock.post.mock.calls[0]
    expect((body as URLSearchParams).get('name')).toBe('Renamed')
    expect((body as URLSearchParams).get('vocabulary')).toBe('coverage')
  })

  it('updateMetadata omits the vocabulary param when none is passed', async () => {
    await updateMetadata('m-9', 'Renamed')
    const [, body] = clientMock.post.mock.calls[0]
    expect((body as URLSearchParams).has('vocabulary')).toBe(false)
  })

  it('deleteMetadata DELETEs /metadata/:id', async () => {
    await deleteMetadata('m-9')
    expect(clientMock.delete).toHaveBeenCalledWith('/metadata/m-9')
  })
})
