import api from './client'

export interface Tag {
  id: string
  name: string
  color: string
  parent: string | null
  count?: number
}

export function listTags() {
  return api.get<{ tags: Tag[] }>('/tag/list')
}

export function createTag(name: string, color: string, parent?: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('color', color)
  if (parent) params.set('parent', parent)
  return api.put<{ id: string }>('/tag', params)
}

export function updateTag(id: string, name: string, color: string, parent?: string | null) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('color', color)
  params.set('parent', parent ?? '')
  return api.post<{ id: string }>(`/tag/${id}`, params)
}

export function deleteTag(id: string) {
  return api.delete(`/tag/${id}`)
}

export function getTagStats() {
  return api.get<{ stats: Record<string, number> }>('/tag/stats')
}

export interface CoOccurrencePair {
  tagA: string
  tagB: string
  count: number
}

export function getTagCoOccurrence() {
  return api.get<{ pairs: CoOccurrencePair[] }>('/tag/co-occurrence')
}

export function getTagFacets(tagIds?: string[], mode?: 'and' | 'or') {
  const params: Record<string, string> = {}
  if (tagIds?.length) params.tags = tagIds.join(',')
  if (mode === 'or') params.mode = 'or'
  return api.get<{ facets: Record<string, number>; total: number }>('/tag/facets', { params })
}
