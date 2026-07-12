import api from './client'
import type { RouteStepSummary } from './route'

export interface DocumentListItem {
  id: string
  title: string
  description: string
  create_date: number
  update_date: number
  language: string
  file_id: string | null
  // Baked clockwise rotation of the main file's raster (0/90/180/270); drives the list
  // thumbnail/preview cache-bust so a rotated document shows the oriented raster.
  file_rotation?: number
  file_count: number
  tags: Array<{ id: string; name: string; color: string }>
  shared: boolean
  active_route?: boolean
  current_step_name?: string | null
  highlight?: string
  // True if the current user has favorited this document (additive; per-user private state).
  favorite?: boolean
}

export interface DocumentListResponse {
  total: number
  documents: DocumentListItem[]
  suggestions: string[]
}

export interface Acl {
  id: string
  perm: 'READ' | 'WRITE'
  name: string
  type: 'USER' | 'GROUP' | 'SHARE'
}

export interface InheritedAcl {
  perm: 'READ' | 'WRITE'
  source_id: string
  source_name: string
  source_color: string
  id: string
  name: string
  type: 'USER' | 'GROUP' | 'SHARE'
}

export interface DocumentDetail extends DocumentListItem {
  subject: string
  identifier: string
  publisher: string
  format: string
  source: string
  type: string
  coverage: string
  rights: string
  creator: string
  writable: boolean
  file_count: number
  contributors: Array<{ username: string; email: string }>
  relations: Array<{ id: string; title: string; source: boolean }>
  metadata: Array<{ id: string; name: string; type: string; value?: unknown; vocabulary?: string }>
  files?: Array<{ id: string; name: string; mimetype: string; size: number; rotation?: number }>
  acls?: Acl[]
  inherited_acls?: InheritedAcl[]
  // Current active route step, present only when a route is active on this document AND the caller
  // is not anonymous. Carries `transitionable` (may the caller act now). See DocumentResource.
  route_step?: RouteStepSummary
}

export interface DocumentListParams {
  offset?: number
  limit?: number
  sort_column?: number
  asc?: boolean
  search?: string
  files?: boolean
  'search[tagMode]'?: 'and' | 'or'
  // Structured "awaiting my action" filter. Value 'me' restricts the list to documents whose
  // current route step targets the caller (the active_route/current_step_name row fields are
  // target-scoped to the same signal). Sent as a typed param instead of a literal `workflow:me`
  // token folded into the free-text search.
  'search[searchworkflow]'?: 'me'
  // Restrict the list to the current user's favorited documents. URL-round-trippable so it
  // composes with search/tags/pagination identically to every other filter dimension.
  favorites?: 'me'
}

export function listDocuments(params: DocumentListParams) {
  return api.get<DocumentListResponse>('/document/list', { params })
}

export function getDocument(id: string, files = true, shareId?: string) {
  const params: Record<string, unknown> = { files }
  if (shareId) params.share = shareId
  return api.get<DocumentDetail>(`/document/${id}`, { params })
}

export function createDocument(params: URLSearchParams) {
  return api.put<{ id: string }>('/document', params)
}

export function updateDocument(id: string, params: URLSearchParams) {
  return api.post<{ id: string }>(`/document/${id}`, params)
}

/**
 * Build the form body for an outgoing-relations edit via POST /document/:id.
 *
 * The update endpoint validates `title` and `language` as REQUIRED before it processes
 * relations (DocumentResource#update), so both are always sent — omitting them would
 * fail the whole request. Only relation fields are otherwise included, so a relations-only
 * edit never touches the document's other fields (tags, description, metadata): the backend
 * partial-update contract preserves any param not present in the body.
 *
 * `outgoingIds` is the FULL surviving list of outgoing (source=true) relation targets. When
 * it is empty, the `relations_reset=true` sentinel is sent so the backend clears the last
 * outgoing relation (an empty `relations` list is otherwise indistinguishable from "omitted"
 * and would preserve the existing relations).
 */
export function buildRelationsParams(
  title: string,
  language: string,
  outgoingIds: string[],
): URLSearchParams {
  const params = new URLSearchParams()
  params.append('title', title)
  params.append('language', language)
  if (outgoingIds.length === 0) {
    params.append('relations_reset', 'true')
  } else {
    for (const id of outgoingIds) params.append('relations', id)
  }
  return params
}

/**
 * Import a new document from an .eml email file via PUT /api/document/eml
 * (multipart/form-data, param `file`). The backend parses the email, uses its
 * subject as the title, and attaches the body + attachments as files; it returns
 * the new document's id. Normal authenticated user — not admin-gated.
 */
export function importEml(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return api.put<{ id: string }>('/document/eml', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function deleteDocument(id: string) {
  return api.delete(`/document/${id}`)
}

export interface TrashItem {
  id: string
  title: string
  description: string | null
  language: string
  create_date: number
  delete_date: number
}

export interface TrashListResponse {
  total: number
  documents: TrashItem[]
}

export function listTrash(params?: { limit?: number; offset?: number }) {
  return api.get<TrashListResponse>('/document/trash', { params })
}

export function restoreDocument(id: string) {
  return api.post(`/document/${id}/restore`)
}

export function permanentDeleteDocument(id: string) {
  return api.delete(`/document/${id}/permanent`)
}

export function emptyTrash() {
  return api.delete<{ deleted_count: number }>('/document/trash')
}
