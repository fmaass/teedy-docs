import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'

// Mock the shared axios client (a dependency of share.ts). The unit under test is
// share.ts's own verb/path selection, form-param encoding, and URL building —
// exercised for real against the mock.
const mock = vi.hoisted(() => ({
  get: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  put: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  post: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
  delete: vi.fn((..._args: unknown[]) => Promise.resolve({ data: {} })),
}))

vi.mock('./client', () => ({ default: mock }))

import { createShare, deleteShare, buildShareUrl } from './share'

function bodyOf(call: unknown[]): Record<string, string> {
  const params = call[1] as URLSearchParams
  const out: Record<string, string> = {}
  params.forEach((value, key) => {
    out[key] = value
  })
  return out
}

describe('share api client', () => {
  beforeEach(() => {
    mock.get.mockClear()
    mock.put.mockClear()
    mock.post.mockClear()
    mock.delete.mockClear()
  })

  it('createShare PUTs /share with the document id', () => {
    createShare('doc-1')
    expect(mock.put).toHaveBeenCalledTimes(1)
    expect(mock.put.mock.calls[0][0]).toBe('/share')
    expect(bodyOf(mock.put.mock.calls[0])).toEqual({ id: 'doc-1' })
  })

  it('createShare includes a trimmed name when provided', () => {
    createShare('doc-1', '  quarterly report  ')
    expect(bodyOf(mock.put.mock.calls[0])).toEqual({ id: 'doc-1', name: 'quarterly report' })
  })

  it('createShare omits an empty/whitespace name', () => {
    createShare('doc-1', '   ')
    expect(bodyOf(mock.put.mock.calls[0])).toEqual({ id: 'doc-1' })
  })

  it('deleteShare DELETEs /share/:id', () => {
    deleteShare('share-42')
    expect(mock.delete).toHaveBeenCalledWith('/share/share-42')
  })
})

describe('buildShareUrl', () => {
  const original = window.location.href

  afterEach(() => {
    Object.defineProperty(window, 'location', {
      value: new URL(original),
      writable: true,
    })
  })

  function setHref(href: string) {
    Object.defineProperty(window, 'location', {
      value: { href },
      writable: true,
    })
  }

  it('builds a hash-routed public share link from the current origin', () => {
    setHref('https://teedy.example.com/docs-web/')
    expect(buildShareUrl('doc-1', 'share-42')).toBe(
      'https://teedy.example.com/docs-web/#/share/doc-1/share-42',
    )
  })

  it('strips any existing hash fragment before appending the share route', () => {
    setHref('https://teedy.example.com/#/document/view/doc-1')
    expect(buildShareUrl('doc-1', 'share-42')).toBe(
      'https://teedy.example.com/#/share/doc-1/share-42',
    )
  })
})
