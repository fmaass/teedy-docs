import api from './client'

/**
 * One historical revision of a file, as returned by GET /file/:id/versions.
 * The backend returns every row sharing the file's version_id (or the single
 * current row when the file has no version_id). Note: unlike /file/list, this
 * endpoint does NOT include a size field.
 */
export interface FileVersion {
  id: string
  name: string | null
  version: number
  mimetype: string
  create_date: number
}

export function uploadFile(documentId: string, file: File) {
  const formData = new FormData()
  formData.append('id', documentId)
  formData.append('file', file)
  return api.put('/file', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function getFileUrl(fileId: string, size?: 'web' | 'thumb' | 'content', shareId?: string) {
  const query = new URLSearchParams()
  if (size) query.set('size', size)
  if (shareId) query.set('share', shareId)
  const suffix = query.toString()
  return `api/file/${fileId}/data${suffix ? `?${suffix}` : ''}`
}

export function deleteFile(fileId: string) {
  return api.delete(`/file/${fileId}`)
}

export function renameFile(fileId: string, name: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  return api.post(`/file/${fileId}`, params)
}

export function reprocessFile(fileId: string) {
  return api.post(`/file/${fileId}/process`)
}

/**
 * List all versions of a file, newest revisions and the original included.
 * Backed by GET /file/:id/versions. There is no restore endpoint on the
 * backend, so this is read-only history.
 */
export async function getFileVersions(fileId: string): Promise<FileVersion[]> {
  const res = await api.get(`/file/${fileId}/versions`)
  return (res.data?.files ?? []) as FileVersion[]
}

export async function getFileContent(fileId: string): Promise<string> {
  const res = await api.get(`/file/${fileId}/data`, {
    params: { size: 'content' },
    responseType: 'text',
    transformResponse: [(data: string) => data],
  })
  return res.data
}
