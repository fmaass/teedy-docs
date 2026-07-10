import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the axios client instance (a dependency of vocabulary.ts). The functions under
// test — URL construction, verb selection, and form-param encoding — run for real
// against the mock and we assert on what they pass to axios.
const clientMock = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('./client', () => ({ default: clientMock }))

import {
  listVocabularyNames,
  getVocabulary,
  createVocabularyEntry,
  updateVocabularyEntry,
  deleteVocabularyEntry,
} from './vocabulary'

describe('vocabulary api module', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: { names: [] } })
    clientMock.put.mockReset().mockResolvedValue({ data: {} })
    clientMock.post.mockReset().mockResolvedValue({ data: {} })
    clientMock.delete.mockReset().mockResolvedValue({ data: { status: 'ok' } })
  })

  it('listVocabularyNames GETs /vocabulary', async () => {
    await listVocabularyNames()
    expect(clientMock.get).toHaveBeenCalledWith('/vocabulary')
  })

  it('listVocabularyNames returns the response payload', async () => {
    const payload = { names: ['type', 'coverage', 'rights'] }
    clientMock.get.mockResolvedValueOnce({ data: payload })
    const res = await listVocabularyNames()
    expect(res.data).toEqual(payload)
  })

  it('getVocabulary GETs /vocabulary/:name', async () => {
    clientMock.get.mockResolvedValueOnce({ data: { entries: [] } })
    await getVocabulary('type')
    expect(clientMock.get).toHaveBeenCalledWith('/vocabulary/type')
  })

  it('createVocabularyEntry PUTs /vocabulary with name, value, order as form params', async () => {
    await createVocabularyEntry('type', 'Book', 3)
    expect(clientMock.put).toHaveBeenCalledTimes(1)
    const [url, body] = clientMock.put.mock.calls[0]
    expect(url).toBe('/vocabulary')
    expect(body).toBeInstanceOf(URLSearchParams)
    expect((body as URLSearchParams).get('name')).toBe('type')
    expect((body as URLSearchParams).get('value')).toBe('Book')
    expect((body as URLSearchParams).get('order')).toBe('3')
  })

  it('updateVocabularyEntry POSTs only the provided fields', async () => {
    await updateVocabularyEntry('v-1', { value: 'Renamed' })
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/vocabulary/v-1')
    expect((body as URLSearchParams).get('value')).toBe('Renamed')
    expect((body as URLSearchParams).has('name')).toBe(false)
    expect((body as URLSearchParams).has('order')).toBe(false)
  })

  it('updateVocabularyEntry encodes a numeric order of 0', async () => {
    await updateVocabularyEntry('v-2', { order: 0 })
    const [, body] = clientMock.post.mock.calls[0]
    expect((body as URLSearchParams).get('order')).toBe('0')
  })

  it('deleteVocabularyEntry DELETEs /vocabulary/:id', async () => {
    await deleteVocabularyEntry('v-9')
    expect(clientMock.delete).toHaveBeenCalledWith('/vocabulary/v-9')
  })

  // Creating a NEW vocabulary namespace is expressed as adding its first entry under a
  // brand-new name at order 0 (the backend has no empty-namespace concept). This is the
  // exact call SettingsVocabulary.doCreateVocabulary issues.
  it('creates a new vocabulary namespace via its first entry at order 0', async () => {
    await createVocabularyEntry('license', 'Public Domain', 0)
    const [url, body] = clientMock.put.mock.calls[0]
    expect(url).toBe('/vocabulary')
    expect((body as URLSearchParams).get('name')).toBe('license')
    expect((body as URLSearchParams).get('value')).toBe('Public Domain')
    expect((body as URLSearchParams).get('order')).toBe('0')
  })
})
