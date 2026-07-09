import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the shared axios client (a dependency of group.ts). We capture the calls
// and assert the exact verb/path/params each function sends to GroupResource.
// group.ts's own param-building and URL construction is the unit under test.
const mock = vi.hoisted(() => ({
  get: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  put: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  post: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  delete: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
}))

vi.mock('./client', () => ({ default: mock }))

import {
  listGroups,
  getGroup,
  createGroup,
  updateGroup,
  deleteGroup,
  addGroupMember,
  removeGroupMember,
} from './group'

function bodyOf(call: unknown[]): Record<string, string> {
  const params = call[1] as URLSearchParams
  const out: Record<string, string> = {}
  params.forEach((value, key) => {
    out[key] = value
  })
  return out
}

describe('group api client', () => {
  beforeEach(() => {
    mock.get.mockClear()
    mock.put.mockClear()
    mock.post.mockClear()
    mock.delete.mockClear()
  })

  it('listGroups GETs /group sorted ascending by name', () => {
    listGroups()
    expect(mock.get).toHaveBeenCalledWith('/group', { params: { sort_column: 1, asc: true } })
  })

  it('getGroup GETs /group/:name', () => {
    getGroup('editors')
    expect(mock.get).toHaveBeenCalledWith('/group/editors')
  })

  it('createGroup PUTs /group with name only when no parent', () => {
    createGroup('editors')
    expect(mock.put).toHaveBeenCalledTimes(1)
    const [url, body] = [mock.put.mock.calls[0][0], bodyOf(mock.put.mock.calls[0])]
    expect(url).toBe('/group')
    expect(body).toEqual({ name: 'editors' })
  })

  it('createGroup includes parent when provided', () => {
    createGroup('editors', 'staff')
    expect(bodyOf(mock.put.mock.calls[0])).toEqual({ name: 'editors', parent: 'staff' })
  })

  it('updateGroup POSTs to /group/:currentName with the new name', () => {
    updateGroup('editors', 'writers', 'staff')
    expect(mock.post.mock.calls[0][0]).toBe('/group/editors')
    expect(bodyOf(mock.post.mock.calls[0])).toEqual({ name: 'writers', parent: 'staff' })
  })

  it('updateGroup omits parent when not provided', () => {
    updateGroup('editors', 'writers')
    expect(bodyOf(mock.post.mock.calls[0])).toEqual({ name: 'writers' })
  })

  it('deleteGroup DELETEs /group/:name', () => {
    deleteGroup('editors')
    expect(mock.delete).toHaveBeenCalledWith('/group/editors')
  })

  it('addGroupMember PUTs /group/:name with username', () => {
    addGroupMember('editors', 'alice')
    expect(mock.put.mock.calls[0][0]).toBe('/group/editors')
    expect(bodyOf(mock.put.mock.calls[0])).toEqual({ username: 'alice' })
  })

  it('removeGroupMember DELETEs /group/:name/:username', () => {
    removeGroupMember('editors', 'alice')
    expect(mock.delete).toHaveBeenCalledWith('/group/editors/alice')
  })
})
