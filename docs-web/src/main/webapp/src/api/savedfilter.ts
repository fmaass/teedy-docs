import api from './client'

export interface SavedFilterItem {
  id: string
  name: string
  /** The canonical URL query string captured from the documents route. */
  query: string
  create_date: number
}

export function listSavedFilters() {
  return api.get<{ saved_filters: SavedFilterItem[] }>('/savedfilter')
}

export function createSavedFilter(name: string, query: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('query', query)
  return api.put<{ id: string; name: string; query: string }>('/savedfilter', params)
}

/**
 * Updates an existing saved filter (rename and/or re-capture its query).
 * Teedy convention: PUT creates, POST /{id} updates.
 */
export function updateSavedFilter(id: string, name: string, query: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('query', query)
  return api.post<{ id: string; name: string; query: string }>(`/savedfilter/${id}`, params)
}

export function deleteSavedFilter(id: string) {
  return api.delete(`/savedfilter/${id}`)
}
