import { describe, it, expect } from 'vitest'
import { partitionByNameConflict, type ExistingFile } from './fileConflicts'

// Build a real File so the partition runs against the same object type the upload
// bar hands it (name is the only field the matcher reads).
function f(name: string): File {
  return new File(['x'], name, { type: 'text/plain' })
}

const existing: ExistingFile[] = [
  { id: 'file-a', name: 'Report.pdf' },
  { id: 'file-b', name: 'notes.txt' },
]

describe('partitionByNameConflict', () => {
  it('routes a name that matches no existing file to `fresh`', () => {
    const { conflicts, fresh } = partitionByNameConflict([f('brand-new.txt')], existing)
    expect(conflicts).toEqual([])
    expect(fresh.map((x) => x.name)).toEqual(['brand-new.txt'])
  })

  it('matches an existing file case-insensitively and carries its id as the version base', () => {
    const { conflicts, fresh } = partitionByNameConflict([f('REPORT.PDF')], existing)
    expect(fresh).toEqual([])
    expect(conflicts).toHaveLength(1)
    expect(conflicts[0].file.name).toBe('REPORT.PDF')
    expect(conflicts[0].existing).toEqual({ id: 'file-a', name: 'Report.pdf' })
  })

  it('splits a mixed multi-drop into its conflicting and fresh parts, preserving order', () => {
    const dropped = [f('Report.pdf'), f('fresh-1.txt'), f('NOTES.txt'), f('fresh-2.txt')]
    const { conflicts, fresh } = partitionByNameConflict(dropped, existing)
    expect(conflicts.map((c) => c.file.name)).toEqual(['Report.pdf', 'NOTES.txt'])
    expect(conflicts.map((c) => c.existing.id)).toEqual(['file-a', 'file-b'])
    expect(fresh.map((x) => x.name)).toEqual(['fresh-1.txt', 'fresh-2.txt'])
  })

  it('treats every dropped file as fresh when the document has no existing files', () => {
    const { conflicts, fresh } = partitionByNameConflict([f('a.txt'), f('b.txt')], [])
    expect(conflicts).toEqual([])
    expect(fresh.map((x) => x.name)).toEqual(['a.txt', 'b.txt'])
  })

  it('returns empty partitions for an empty drop', () => {
    expect(partitionByNameConflict([], existing)).toEqual({ conflicts: [], fresh: [] })
  })

  it('binds a conflict to the FIRST existing file of a duplicated name (deterministic base)', () => {
    const dupes: ExistingFile[] = [
      { id: 'first', name: 'dup.txt' },
      { id: 'second', name: 'dup.txt' },
    ]
    const { conflicts } = partitionByNameConflict([f('dup.txt')], dupes)
    expect(conflicts).toHaveLength(1)
    expect(conflicts[0].existing.id).toBe('first')
  })
})
