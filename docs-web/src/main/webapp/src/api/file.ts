import api from './client'

export function uploadFile(documentId: string, file: File) {
  const formData = new FormData()
  formData.append('id', documentId)
  formData.append('file', file)
  return api.put('/file', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function getFileUrl(fileId: string, size?: 'web' | 'thumb' | 'content') {
  const params = size ? `?size=${size}` : ''
  return `api/file/${fileId}/data${params}`
}

export function deleteFile(fileId: string) {
  return api.delete(`/file/${fileId}`)
}

export function renameFile(fileId: string, name: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  return api.post(`/file/${fileId}`, params)
}
