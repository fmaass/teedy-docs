import { useQueryClient } from '@tanstack/vue-query'
import { getDocument, updateDocument, type DocumentDetail } from '../api/document'
import { buildAddTagParams, buildRemoveTagParams } from '../utils/bulkOps'
import { queryKeys, tagCountKeys } from '../api/queryKeys'
import { useToast } from 'primevue/usetoast'

/**
 * Single-document tag add/remove used by the list context menu and slide-over.
 *
 * A tag toggle is a PARTIAL update: it sends only title + language + the new tag
 * list (the POST /document/{id} contract — see utils/bulkOps.ts). We deliberately
 * do NOT re-send every document field: replace-semantics on a full field set
 * would clobber a title/description another client edited concurrently. The tag
 * param builders in bulkOps encode exactly that minimal contract, so add/remove
 * reuse them rather than re-implementing the form serialization.
 */
export function useDocumentTags() {
  const queryClient = useQueryClient()
  const toast = useToast()

  function showError() {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to update tags', life: 3000 })
  }

  function invalidate(docId: string) {
    queryClient.invalidateQueries({ queryKey: queryKeys.documents() })
    queryClient.invalidateQueries({ queryKey: queryKeys.document(docId) })
    // Sidebar/facet counts depend on which tags sit on which documents; a toggle
    // stales them or the counts drift until the next unrelated refetch.
    for (const key of tagCountKeys) queryClient.invalidateQueries({ queryKey: key })
  }

  async function addTag(docId: string, tagId: string, currentDoc?: DocumentDetail): Promise<DocumentDetail | null> {
    try {
      const doc = currentDoc ?? (await getDocument(docId)).data
      if ((doc.tags ?? []).some((t) => t.id === tagId)) return doc
      await updateDocument(docId, buildAddTagParams(doc, tagId))
      invalidate(docId)
      return doc
    } catch {
      showError()
      return null
    }
  }

  async function removeTag(docId: string, tagId: string, currentDoc?: DocumentDetail): Promise<DocumentDetail | null> {
    try {
      const doc = currentDoc ?? (await getDocument(docId)).data
      await updateDocument(docId, buildRemoveTagParams(doc, tagId))
      invalidate(docId)
      return doc
    } catch {
      showError()
      return null
    }
  }

  return { addTag, removeTag }
}
