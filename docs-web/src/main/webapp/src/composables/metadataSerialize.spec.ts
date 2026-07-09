import { describe, it, expect } from 'vitest'
import { buildMetadataParams, serializeMetadataValue, shouldResetMetadata } from './metadataSerialize'
import type { MetadataDefinition } from '../api/metadata'

const defs: MetadataDefinition[] = [
  { id: 'str', name: 'Text', type: 'STRING' },
  { id: 'int', name: 'Count', type: 'INTEGER' },
  { id: 'flt', name: 'Amount', type: 'FLOAT' },
  { id: 'dat', name: 'Due', type: 'DATE' },
  { id: 'bool', name: 'Flag', type: 'BOOLEAN' },
]

describe('buildMetadataParams — omit unset fields (backend rejects blank numeric/date)', () => {
  it('omits ALL fields when nothing is set (no blank pairs submitted)', () => {
    const values = { str: null, int: null, flt: null, dat: null, bool: null }
    const result = buildMetadataParams(defs, values, new Set())
    expect(result).toEqual([])
  })

  it('omits an unset INTEGER/FLOAT/DATE rather than sending a blank value', () => {
    // These blanks would be validated as numbers/timestamps by the backend and
    // reject the whole document save. Only the set STRING must survive.
    const values = { str: 'hello', int: null, flt: null, dat: null, bool: null }
    const result = buildMetadataParams(defs, values, new Set())
    expect(result).toEqual([{ id: 'str', value: 'hello' }])
    expect(result.some((p) => p.value === '')).toBe(false)
  })

  it('omits an empty-string STRING', () => {
    const values = { str: '', int: null, flt: null, dat: null, bool: null }
    expect(buildMetadataParams(defs, values, new Set())).toEqual([])
  })

  it('omits an UNSET boolean (must not silently become "false")', () => {
    // bool value present as false in the map but its id NOT in setIds -> unset.
    const values = { str: null, int: null, flt: null, dat: null, bool: false }
    const result = buildMetadataParams(defs, values, new Set())
    expect(result).toEqual([])
  })

  it('submits a deliberately-set boolean=false with correct typing', () => {
    const values = { str: null, int: null, flt: null, dat: null, bool: false }
    const result = buildMetadataParams(defs, values, new Set(['bool']))
    expect(result).toEqual([{ id: 'bool', value: 'false' }])
  })

  it('submits a set boolean=true', () => {
    const values = { str: null, int: null, flt: null, dat: null, bool: true }
    const result = buildMetadataParams(defs, values, new Set(['bool']))
    expect(result).toEqual([{ id: 'bool', value: 'true' }])
  })

  it('serializes a set DATE as epoch milliseconds', () => {
    const due = new Date('2026-07-09T00:00:00.000Z')
    const values = { str: null, int: null, flt: null, dat: due, bool: null }
    const result = buildMetadataParams(defs, values, new Set())
    expect(result).toEqual([{ id: 'dat', value: String(due.getTime()) }])
  })

  it('serializes set numeric values as strings', () => {
    const values = { str: null, int: 42, flt: 3.5, dat: null, bool: null }
    const result = buildMetadataParams(defs, values, new Set())
    expect(result).toEqual([
      { id: 'int', value: '42' },
      { id: 'flt', value: '3.5' },
    ])
  })

  it('submits every set field together in definition order', () => {
    const due = new Date('2026-01-02T03:04:05.000Z')
    const values = { str: 'x', int: 1, flt: 2.5, dat: due, bool: true }
    const result = buildMetadataParams(defs, values, new Set(['bool']))
    expect(result).toEqual([
      { id: 'str', value: 'x' },
      { id: 'int', value: '1' },
      { id: 'flt', value: '2.5' },
      { id: 'dat', value: String(due.getTime()) },
      { id: 'bool', value: 'true' },
    ])
  })
})

describe('shouldResetMetadata — send metadata_reset only on a genuine clear', () => {
  it('true when the document HAD set values but now emits zero params', () => {
    // The user cleared the last set metadata value; the backend preserves-on-omit,
    // so we must send the explicit clear-all sentinel.
    expect(shouldResetMetadata(true, [])).toBe(true)
  })

  it('false when the document NEVER had set values and emits zero params', () => {
    // A document that simply never had metadata must not send the sentinel.
    expect(shouldResetMetadata(false, [])).toBe(false)
  })

  it('false when params ARE present, even if the document had values', () => {
    // A normal update with values takes precedence — no sentinel.
    expect(shouldResetMetadata(true, [{ id: 'm1', value: 'x' }])).toBe(false)
  })

  it('false when params are present and the document had no prior values', () => {
    expect(shouldResetMetadata(false, [{ id: 'm1', value: 'x' }])).toBe(false)
  })
})

describe('serializeMetadataValue', () => {
  it('DATE -> epoch ms string', () => {
    const d = new Date('2026-07-09T12:00:00.000Z')
    expect(serializeMetadataValue('DATE', d)).toBe(String(d.getTime()))
  })
  it('BOOLEAN -> "true"/"false"', () => {
    expect(serializeMetadataValue('BOOLEAN', true)).toBe('true')
    expect(serializeMetadataValue('BOOLEAN', false)).toBe('false')
  })
  it('STRING/number -> String()', () => {
    expect(serializeMetadataValue('STRING', 'hi')).toBe('hi')
    expect(serializeMetadataValue('INTEGER', 7)).toBe('7')
  })
})
