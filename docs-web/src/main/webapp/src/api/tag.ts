import api from './client'
import type { AclEntry } from './acl'

export interface Tag {
  id: string
  name: string
  color: string
  /**
   * The tag's icon (#287): `emoji:<grapheme>` or `set:<iconId>`. ABSENT — not null — for a tag
   * with no icon, which is what every tag is until somebody chooses one. `utils/tagIcon.ts`
   * reads it; nothing else should take the string apart.
   */
  icon?: string
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
  icon?: string
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

export function createTag(name: string, color: string, parent?: string, icon?: string | null) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('color', color)
  if (parent) params.set('parent', parent)
  if (icon) params.set('icon', icon)
  return api.put<{ id: string }>('/tag', params)
}

export function updateTag(
  id: string,
  name: string,
  color: string,
  parent?: string | null,
  icon?: string | null,
) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('color', color)
  params.set('parent', parent ?? '')
  // Always sent, like `parent` and unlike `color`: an empty value is how the form says "no icon",
  // and omitting the field would make taking an icon back off a tag impossible.
  params.set('icon', icon ?? '')
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

/** A tag a reduction run took off a document, or would. */
export interface ReducedTag {
  id: string
  name: string
  /** Slash-joined chain of visible ancestor names, this tag last. */
  path: string
}

/** One document's redundant tags. */
export interface DocumentTagReduction {
  id: string
  tags: ReducedTag[]
}

/**
 * What a tag-reduction run removed, or would remove (#293). A document with nothing redundant on
 * it appears in NEITHER list: `documents` carries only real changes, and `skipped` only documents
 * the run could not touch at all — the caller cannot write them, or they are gone. The server does
 * not say which of the two, and the screen must not guess: the distinction would be an existence
 * oracle for other people's documents.
 */
export interface TagReductionReport {
  status: string
  /** True when nothing was modified. */
  dryRun: boolean
  /** Total tags removed, or that would be, across every document. */
  count: number
  documents: DocumentTagReduction[]
  skipped: string[]
}

/**
 * Previews or runs a tag reduction over the given documents.
 *
 * The request carries document IDs and nothing else — the server derives what is redundant itself,
 * on both passes, so a preview this screen holds can never be replayed as a removal list.
 */
export function reduceDocumentTags(documentIds: string[], dryRun: boolean) {
  const params = new URLSearchParams()
  for (const id of documentIds) params.append('documents', id)
  params.set('dryRun', String(dryRun))
  return api.post<TagReductionReport>('/tag/reduce', params)
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

/**
 * One icon in the instance's custom icon set (#287). Admin-managed, used by everybody: any user
 * may put any of these on their own tags, but only an administrator adds to or removes from the
 * set, because an upload writes a file the whole instance then loads.
 */
export interface TagIcon {
  id: string
  name: string
  mimetype: string
}

export function listTagIcons() {
  return api.get<{ icons: TagIcon[] }>('/tag/icon')
}

/** Adds a PNG or SVG to the set. ADMIN. The server decides the type from the bytes. */
export function uploadTagIcon(name: string, file: File) {
  const form = new FormData()
  form.append('name', name)
  form.append('image', file)
  return api.put<{ id: string }>('/tag/icon', form)
}

/**
 * Removes an icon from the set. ADMIN. Reports how many tags were left with no icon: the server
 * clears the reference off every tag that used it, so nothing is ever left pointing at it.
 */
export function deleteTagIcon(id: string) {
  return api.delete<{ status: string; tags: number }>(`/tag/icon/${id}`)
}
