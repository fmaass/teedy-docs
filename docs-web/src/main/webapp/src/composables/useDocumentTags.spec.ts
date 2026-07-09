import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { DocumentDetail } from '../api/document'

// --- Dependency mocks (NOT the unit under test) ---
//
// useDocumentTags pulls in vue-query (useQueryClient) and PrimeVue (useToast) at
// call time, plus the document API. We mock those so the composable's OWN logic
// — which param builder it calls, what it invalidates, that it never re-sends
// every field — runs for real against controllable data.

const invalidateQueries = vi.fn()
vi.mock('@tanstack/vue-query', () => ({
  useQueryClient: () => ({ invalidateQueries }),
}))

const toastAdd = vi.fn()
vi.mock('primevue/usetoast', () => ({
  useToast: () => ({ add: toastAdd }),
}))

const getDocumentMock = vi.fn<(id: string) => Promise<{ data: DocumentDetail }>>()
const updateDocumentMock =
  vi.fn<(id: string, params: URLSearchParams) => Promise<{ data: { id: string } }>>(() =>
    Promise.resolve({ data: { id: 'doc1' } }),
  )
vi.mock('../api/document', () => ({
  getDocument: (id: string) => getDocumentMock(id),
  updateDocument: (id: string, params: URLSearchParams) => updateDocumentMock(id, params),
}))

import { useDocumentTags } from './useDocumentTags'

function makeDoc(overrides: Partial<DocumentDetail> = {}): DocumentDetail {
  return {
    id: 'doc1',
    title: 'A title',
    description: 'a long description that must NOT be re-sent on a tag toggle',
    create_date: 0,
    update_date: 0,
    language: 'eng',
    file_id: null,
    file_count: 0,
    tags: [{ id: 't1', name: 'A', color: '#000' }],
    shared: false,
    subject: 'subj',
    identifier: 'id',
    publisher: 'pub',
    format: 'fmt',
    source: 'src',
    type: 'typ',
    coverage: 'cov',
    rights: 'rts',
    creator: 'me',
    writable: true,
    contributors: [],
    relations: [{ id: 'rel1', title: 'R', source: true }],
    metadata: [{ id: 'm1', name: 'Count', type: 'INTEGER', value: 5 }],
    ...overrides,
  }
}

function sentParams(): URLSearchParams {
  return updateDocumentMock.mock.calls[0][1]
}

describe('useDocumentTags — partial-update contract (title+language+tags only)', () => {
  beforeEach(() => {
    invalidateQueries.mockClear()
    toastAdd.mockClear()
    getDocumentMock.mockReset()
    updateDocumentMock.mockClear()
  })

  it('addTag sends ONLY title, language and the new tag list — never description/subject/metadata/relations', async () => {
    const { addTag } = useDocumentTags()
    await addTag('doc1', 't2', makeDoc())

    const p = sentParams()
    expect(p.get('title')).toBe('A title')
    expect(p.get('language')).toBe('eng')
    expect(p.getAll('tags')).toEqual(['t1', 't2'])
    // Partial update: none of these fields may be re-sent (would clobber a
    // concurrent edit).
    expect(p.has('description')).toBe(false)
    expect(p.has('subject')).toBe(false)
    expect(p.has('metadata_id')).toBe(false)
    expect(p.has('relations')).toBe(false)
  })

  it('removeTag drops the target tag and preserves the rest, still partial-update', async () => {
    const { removeTag } = useDocumentTags()
    await removeTag('doc1', 't1', makeDoc({ tags: [
      { id: 't1', name: 'A', color: '#000' },
      { id: 't2', name: 'B', color: '#000' },
    ] }))

    const p = sentParams()
    expect(p.getAll('tags')).toEqual(['t2'])
    expect(p.has('description')).toBe(false)
    expect(p.has('metadata_id')).toBe(false)
  })

  it('addTag is a no-op update when the doc already has the tag', async () => {
    const { addTag } = useDocumentTags()
    await addTag('doc1', 't1', makeDoc())
    expect(updateDocumentMock).not.toHaveBeenCalled()
  })

  it('fetches the document itself only when no currentDoc is supplied (no redundant pre-fetch)', async () => {
    getDocumentMock.mockResolvedValue({ data: makeDoc() })
    const { addTag } = useDocumentTags()

    // currentDoc supplied → no fetch.
    await addTag('doc1', 't2', makeDoc())
    expect(getDocumentMock).not.toHaveBeenCalled()

    // no currentDoc → exactly one fetch, and NO trailing re-fetch after the update.
    await addTag('doc1', 't3')
    expect(getDocumentMock).toHaveBeenCalledTimes(1)
  })

  it('invalidates documents, the single document, and all tag-count queries', async () => {
    const { addTag } = useDocumentTags()
    await addTag('doc1', 't2', makeDoc())

    const invalidated = invalidateQueries.mock.calls.map((c) => (c[0] as any).queryKey[0])
    expect(invalidated).toContain('documents')
    expect(invalidated).toContain('document')
    expect(invalidated).toContain('tagStats')
    expect(invalidated).toContain('tagFacets')
    expect(invalidated).toContain('tagCoOccurrence')
  })
})
