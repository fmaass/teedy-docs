import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the axios client instance (a dependency of comment.ts). The functions
// under test — URL construction, verb selection, and form-param encoding — run
// for real against the mock and we assert on what they pass to axios.
const clientMock = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('./client', () => ({ default: clientMock }))

import { listComments, addComment, updateComment, deleteComment, gravatarUrl } from './comment'

describe('comment api module', () => {
  beforeEach(() => {
    clientMock.get.mockReset().mockResolvedValue({ data: { comments: [] } })
    clientMock.put.mockReset().mockResolvedValue({ data: {} })
    clientMock.post.mockReset().mockResolvedValue({ data: {} })
    clientMock.delete.mockReset().mockResolvedValue({ data: { status: 'ok' } })
  })

  it('listComments GETs /comment/:documentId', async () => {
    await listComments('doc-1')
    expect(clientMock.get).toHaveBeenCalledWith('/comment/doc-1')
  })

  it('listComments returns the response payload', async () => {
    const payload = { comments: [{ id: 'c1', content: 'hi', creator: 'bob', creator_gravatar: 'h', create_date: 1 }] }
    clientMock.get.mockResolvedValueOnce({ data: payload })
    const res = await listComments('doc-1')
    expect(res.data).toEqual(payload)
  })

  it('addComment PUTs /comment with id and content as form params', async () => {
    await addComment('doc-2', 'a comment')
    expect(clientMock.put).toHaveBeenCalledTimes(1)
    const [url, body] = clientMock.put.mock.calls[0]
    expect(url).toBe('/comment')
    expect(body).toBeInstanceOf(URLSearchParams)
    expect((body as URLSearchParams).get('id')).toBe('doc-2')
    expect((body as URLSearchParams).get('content')).toBe('a comment')
  })

  it('updateComment POSTs /comment/:id with the new content as a form param (#285)', async () => {
    await updateComment('c-7', 'corrected text')
    expect(clientMock.post).toHaveBeenCalledTimes(1)
    const [url, body] = clientMock.post.mock.calls[0]
    expect(url).toBe('/comment/c-7')
    expect(body).toBeInstanceOf(URLSearchParams)
    expect((body as URLSearchParams).get('content')).toBe('corrected text')
    // The comment id travels in the path, not the body — a stray body id would be ignored server-side.
    expect((body as URLSearchParams).get('id')).toBeNull()
  })

  it('updateComment returns the edited comment payload including its edit timestamp', async () => {
    const payload = {
      id: 'c-7',
      content: 'corrected text',
      creator: 'bob',
      creator_gravatar: 'h',
      create_date: 100,
      update_date: 200,
    }
    clientMock.post.mockResolvedValueOnce({ data: payload })
    const res = await updateComment('c-7', 'corrected text')
    expect(res.data).toEqual(payload)
  })

  it('deleteComment DELETEs /comment/:id', async () => {
    await deleteComment('c-9')
    expect(clientMock.delete).toHaveBeenCalledWith('/comment/c-9')
  })

  it('gravatarUrl builds an avatar URL from the hash with size and identicon fallback', () => {
    expect(gravatarUrl('abc123')).toBe('https://www.gravatar.com/avatar/abc123?s=40&d=identicon')
    expect(gravatarUrl('abc123', 64)).toBe('https://www.gravatar.com/avatar/abc123?s=64&d=identicon')
  })
})
