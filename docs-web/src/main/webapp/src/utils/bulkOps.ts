import type { DocumentListItem } from '../api/document'

/**
 * Client-side bulk operations over the existing single-document REST endpoints.
 *
 * Teedy's backend exposes NO bulk document endpoint (there is no POST
 * /document/bulk or equivalent — see DocumentResource.java), so a bulk action is
 * a fan-out of per-document calls. This module owns the two concerns that are
 * worth testing in isolation from Vue: (1) building the form params each
 * single-doc call needs, and (2) aggregating per-doc success/failure into one
 * summary the UI can report.
 */

export interface BulkResult {
  /** IDs of documents the operation succeeded on. */
  succeeded: string[]
  /** Documents the operation failed on, with the id and a short reason. */
  failed: Array<{ id: string; title: string; reason: string }>
}

/**
 * Run an async per-document operation over every item and aggregate the outcome.
 * A rejection (network error, ACL/permission failure, validation error) is
 * captured per-document — one failing document never aborts the rest.
 *
 * `onProgress` is invoked after each document settles so the caller can drive a
 * progress indicator (done / total).
 */
export async function runBulk(
  items: DocumentListItem[],
  op: (item: DocumentListItem) => Promise<unknown>,
  onProgress?: (done: number, total: number) => void,
): Promise<BulkResult> {
  const result: BulkResult = { succeeded: [], failed: [] }
  const total = items.length
  let done = 0
  for (const item of items) {
    try {
      await op(item)
      result.succeeded.push(item.id)
    } catch (err) {
      result.failed.push({ id: item.id, title: item.title, reason: describeError(err) })
    }
    done += 1
    onProgress?.(done, total)
  }
  return result
}

/** Extract a short human-readable reason from a thrown value. */
export function describeError(err: unknown): string {
  const e = err as { response?: { status?: number; data?: { message?: string; type?: string } }; message?: string }
  if (e?.response?.status === 403) return 'forbidden'
  const apiMessage = e?.response?.data?.message
  if (apiMessage) return apiMessage
  if (e?.message) return e.message
  return 'error'
}

/**
 * The /document update endpoint (POST /document/{id}) requires `title` and
 * `language` on every call and REPLACES the tag list only when a `tags` param is
 * present. These builders encode that contract so a bulk edit preserves the
 * fields it is not changing.
 */

/** Build params to add one tag to a document, preserving its existing tags. */
export function buildAddTagParams(doc: DocumentListItem, tagId: string): URLSearchParams {
  const currentIds = (doc.tags ?? []).map((tag) => tag.id)
  const nextIds = currentIds.includes(tagId) ? currentIds : [...currentIds, tagId]
  return buildTagParams(doc, nextIds)
}

/** Build params to remove one tag from a document, preserving the rest. */
export function buildRemoveTagParams(doc: DocumentListItem, tagId: string): URLSearchParams {
  const nextIds = (doc.tags ?? []).map((tag) => tag.id).filter((id) => id !== tagId)
  return buildTagParams(doc, nextIds)
}

function buildTagParams(doc: DocumentListItem, tagIds: string[]): URLSearchParams {
  const p = new URLSearchParams()
  p.set('title', doc.title)
  p.set('language', doc.language)
  if (tagIds.length === 0) {
    // The backend preserves tags on an omitted `tags` param, so an empty list is a
    // silent no-op. Removing the LAST tag must send the explicit clear-all sentinel
    // (POST /document/{id} tags_reset=true) — see the P8 partial-update contract.
    p.set('tags_reset', 'true')
  } else {
    for (const id of tagIds) p.append('tags', id)
  }
  return p
}

/**
 * Build params to set a document's language. No `tags` param is sent, so the
 * backend leaves the existing tag list untouched.
 */
export function buildLanguageParams(doc: DocumentListItem, language: string): URLSearchParams {
  const p = new URLSearchParams()
  p.set('title', doc.title)
  p.set('language', language)
  return p
}
