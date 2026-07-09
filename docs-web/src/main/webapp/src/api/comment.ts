import api from './client'

export interface Comment {
  id: string
  content: string
  creator: string
  creator_gravatar: string
  create_date: number
}

export interface CommentListResponse {
  comments: Comment[]
}

// GET /comment/:documentId — list comments on a document. Requires READ ACL on
// the document; the backend returns 404 when access is denied.
export function listComments(documentId: string) {
  return api.get<CommentListResponse>(`/comment/${documentId}`)
}

// PUT /comment — add a comment. READ access on the document grants the right to
// comment. Returns the created comment.
export function addComment(documentId: string, content: string) {
  const params = new URLSearchParams()
  params.set('id', documentId)
  params.set('content', content)
  return api.put<Comment>('/comment', params)
}

// DELETE /comment/:id — delete a comment. The creator may delete their own
// comment; otherwise WRITE access on the document is required.
export function deleteComment(id: string) {
  return api.delete<{ status: string }>(`/comment/${id}`)
}

// Gravatar URL from the hash returned by the backend (identicon fallback).
export function gravatarUrl(hash: string, size = 40): string {
  return `https://www.gravatar.com/avatar/${hash}?s=${size}&d=identicon`
}
