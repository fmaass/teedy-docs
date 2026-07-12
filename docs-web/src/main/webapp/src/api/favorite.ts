import api from './client'
import type { DocumentListItem } from './document'

// Per-user document favorites over FavoriteResource (@Path("/favorite")).
//
// Favorites are private user state: a star is never visible to, nor countable by, any
// other user. Starring is idempotent (a repeat PUT is a 200 no-op); unstarring an
// un-favorited document is a 404.

export interface FavoriteListResponse {
  favorites: DocumentListItem[]
}

/** Favorite (star) a document for the current user. Idempotent. */
export function addFavorite(documentId: string) {
  return api.put<{ status: string }>(`/favorite/${documentId}`)
}

/** Remove the current user's favorite of a document. */
export function removeFavorite(documentId: string) {
  return api.delete<{ status: string }>(`/favorite/${documentId}`)
}

/** List the current user's favorited documents (ACL-filtered document DTOs). */
export function listFavorites() {
  return api.get<FavoriteListResponse>('/favorite')
}
