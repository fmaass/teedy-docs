import api from './client'

export interface Tag {
  id: string
  name: string
  color: string
  parent: string | null
  count?: number
}

/**
 * A meta-tag is an auto/system tag whose name is prefixed with a double
 * underscore (e.g. `__recent`, `__review`). These are hidden from the FACETS
 * navigation and suggestion lists only — they still appear in Tree mode, search,
 * the tag picker, and as active filter chips.
 */
export function isMetaTag(name: string | undefined | null): boolean {
  return !!name && name.startsWith('__')
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

export function getTagFacets(tagIds?: string[], mode?: 'and' | 'or', excludedTagIds?: string[]) {
  const params = new URLSearchParams()
  if (tagIds?.length) params.set('tags', tagIds.join(','))
  if (mode === 'or') params.set('mode', 'or')
  // The backend accepts a repeated `exclude` query param (one per excluded tag id);
  // documents carrying any excluded tag are removed from the facet/total counts.
  if (excludedTagIds?.length) {
    for (const id of excludedTagIds) params.append('exclude', id)
  }
  return api.get<{ facets: Record<string, number>; total: number }>('/tag/facets', { params })
}
