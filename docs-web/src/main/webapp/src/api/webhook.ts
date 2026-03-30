import api from './client'

export interface WebhookItem {
  id: string
  event: string
  url: string
  create_date: number
}

export const WEBHOOK_EVENTS = [
  'DOCUMENT_CREATED',
  'DOCUMENT_UPDATED',
  'DOCUMENT_DELETED',
  'DOCUMENT_TRASHED',
  'DOCUMENT_RESTORED',
  'FILE_CREATED',
  'FILE_UPDATED',
  'FILE_DELETED',
] as const

export function listWebhooks() {
  return api.get<{ webhooks: WebhookItem[] }>('/webhook')
}

export function createWebhook(event: string, url: string) {
  const params = new URLSearchParams()
  params.set('event', event)
  params.set('url', url)
  return api.put('/webhook', params)
}

export function deleteWebhook(id: string) {
  return api.delete(`/webhook/${id}`)
}
