import api from './client'

export interface ApiKeyItem {
  id: string
  name: string
  prefix: string
  create_date: number
  last_used_date?: number
}

export interface ApiKeyCreateResponse {
  id: string
  name: string
  key: string
}

export function listApiKeys() {
  return api.get<{ api_keys: ApiKeyItem[] }>('/apikey')
}

export function createApiKey(name: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  return api.put<ApiKeyCreateResponse>('/apikey', params)
}

export function deleteApiKey(id: string) {
  return api.delete(`/apikey/${id}`)
}
