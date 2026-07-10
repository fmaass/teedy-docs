import api from './client'

// Custom metadata field types supported by the backend (MetadataType enum).
export const METADATA_TYPES = ['STRING', 'INTEGER', 'FLOAT', 'DATE', 'BOOLEAN', 'VOCABULARY'] as const

export type MetadataType = (typeof METADATA_TYPES)[number]

// A metadata field definition (name + type), managed by administrators.
// `vocabulary` is the referenced vocabulary name, present only when type is VOCABULARY.
export interface MetadataDefinition {
  id: string
  name: string
  type: MetadataType
  vocabulary?: string
}

export function listMetadata() {
  return api.get<{ metadata: MetadataDefinition[] }>('/metadata')
}

export function createMetadata(name: string, type: MetadataType, vocabulary?: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('type', type)
  if (type === 'VOCABULARY' && vocabulary) {
    params.set('vocabulary', vocabulary)
  }
  return api.put<MetadataDefinition>('/metadata', params)
}

export function updateMetadata(id: string, name: string, vocabulary?: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  if (vocabulary) {
    params.set('vocabulary', vocabulary)
  }
  return api.post<MetadataDefinition>(`/metadata/${id}`, params)
}

export function deleteMetadata(id: string) {
  return api.delete<{ status: string }>(`/metadata/${id}`)
}
