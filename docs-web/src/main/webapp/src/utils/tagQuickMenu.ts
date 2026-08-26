// Compact right-click "add tags" menu logic (#71).
//
// The gallery/list context menu used to render EVERY tag as a flat menu, which
// overflowed and got cut off on instances with many tags. The redesigned ADD flow
// is a compact popover: a name search over all assignable tags, plus a row of the
// most-used tags as quick-add chips. These two pure selectors drive that UI and are
// unit-tested in isolation from the component.

import { type Tag } from '../api/tag'

/** Default number of quick-add chips shown in the compact add popover. */
export const QUICK_ADD_TAG_LIMIT = 5

/**
 * The tags a document can still be given: every tag NOT already assigned to it.
 * Pure; order preserves the input tag order.
 *
 * @param allTags        All tags known to the app.
 * @param assignedTagIds Ids of tags already on the document.
 */
export function assignableTags(allTags: Tag[], assignedTagIds: Set<string>): Tag[] {
  return allTags.filter((tag) => !assignedTagIds.has(tag.id))
}

// The name filter that used to live here moved to utils/tagSynonyms.ts as `matchTagsByName`
// (#280): a query now reaches a tag by its name OR by one of its synonyms, and the caller needs
// to know WHICH, so the same matcher serves this menu and the document editor's TagPicker.

/**
 * The top-N most-used assignable tags, for quick-add chips. Ranks the assignable
 * tags by their usage count (from the tag-stats map the app already fetches),
 * descending, breaking ties by name for a stable order, and takes the first `limit`.
 *
 * Fallback: a tag absent from `counts` (never used) contributes 0, so when NO usage
 * data is available the ranking degrades to the first `limit` assignable tags in
 * name order — never empty when assignable tags exist. Pure.
 *
 * @param tags   Assignable tags (already excludes assigned ones).
 * @param counts Usage counts keyed by tag id (tf.tagCounts / getTagStats).
 * @param limit  Max chips to return (default QUICK_ADD_TAG_LIMIT).
 */
export function topUsedTags(
  tags: Tag[],
  counts: Record<string, number>,
  limit: number = QUICK_ADD_TAG_LIMIT,
): Tag[] {
  return [...tags]
    .sort((a, b) => {
      const ca = counts[a.id] ?? 0
      const cb = counts[b.id] ?? 0
      if (cb !== ca) return cb - ca
      return a.name.localeCompare(b.name)
    })
    .slice(0, Math.max(0, limit))
}
