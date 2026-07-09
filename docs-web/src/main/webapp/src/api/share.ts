import api from './client'

// Share-by-URL client over ShareResource (@Path("/share")).
//
// A share is modelled backend-side as an ACL of type SHARE on a document: the
// share id doubles as the ACL target id and is the token passed as ?share=<id>
// on anonymous document/file reads. There is no dedicated "list shares"
// endpoint — active shares are surfaced in the document detail response under
// acls[] where type === 'SHARE' (see listSharesFromAcls below).

export interface Share {
  id: string
  name: string
  perm: 'READ' | 'WRITE'
  type: 'SHARE'
}

/**
 * Create a share on a document. Requires WRITE permission on the document.
 * The returned id is the share token used to build the anonymous link.
 */
export function createShare(documentId: string, name?: string) {
  const params = new URLSearchParams()
  params.set('id', documentId)
  if (name && name.trim()) params.set('name', name.trim())
  return api.put<Share>('/share', params)
}

/**
 * Delete (revoke) a share by its id. Requires WRITE permission on the linked
 * document.
 */
export function deleteShare(shareId: string) {
  return api.delete<{ status: string }>(`/share/${shareId}`)
}

/**
 * Build the public, unauthenticated share URL for a document/share pair. The
 * SPA uses hash routing, so the link targets the public #/share route which
 * threads ?share=<shareId> into every backend read.
 */
export function buildShareUrl(documentId: string, shareId: string): string {
  const base = window.location.href.split('#')[0]
  return `${base}#/share/${documentId}/${shareId}`
}
