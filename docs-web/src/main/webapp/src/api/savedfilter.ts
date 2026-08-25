import api from './client'

export interface SavedFilterItem {
  id: string
  name: string
  /** The canonical URL query string captured from the documents route. */
  query: string
  create_date: number
  /** #51: true once the owner has published this filter to every user of the instance. */
  published: boolean
  /** When it was published, or null while it is private. */
  publish_date: number | null
}

/**
 * A filter ANOTHER user has published (#51). Never the caller's own — those stay in
 * `saved_filters`, marked with their `published` flag.
 */
export interface SharedSavedFilterItem {
  id: string
  name: string
  /**
   * The canonical URL query string — EMPTY when `hidden_tag_count` is non-zero. The server
   * withholds the criteria of a filter this viewer cannot apply, so the payload carries no
   * reference to tags they are not allowed to see.
   */
  query: string
  /** The publisher's username: two users may each own a filter of the same name. */
  username: string
  create_date: number
  publish_date: number
  /**
   * How many of the tags this filter names the caller cannot READ. Zero means applicable;
   * anything else means the filter is offered but disabled. A COUNT deliberately — never
   * which tags, never their names.
   */
  hidden_tag_count: number
}

export interface SavedFilterList {
  saved_filters: SavedFilterItem[]
  shared_filters: SharedSavedFilterItem[]
}

export function listSavedFilters() {
  return api.get<SavedFilterList>('/savedfilter')
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

/** Publishes one of the caller's OWN filters to every user (#51). Owner-only, server-side. */
export function publishSavedFilter(id: string) {
  return api.post<{ status: string; publish_date: number }>(
    `/savedfilter/${id}/publish`,
    new URLSearchParams(),
  )
}

/**
 * Withdraws a publication (#51). The owner may withdraw their own; an administrator may
 * withdraw anybody's — management, not authorship, so it never touches the filter itself.
 */
export function unpublishSavedFilter(id: string) {
  return api.delete(`/savedfilter/${id}/publish`)
}
