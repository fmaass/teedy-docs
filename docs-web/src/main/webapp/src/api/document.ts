import api from './client'

export interface DocumentListItem {
  id: string
  title: string
  description: string
  create_date: number
  update_date: number
  language: string
  file_id: string | null
  file_count: number
  tags: Array<{ id: string; name: string; color: string }>
  shared: boolean
  active_route: boolean
  highlight?: string
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
  metadata: Array<{ id: string; name: string; type: string; value?: unknown }>
  files?: Array<{ id: string; name: string; mimetype: string; size: number }>
  acls?: Acl[]
  inherited_acls?: InheritedAcl[]
}

export interface DocumentListParams {
  offset?: number
  limit?: number
  sort_column?: number
  asc?: boolean
  search?: string
  files?: boolean
  'search[tagMode]'?: 'and' | 'or'
}

export function listDocuments(params: DocumentListParams) {
  return api.get<DocumentListResponse>('/document/list', { params })
}

export function getDocument(id: string, files = true) {
  return api.get<DocumentDetail>(`/document/${id}`, { params: { files } })
}

export function createDocument(params: URLSearchParams) {
  return api.put<{ id: string }>('/document', params)
}

export function updateDocument(id: string, params: URLSearchParams) {
  return api.post<{ id: string }>(`/document/${id}`, params)
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
