import { describe, it, expect } from 'vitest'
import {
  assignableTags,
  filterTagsByName,
  topUsedTags,
  QUICK_ADD_TAG_LIMIT,
} from './tagQuickMenu'
import { type Tag } from '../api/tag'

function tag(id: string, name: string): Tag {
  return { id, name, color: '#000', parent: null }
}

const invoice = tag('t1', 'Invoice')
const receipt = tag('t2', 'Receipt')
const bank = tag('t3', 'Bank')
const archive = tag('t4', 'Archive')
const contract = tag('t5', 'Contract')
const draft = tag('t6', 'Draft')
const all = [invoice, receipt, bank, archive, contract, draft]

describe('assignableTags', () => {
  it('excludes tags already assigned to the document', () => {
    const result = assignableTags(all, new Set(['t1', 't3']))
    expect(result.map((t) => t.id)).toEqual(['t2', 't4', 't5', 't6'])
  })

  it('returns all tags when none are assigned', () => {
    expect(assignableTags(all, new Set())).toHaveLength(6)
  })
})

describe('filterTagsByName', () => {
  it('matches a case-insensitive substring of the tag name', () => {
    const result = filterTagsByName(all, 'ra')
    // "Contract" and "Draft" both contain "ra"; nothing else does.
    expect(result.map((t) => t.name).sort()).toEqual(['Contract', 'Draft'])
  })

  it('returns all tags unchanged for a blank query', () => {
    expect(filterTagsByName(all, '   ')).toEqual(all)
  })

  it('returns an empty list when nothing matches', () => {
    expect(filterTagsByName(all, 'zzz')).toEqual([])
  })
})

describe('topUsedTags', () => {
  it('ranks assignable tags by usage count, descending', () => {
    const counts = { t1: 10, t2: 3, t3: 25, t4: 1, t5: 7, t6: 0 }
    const result = topUsedTags(all, counts)
    // t3(25) > t1(10) > t5(7) > t2(3) > t4(1) — top 5, t6(0) drops off.
    expect(result.map((t) => t.id)).toEqual(['t3', 't1', 't5', 't2', 't4'])
  })

  it('defaults to five chips', () => {
    const counts = { t1: 6, t2: 5, t3: 4, t4: 3, t5: 2, t6: 1 }
    expect(topUsedTags(all, counts)).toHaveLength(QUICK_ADD_TAG_LIMIT)
    expect(topUsedTags(all, counts)).toHaveLength(5)
  })

  it('honours a custom limit', () => {
    const counts = { t1: 6, t2: 5, t3: 4, t4: 3, t5: 2, t6: 1 }
    expect(topUsedTags(all, counts, 2).map((t) => t.id)).toEqual(['t1', 't2'])
  })

  it('breaks ties by name for a stable order', () => {
    // All equal count → pure alphabetical by name.
    const counts = { t1: 5, t2: 5, t3: 5, t4: 5, t5: 5, t6: 5 }
    expect(topUsedTags(all, counts, 3).map((t) => t.name)).toEqual([
      'Archive',
      'Bank',
      'Contract',
    ])
  })

  it('falls back to the first N by name when no usage data exists', () => {
    // Empty counts: every tag is 0 → alphabetical, never empty.
    const result = topUsedTags(all, {}, 3)
    expect(result.map((t) => t.name)).toEqual(['Archive', 'Bank', 'Contract'])
    expect(result).toHaveLength(3)
  })
})
