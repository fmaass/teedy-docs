import { describe, it, expect } from 'vitest'
import { matchTagsByName } from './tagSynonyms'
import { type Tag } from '../api/tag'

function tag(id: string, name: string, synonyms?: string[]): Tag {
  return { id, name, color: '#000', parent: null, synonyms }
}

const invoice = tag('t1', 'Invoice', ['Rechnung', 'Quittung'])
const receipt = tag('t2', 'Receipt')
const bank = tag('t3', 'Bank')
const archive = tag('t4', 'Archive')
const contract = tag('t5', 'Contract')
const draft = tag('t6', 'Draft')
const all = [invoice, receipt, bank, archive, contract, draft]

/**
 * The one matcher both tag inputs search with (#280) — the document editor's TagPicker and the
 * document list's TagQuickMenu. It answers WHICH tag matched and, when the hit was on a synonym,
 * WHICH synonym, because that is what lets the option say "Invoice (via Rechnung)" rather than
 * silently offering a tag whose name the user did not type.
 */
describe('matchTagsByName', () => {
  // --- carried over from filterTagsByName, which this replaces ---

  it('matches a case-insensitive substring of the tag name', () => {
    const result = matchTagsByName(all, 'ra')
    // "Contract" and "Draft" both contain "ra"; nothing else does by NAME.
    expect(result.map((m) => m.tag.name).sort()).toEqual(['Contract', 'Draft'])
  })

  it('returns every tag unchanged for a blank query', () => {
    expect(matchTagsByName(all, '   ').map((m) => m.tag)).toEqual(all)
  })

  it('returns nothing when the query matches no tag', () => {
    expect(matchTagsByName(all, 'zzz')).toEqual([])
  })

  it('preserves the order of the input list', () => {
    expect(matchTagsByName(all, 'a').map((m) => m.tag.id)).toEqual(['t3', 't4', 't5', 't6'])
  })

  // --- synonyms (#280) ---

  it('finds a tag by one of its synonyms and reports which one', () => {
    const result = matchTagsByName(all, 'Rechnung')
    expect(result).toHaveLength(1)
    expect(result[0].tag.id).toBe('t1')
    expect(result[0].via).toBe('Rechnung')
  })

  it('matches a synonym case-insensitively and by prefix', () => {
    expect(matchTagsByName(all, 'quitt')[0]).toMatchObject({ via: 'Quittung' })
  })

  it('reports no synonym when the tag matched by its own name', () => {
    const result = matchTagsByName(all, 'Invoice')
    expect(result).toEqual([{ tag: invoice, via: null }])
  })

  it('prefers the tag name over a synonym when both match', () => {
    const both = tag('t9', 'Rechnung', ['Rechnungen'])
    expect(matchTagsByName([both], 'Rechnung')).toEqual([{ tag: both, via: null }])
  })

  it('returns a tag once even when several of its synonyms match', () => {
    const many = tag('t9', 'Insurance', ['Versicherung', 'Versicherungen'])
    const result = matchTagsByName([many], 'Versicherung')
    expect(result).toHaveLength(1)
    expect(result[0].via).toBe('Versicherung')
  })

  it('folds accents in synonyms exactly as it does in names', () => {
    const uber = tag('t9', 'Transport', ['Überführung'])
    expect(matchTagsByName([uber], 'uberf')[0]).toMatchObject({ via: 'Überführung' })
  })

  it('treats a tag with no synonyms field as a tag with no synonyms', () => {
    expect(matchTagsByName([receipt], 'Receipt')).toEqual([{ tag: receipt, via: null }])
    expect(matchTagsByName([receipt], 'Rechnung')).toEqual([])
  })
})
