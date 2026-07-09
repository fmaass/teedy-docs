import { useQueryClient } from '@tanstack/vue-query'
import { getDocument, updateDocument, type DocumentDetail } from '../api/document'
import { useToast } from 'primevue/usetoast'

export function buildFullParams(doc: DocumentDetail, tagIds: string[]): URLSearchParams {
  const p = new URLSearchParams()
  p.set('title', doc.title)
  p.set('language', doc.language)
  if (doc.description) p.set('description', doc.description)
  if (doc.subject) p.set('subject', doc.subject)
  if (doc.identifier) p.set('identifier', doc.identifier)
  if (doc.publisher) p.set('publisher', doc.publisher)
  if (doc.format) p.set('format', doc.format)
  if (doc.source) p.set('source', doc.source)
  if (doc.type) p.set('type', doc.type)
  if (doc.coverage) p.set('coverage', doc.coverage)
  if (doc.rights) p.set('rights', doc.rights)
  if (doc.create_date) p.set('create_date', String(doc.create_date))
  for (const id of tagIds) p.append('tags', id)
  for (const r of doc.relations ?? []) p.append('relations', r.id)
  for (const m of doc.metadata ?? []) {
    // Skip unset fields: the backend validates INTEGER/FLOAT/DATE values and rejects
    // the ENTIRE save on a blank (Integer.parseInt("") -> 400). A set BOOLEAN false is
    // meaningful and must still be sent (false != null && false !== '').
    if (m.value == null || m.value === '') continue
    p.append('metadata_id', m.id)
    p.append('metadata_value', String(m.value))
  }
  return p
}

export function useDocumentTags() {
  const queryClient = useQueryClient()
  const toast = useToast()

  function showError() {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to update tags', life: 3000 })
  }

  async function addTag(docId: string, tagId: string, currentDoc?: DocumentDetail): Promise<DocumentDetail | null> {
    try {
      const doc = currentDoc ?? (await getDocument(docId)).data
      const currentTagIds = doc.tags?.map((t) => t.id) ?? []
      if (currentTagIds.includes(tagId)) return doc
      const params = buildFullParams(doc, [...currentTagIds, tagId])
      await updateDocument(docId, params)
      queryClient.invalidateQueries({ queryKey: ['documents'] })
      queryClient.invalidateQueries({ queryKey: ['document', docId] })
      const { data: refreshed } = await getDocument(docId)
      return refreshed
    } catch {
      showError()
      return null
    }
  }

  async function removeTag(docId: string, tagId: string, currentDoc?: DocumentDetail): Promise<DocumentDetail | null> {
    try {
      const doc = currentDoc ?? (await getDocument(docId)).data
      const currentTagIds = (doc.tags?.map((t) => t.id) ?? []).filter((id) => id !== tagId)
      const params = buildFullParams(doc, currentTagIds)
      await updateDocument(docId, params)
      queryClient.invalidateQueries({ queryKey: ['documents'] })
      queryClient.invalidateQueries({ queryKey: ['document', docId] })
      const { data: refreshed } = await getDocument(docId)
      return refreshed
    } catch {
      showError()
      return null
    }
  }

  return { addTag, removeTag }
}
