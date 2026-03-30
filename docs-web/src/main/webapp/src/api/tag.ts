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

export function getTagFacets(tagIds?: string[]) {
  const params = tagIds?.length ? { tags: tagIds.join(',') } : {}
  return api.get<{ facets: Record<string, number>; total: number }>('/tag/facets', { params })
}
