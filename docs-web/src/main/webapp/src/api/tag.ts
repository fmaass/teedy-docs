import api from './client'
import type { AclEntry } from './acl'

export interface Tag {
  id: string
  name: string
  color: string
  parent: string | null
  count?: number
}

/**
 * GET /tag/{id}. Carries the direct ACLs and the caller's writability, plus the creator's
 * username — the creator's own base READ/WRITE grants are mandatory and cannot be removed
 * (see AclResource base-ACL protection), so the editor renders them as immutable.
 */
export interface TagDetail {
  id: string
  name: string
  creator: string
  color: string
  parent: string | null
  acls: AclEntry[]
  writable: boolean
}

/**
 * A meta-tag is an auto/system tag whose name is prefixed with a double
 * underscore (e.g. `__recent`, `__review`). These are hidden from the FACETS
 * navigation and suggestion lists only — they still appear in Tree mode, search,
 * the tag picker, and as active filter chips.
 */
export function isMetaTag(name: string | undefined | null): boolean {
  return !!name && name.startsWith('__')
}

export function listTags() {
  return api.get<{ tags: Tag[] }>('/tag/list')
}

export function getTag(id: string) {
  return api.get<TagDetail>(`/tag/${id}`)
}

export function createTag(name: string, color: string, parent?: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('color', color)
  if (parent) params.set('parent', parent)
  return api.put<{ id: string }>('/tag', params)
}

export function updateTag(id: string, name: string, color: string, parent?: string | null) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('color', color)
  params.set('parent', parent ?? '')
  return api.post<{ id: string }>(`/tag/${id}`, params)
}

export function deleteTag(id: string) {
  return api.delete(`/tag/${id}`)
}

export function getTagStats() {
  return api.get<{ stats: Record<string, number> }>('/tag/stats')
}

/**
 * Why a tag's subtree may not be removed by tag maintenance.
 *
 * `trash` means no ACTIVE document carries it but a restorable one in the trash still does —
 * a distinct answer from `documents`, because the count shown in the tree is active-only and
 * would read 0. `other` is deliberately unexplained: it is what a branch holding a tag this
 * account cannot reach reports, and naming that reason would confirm such a tag exists.
 */
export type TagBlockReason = 'documents' | 'trash' | 'rule' | 'other'

/**
 * The server's verdict on one tag (#298 parts 1 and 2): may its whole subtree be removed, and
 * if not, why. `deletable` is the ONLY authority the UI has — a tag is deletable when neither it
 * nor any descendant carries a document, which is a question about tags the caller may not even
 * see, so the screen never recomputes it from the tag list.
 */
export interface TagMaintenanceItem {
  id: string
  name: string
  /** Slash-joined chain of visible ancestor names, this tag last. */
  path: string
  deletable: boolean
  /** True when this tag is the topmost deletable tag of its branch — a cleanup root. */
  root: boolean
  /** Documents on this tag and its readable descendants. */
  subtreeDocuments: number
  /** Absent when the tag is deletable. */
  reason?: TagBlockReason
}

/** A tag a destructive maintenance action removed. */
export interface DeletedTag {
  id: string
  name: string
  path: string
}

export interface TagDeletionReport {
  status: string
  count: number
  tags: DeletedTag[]
  /**
   * Tags the server re-checked immediately before deleting them and then KEPT, because they had
   * become used since the preview was rendered. Empty in the ordinary case; never absent, so the
   * screen can always report the difference between "removed" and "left standing".
   */
  blocked: DeletedTag[]
}

/** Reads the maintenance verdict for every visible tag. Modifies nothing. */
export function getTagMaintenance() {
  return api.get<{ tags: TagMaintenanceItem[] }>('/tag/maintenance')
}

/**
 * Deletes a tag and its whole subtree — refused by the server unless nothing in it carries a
 * document. NOT the same call as {@link deleteTag}, which removes one tag, un-assigns it from
 * every document and re-parents its children.
 */
export function deleteTagSubtree(id: string) {
  return api.delete<TagDeletionReport>(`/tag/${id}/subtree`)
}

/** Deletes every fully-unused tag subtree and reports what went. */
export function deleteUnusedTags() {
  return api.delete<TagDeletionReport>('/tag/maintenance')
}

export interface CoOccurrencePair {
  tagA: string
  tagB: string
  count: number
}

export function getTagCoOccurrence() {
  return api.get<{ pairs: CoOccurrencePair[] }>('/tag/co-occurrence')
}

export function getTagFacets(tagIds?: string[], mode?: 'and' | 'or', excludedTagIds?: string[]) {
  const params = new URLSearchParams()
  if (tagIds?.length) params.set('tags', tagIds.join(','))
  if (mode === 'or') params.set('mode', 'or')
  // The backend accepts a repeated `exclude` query param (one per excluded tag id);
  // documents carrying any excluded tag are removed from the facet/total counts.
  if (excludedTagIds?.length) {
    for (const id of excludedTagIds) params.append('exclude', id)
  }
  return api.get<{ facets: Record<string, number>; total: number }>('/tag/facets', { params })
}
