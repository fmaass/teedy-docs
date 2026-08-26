// Searching tags by name OR synonym (#280).
//
// A tag can carry alternative names, and typing one of them has to offer the TAG — "Rechnung"
// offers Invoice, and the option says so. That is one rule, and it is used by both tag inputs:
// the document editor's TagPicker and the document list's TagQuickMenu. It lives here rather
// than in either of them because a matcher that existed twice would drift, and the two inputs
// already drifted once before they were unified (#182).
//
// This replaces `filterTagsByName` in utils/tagQuickMenu.ts and the private `foldForSearch`
// filter inside TagPicker.vue, which were the two copies.

import { type Tag } from '../api/tag'

/** A tag the query reached, and the name it reached it by. */
export interface TagNameMatch {
  tag: Tag
  /**
   * The synonym that matched, or null when the tag's OWN name did. Null is what tells a caller
   * to render the plain tag name; a value is what it puts in the "(via …)" label.
   */
  via: string | null
}

/**
 * Accent-folded, lower-cased comparison key.
 *
 * <p>Taken from TagPicker, where it replaced PrimeVue's built-in `contains` filter: that folded
 * accents through a Latin-1/Latin-Extended-A lookup table, so a German user typing "uber" found
 * "Über". Canonical decomposition reproduces it for every accented letter that decomposes, which
 * covers the diacritics of all twelve shipped locales. It does NOT fold the stroked/ligature
 * letters that have no decomposition (Ø, Ł, Đ, Æ, Œ) — those still match on themselves, they
 * just no longer match their unstroked spelling.
 */
export function foldForSearch(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLocaleLowerCase()
}

/**
 * The tags a search box query reaches, in the order of the input list, each once.
 *
 * A blank query reaches every tag (the inputs show the full list to scroll). Otherwise the query
 * is an accent-folded, case-insensitive SUBSTRING of one of the tag's names — which is what the
 * two inputs already did with tag names, extended to synonyms.
 *
 * The tag's own name is tested first, so a tag whose NAME matches is never labelled as reached
 * "via" a synonym it also happens to match; and the first synonym that matches wins, so the
 * label names a real, single reason.
 *
 * @param tags Candidate tags (the caller narrows them first — the quick menu passes only the
 *             tags not already on the document)
 * @param query Raw search text
 */
export function matchTagsByName(tags: Tag[], query: string): TagNameMatch[] {
  const needle = foldForSearch(query.trim())
  if (!needle) {
    return tags.map((tag) => ({ tag, via: null }))
  }
  const matches: TagNameMatch[] = []
  for (const tag of tags) {
    if (foldForSearch(tag.name).includes(needle)) {
      matches.push({ tag, via: null })
      continue
    }
    const via = (tag.synonyms ?? []).find((synonym) => foldForSearch(synonym).includes(needle))
    if (via !== undefined) {
      matches.push({ tag, via })
    }
  }
  return matches
}
