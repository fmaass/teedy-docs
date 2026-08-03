// Curated "What's new" highlights for the About dialog. Extracted so the bullet
// set and the pinned heading version are a single source of truth shared by the
// AboutDialog component and its unit test (BL-019).
//
// The What's-New bullets are hand-curated for a SPECIFIC release, so the heading
// is pinned to that release — NOT the live server version, which drifts ahead of
// the bullets on every patch. The v{version} brand badge shows the live version.
//
// The heading tracks MAJOR.MINOR only, and that is the ONLY part a patch release
// leaves alone: HIGHLIGHTS_VERSION stays at the minor's .0 for every 3.8.x, and a
// new minor (or major) bumps it. The BULLETS are a different matter — they describe
// the whole minor LINE and ACCUMULATE across its patches, so a patch that ships a
// user-visible feature adds its bullet here. Otherwise the people who just installed
// 3.8.2 open "What's new in 3.8" and see only what 3.8.0 brought.
export const HIGHLIGHTS_VERSION = '3.8.0'

/**
 * The MAJOR.MINOR prefix of a semantic version ("3.5.2" -> "3.5"). Returns the
 * input unchanged when it is not a dotted MAJOR.MINOR[.PATCH] string (a defensive
 * fallback for an unexpected server value). Shared by the component and its guard
 * test so the rendered heading version can never drift from what the test pins.
 */
export function minorOf(version: string): string {
  const parts = version.split('.')
  if (parts.length < 2) return version
  return `${parts[0]}.${parts[1]}`
}

/**
 * The version string the "What's new in {version}" heading DISPLAYS. Derived from
 * the CURRENT app version (major.minor), so any 3.5.x app shows "3.5" — the heading
 * can never show a patch that mismatches the running app. Falls back to the curated
 * HIGHLIGHTS_VERSION's major.minor when the live version is not yet known.
 */
export function headingVersion(currentVersion: string | null | undefined): string {
  return minorOf((currentVersion ?? '').trim() || HIGHLIGHTS_VERSION)
}

// Each entry is an i18n key so the bullets translate. The list is intentionally
// short and CURATED — not a changelog: only user-visible additions earn a line,
// bug fixes stay in CHANGELOG.md. Accurate to the 3.8 line as a whole; 3.8.0
// first: imported mail keeps its original .eml, the file preview goes fullscreen,
// the document header shows its cover thumbnail, the grid view gained sorting and
// drag reordering, a missing search index rebuilds itself at startup (and no
// longer rebuilds a healthy one), and truncated file names reveal themselves on
// hover. Then the 3.8.1/3.8.2 additions: instance branding, tag wildcards in
// search, WebP thumbnails and previews, and the scrolling fullscreen PDF.
export const HIGHLIGHT_KEYS = [
  'ui.about.highlights.email_original_attached',
  'ui.about.highlights.preview_fullscreen',
  'ui.about.highlights.cover_in_header',
  'ui.about.highlights.grid_sort_and_reorder',
  'ui.about.highlights.index_self_repair',
  'ui.about.highlights.filename_tooltips',
  'ui.about.highlights.custom_branding',
  'ui.about.highlights.tag_wildcards',
  'ui.about.highlights.webp_previews',
  'ui.about.highlights.pdf_scroll_and_gallery_open',
] as const
