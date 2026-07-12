// Curated "What's new" highlights for the About dialog. Extracted so the bullet
// set and the pinned heading version are a single source of truth shared by the
// AboutDialog component and its unit test (BL-019).
//
// The What's-New bullets are hand-curated for a SPECIFIC release, so the heading
// is pinned to that release — NOT the live server version, which drifts ahead of
// the bullets on every patch. The v{version} brand badge shows the live version.
//
// The heading tracks MAJOR.MINOR only: a patch release reuses the current minor's
// bullets and does not need to touch this file. A new minor (or major) release
// with fresh bullets bumps this to match.
export const HIGHLIGHTS_VERSION = '3.5.0'

// Each entry is an i18n key so the bullets translate. The list is intentionally
// short and accurate to the 3.5.0 line (personal favorites, gallery view mode,
// rich-text descriptions, admin statistics dashboard).
export const HIGHLIGHT_KEYS = [
  'ui.about.highlights.favorites',
  'ui.about.highlights.gallery',
  'ui.about.highlights.rich_descriptions',
  'ui.about.highlights.admin_stats',
] as const
