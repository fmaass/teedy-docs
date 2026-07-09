import { describe, it, expect, vi } from 'vitest'
import {
  runBulk,
  describeError,
  buildAddTagParams,
  buildRemoveTagParams,
  buildLanguageParams,
} from './bulkOps'
import type { DocumentListItem } from '../api/document'

function doc(overrides: Partial<DocumentListItem> = {}): DocumentListItem {
  return {
    id: 'd1',
    title: 'Doc One',
    description: '',
    create_date: 0,
    update_date: 0,
    language: 'eng',
    file_id: null,
    file_count: 0,
    tags: [],
    shared: false,
    ...overrides,
  }
}

describe('runBulk', () => {
  it('aggregates all successes when every op resolves', async () => {
    const items = [doc({ id: 'a' }), doc({ id: 'b' }), doc({ id: 'c' })]
    const op = vi.fn().mockResolvedValue(undefined)
    const res = await runBulk(items, op)
    expect(op).toHaveBeenCalledTimes(3)
    expect(res.succeeded).toEqual(['a', 'b', 'c'])
    expect(res.failed).toEqual([])
  })

  it('partitions successes and failures without aborting on the first failure', async () => {
    const items = [doc({ id: 'a' }), doc({ id: 'b', title: 'B' }), doc({ id: 'c' })]
    const op = vi.fn((item: DocumentListItem) =>
      item.id === 'b' ? Promise.reject({ response: { status: 403 } }) : Promise.resolve(),
    )
    const res = await runBulk(items, op)
    // 'c' still ran after 'b' rejected.
    expect(op).toHaveBeenCalledTimes(3)
    expect(res.succeeded).toEqual(['a', 'c'])
    expect(res.failed).toEqual([{ id: 'b', title: 'B', reason: 'forbidden' }])
  })

  it('reports every document as failed when every op rejects', async () => {
    const items = [doc({ id: 'a', title: 'A' }), doc({ id: 'b', title: 'B' })]
    const op = vi.fn().mockRejectedValue(new Error('boom'))
    const res = await runBulk(items, op)
    expect(res.succeeded).toEqual([])
    expect(res.failed).toEqual([
      { id: 'a', title: 'A', reason: 'boom' },
      { id: 'b', title: 'B', reason: 'boom' },
    ])
  })

  it('drives onProgress with a monotonic done count up to total', async () => {
    const items = [doc({ id: 'a' }), doc({ id: 'b' })]
    const progress: Array<[number, number]> = []
    await runBulk(items, () => Promise.resolve(), (done, total) => progress.push([done, total]))
    expect(progress).toEqual([[1, 2], [2, 2]])
  })

  it('handles an empty selection as a no-op with an empty result', async () => {
    const op = vi.fn()
    const res = await runBulk([], op)
    expect(op).not.toHaveBeenCalled()
    expect(res).toEqual({ succeeded: [], failed: [] })
  })
})

describe('describeError', () => {
  it('maps HTTP 403 to "forbidden"', () => {
    expect(describeError({ response: { status: 403 } })).toBe('forbidden')
  })

  it('prefers the API error message when present', () => {
    expect(describeError({ response: { status: 400, data: { message: 'Bad title' } } })).toBe('Bad title')
  })

  it('falls back to the thrown Error message', () => {
    expect(describeError(new Error('network down'))).toBe('network down')
  })

  it('falls back to a generic reason for an opaque throw', () => {
    expect(describeError({})).toBe('error')
  })
})

describe('buildAddTagParams', () => {
  it('appends the new tag id to the existing tags and keeps title + language', () => {
    const d = doc({ tags: [{ id: 't1', name: 'A', color: '#000' }], title: 'T', language: 'deu' })
    const p = buildAddTagParams(d, 't2')
    expect(p.get('title')).toBe('T')
    expect(p.get('language')).toBe('deu')
    expect(p.getAll('tags')).toEqual(['t1', 't2'])
  })

  it('is idempotent — adding a tag the document already has does not duplicate it', () => {
    const d = doc({ tags: [{ id: 't1', name: 'A', color: '#000' }] })
    const p = buildAddTagParams(d, 't1')
    expect(p.getAll('tags')).toEqual(['t1'])
  })
})

describe('buildRemoveTagParams', () => {
  it('drops the target tag and preserves the others', () => {
    const d = doc({
      tags: [
        { id: 't1', name: 'A', color: '#000' },
        { id: 't2', name: 'B', color: '#000' },
      ],
    })
    const p = buildRemoveTagParams(d, 't1')
    expect(p.getAll('tags')).toEqual(['t2'])
    // A non-empty result must NOT trigger the clear-all sentinel.
    expect(p.has('tags_reset')).toBe(false)
  })

  it('emits tags_reset=true and NO tags param when the last tag is removed', () => {
    // The backend preserves-on-omit, so an empty `tags` list would be a silent
    // no-op. Removing the final tag must send the explicit clear-all sentinel.
    const d = doc({ tags: [{ id: 't1', name: 'A', color: '#000' }] })
    const p = buildRemoveTagParams(d, 't1')
    expect(p.get('tags_reset')).toBe('true')
    expect(p.has('tags')).toBe(false)
    // title + language are still sent (required by the update contract).
    expect(p.get('title')).toBe('Doc One')
    expect(p.get('language')).toBe('eng')
  })
})

describe('buildLanguageParams', () => {
  it('sets title + new language and sends NO tags param so tags are preserved', () => {
    const d = doc({ tags: [{ id: 't1', name: 'A', color: '#000' }], title: 'Keep', language: 'eng' })
    const p = buildLanguageParams(d, 'fra')
    expect(p.get('title')).toBe('Keep')
    expect(p.get('language')).toBe('fra')
    expect(p.has('tags')).toBe(false)
  })
})
