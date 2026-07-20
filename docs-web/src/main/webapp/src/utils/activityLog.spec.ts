import { describe, it, expect } from 'vitest'
import {
  ACTIVITY_TYPE_LABEL_KEYS,
  activityTypeLabel,
  mergeAuditRows,
  observedTypes,
  reconcileSelection,
} from './activityLog'

// Stub translator: returns the i18n key verbatim so assertions target the mapping
// logic, not the catalog copy.
const t = (key: string) => key

describe('activityTypeLabel (#113)', () => {
  it('maps every known docs-core AuditLogType to its catalog key', () => {
    expect(activityTypeLabel('CREATE', t)).toBe('ui.activity.type_create')
    expect(activityTypeLabel('UPDATE', t)).toBe('ui.activity.type_update')
    expect(activityTypeLabel('DELETE', t)).toBe('ui.activity.type_delete')
    expect(activityTypeLabel('AUTHENTICATION', t)).toBe('ui.activity.type_authentication')
  })

  it('covers exactly the four AuditLogType constants in the static key map', () => {
    expect(Object.keys(ACTIVITY_TYPE_LABEL_KEYS).sort()).toEqual([
      'AUTHENTICATION',
      'CREATE',
      'DELETE',
      'UPDATE',
    ])
  })

  it('falls back to the raw type string for an unknown/unmapped type', () => {
    expect(activityTypeLabel('SOMETHING_NEW', t)).toBe('SOMETHING_NEW')
    expect(activityTypeLabel('', t)).toBe('')
  })

  it('does not lower-case or otherwise rewrite an unknown type', () => {
    expect(activityTypeLabel('Create', t)).toBe('Create')
  })
})

describe('observedTypes (#113)', () => {
  it('returns the distinct types in first-seen order', () => {
    expect(
      observedTypes([{ type: 'CREATE' }, { type: 'UPDATE' }, { type: 'CREATE' }, { type: 'UPDATE' }]),
    ).toEqual(['CREATE', 'UPDATE'])
  })

  it('is empty for no rows', () => {
    expect(observedTypes([])).toEqual([])
  })

  it('ignores rows with an empty/missing type', () => {
    expect(observedTypes([{ type: '' }, { type: 'DELETE' }])).toEqual(['DELETE'])
  })
})

describe('reconcileSelection (#113 stale-filter auto-clear)', () => {
  it('keeps a selection that is still present in the observed set', () => {
    expect(reconcileSelection('UPDATE', ['CREATE', 'UPDATE'])).toBe('UPDATE')
  })

  it('clears a selection whose type is no longer observed (document switch / refetch)', () => {
    expect(reconcileSelection('UPDATE', ['CREATE'])).toBeNull()
  })

  it('clears any selection when the observed set is empty', () => {
    expect(reconcileSelection('CREATE', [])).toBeNull()
  })

  it('is a no-op when nothing is selected', () => {
    expect(reconcileSelection(null, ['CREATE', 'UPDATE'])).toBeNull()
  })
})

describe('mergeAuditRows (#139 load-older append + dedupe)', () => {
  const row = (id: string) => ({ id, type: 'CREATE' })

  it('appends the incoming rows after the existing ones, order preserved', () => {
    expect(mergeAuditRows([row('a'), row('b')], [row('c'), row('d')])).toEqual([
      row('a'),
      row('b'),
      row('c'),
      row('d'),
    ])
  })

  it('drops an incoming row whose id is already present (boundary row returned twice)', () => {
    // Keyset pages can overlap on the boundary row; the accumulated list must not duplicate it.
    expect(mergeAuditRows([row('a'), row('b')], [row('b'), row('c')])).toEqual([
      row('a'),
      row('b'),
      row('c'),
    ])
  })

  it('dedupes within the incoming batch as well', () => {
    expect(mergeAuditRows([row('a')], [row('b'), row('b'), row('c')])).toEqual([
      row('a'),
      row('b'),
      row('c'),
    ])
  })

  it('returns the existing rows unchanged for an empty incoming page (has_more=false tail)', () => {
    expect(mergeAuditRows([row('a'), row('b')], [])).toEqual([row('a'), row('b')])
  })

  it('does not mutate the existing array', () => {
    const existing = [row('a')]
    mergeAuditRows(existing, [row('b')])
    expect(existing).toEqual([row('a')])
  })

  it('ignores incoming rows with a missing id', () => {
    const incoming = [{ id: '', type: 'CREATE' }, row('c')]
    expect(mergeAuditRows([row('a')], incoming)).toEqual([row('a'), row('c')])
  })
})
