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
  file_count: number
  contributors: Array<{ username: string; email: string }>
  relations: Array<{ id: string; title: string; source: boolean }>
  metadata: Array<{ id: string; name: string; type: string; value?: any }>
  files?: Array<{ id: string; name: string; mimetype: string; size: number }>
}

export interface DocumentListParams {
  offset?: number
  limit?: number
  sort_column?: number
  asc?: boolean
  search?: string
  files?: boolean
}

export function listDocuments(params: DocumentListParams) {
  return api.get<DocumentListResponse>('/document/list', { params })
}

export function getDocument(id: string, files = true) {
  return api.get<DocumentDetail>(`/document/${id}`, { params: { files } })
}

export function createDocument(data: Record<string, string>) {
  const params = new URLSearchParams()
  Object.entries(data).forEach(([k, v]) => params.append(k, v))
  return api.put<{ id: string }>('/document', params)
}

export function updateDocument(id: string, data: Record<string, string>) {
  const params = new URLSearchParams()
  Object.entries(data).forEach(([k, v]) => params.append(k, v))
  return api.post<{ id: string }>(`/document/${id}`, params)
}

export function deleteDocument(id: string) {
  return api.delete(`/document/${id}`)
}
