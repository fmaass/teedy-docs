import api from './client'

// A single vocabulary entry (one selectable value within a named vocabulary).
export interface VocabularyEntry {
  id: string
  name: string
  value: string
  order: number
}

// List the distinct vocabulary names (admin-only).
export function listVocabularyNames() {
  return api.get<{ names: string[] }>('/vocabulary')
}

// Get all entries of a single vocabulary, ordered (readable by any authenticated user).
export function getVocabulary(name: string) {
  return api.get<{ entries: VocabularyEntry[] }>(`/vocabulary/${name}`)
}

export function createVocabularyEntry(name: string, value: string, order: number) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('value', value)
  params.set('order', String(order))
  return api.put<VocabularyEntry>('/vocabulary', params)
}

export function updateVocabularyEntry(
  id: string,
  fields: { name?: string; value?: string; order?: number },
) {
  const params = new URLSearchParams()
  if (fields.name !== undefined) params.set('name', fields.name)
  if (fields.value !== undefined) params.set('value', fields.value)
  if (fields.order !== undefined) params.set('order', String(fields.order))
  return api.post<VocabularyEntry>(`/vocabulary/${id}`, params)
}

export function deleteVocabularyEntry(id: string) {
  return api.delete<{ status: string }>(`/vocabulary/${id}`)
}
