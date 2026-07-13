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
// short and accurate to the 3.5.0 line (personal favorites, gallery view mode,
// rich-text descriptions, admin statistics dashboard).
export const HIGHLIGHT_KEYS = [
  'ui.about.highlights.favorites',
  'ui.about.highlights.gallery',
  'ui.about.highlights.rich_descriptions',
  'ui.about.highlights.admin_stats',
] as const
