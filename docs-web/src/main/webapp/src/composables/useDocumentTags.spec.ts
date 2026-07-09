import { describe, it, expect } from 'vitest'
import { buildFullParams } from './useDocumentTags'
import type { DocumentDetail } from '../api/document'

// Minimal DocumentDetail factory: only the fields buildFullParams reads matter.
function makeDoc(
  metadata: DocumentDetail['metadata'],
  overrides: Partial<DocumentDetail> = {},
): DocumentDetail {
  return {
    id: 'doc1',
    title: 'A title',
    description: '',
    create_date: 0,
    update_date: 0,
    language: 'eng',
    file_id: null,
    file_count: 0,
    tags: [],
    shared: false,
    subject: '',
    identifier: '',
    publisher: '',
    format: '',
    source: '',
    type: '',
    coverage: '',
    rights: '',
    creator: '',
    writable: true,
    contributors: [],
    relations: [],
    metadata,
    ...overrides,
  }
}

// Extract the (metadata_id, metadata_value) pairs in submission order.
function metadataPairs(p: URLSearchParams): Array<{ id: string; value: string }> {
  const ids = p.getAll('metadata_id')
  const values = p.getAll('metadata_value')
  return ids.map((id, i) => ({ id, value: values[i] }))
}

describe('buildFullParams — metadata serialization (backend rejects blank numeric/date)', () => {
  it('omits an unset INTEGER field (no metadata_id/metadata_value pair for it)', () => {
    // Unset INTEGER would be validated as Integer.parseInt("") -> 400 on the whole save.
    const doc = makeDoc([{ id: 'int', name: 'Count', type: 'INTEGER' }])
    const params = buildFullParams(doc, [])
    expect(params.getAll('metadata_id')).not.toContain('int')
    expect(metadataPairs(params)).toEqual([])
  })

  it('omits an unset FLOAT field (no metadata_id/metadata_value pair for it)', () => {
    const doc = makeDoc([{ id: 'flt', name: 'Amount', type: 'FLOAT' }])
    const params = buildFullParams(doc, [])
    expect(params.getAll('metadata_id')).not.toContain('flt')
    expect(metadataPairs(params)).toEqual([])
  })

  it('omits an unset DATE field (no metadata_id/metadata_value pair for it)', () => {
    const doc = makeDoc([{ id: 'dat', name: 'Due', type: 'DATE' }])
    const params = buildFullParams(doc, [])
    expect(params.getAll('metadata_id')).not.toContain('dat')
    expect(metadataPairs(params)).toEqual([])
  })

  it('serializes a set STRING field', () => {
    const doc = makeDoc([{ id: 'str', name: 'Text', type: 'STRING', value: 'hello' }])
    const params = buildFullParams(doc, [])
    expect(metadataPairs(params)).toEqual([{ id: 'str', value: 'hello' }])
  })

  it('serializes a set BOOLEAN false (false is meaningful, not unset)', () => {
    const doc = makeDoc([{ id: 'bool', name: 'Flag', type: 'BOOLEAN', value: false }])
    const params = buildFullParams(doc, [])
    expect(metadataPairs(params)).toEqual([{ id: 'bool', value: 'false' }])
  })

  it('always serializes tags', () => {
    const doc = makeDoc([{ id: 'int', name: 'Count', type: 'INTEGER' }])
    const params = buildFullParams(doc, ['tagA', 'tagB'])
    expect(params.getAll('tags')).toEqual(['tagA', 'tagB'])
  })
})
