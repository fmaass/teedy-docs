import api from './client'

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

export async function getFileContent(fileId: string): Promise<string> {
  const res = await api.get(`/file/${fileId}/data`, {
    params: { size: 'content' },
    responseType: 'text',
    transformResponse: [(data: string) => data],
  })
  return res.data
}
