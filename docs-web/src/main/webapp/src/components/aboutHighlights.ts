// Curated "What's new" highlights for the About dialog. Extracted so the bullet
// set and the pinned heading version are a single source of truth shared by the
// AboutDialog component and its unit test (BL-019).
//
// The What's-New bullets are hand-curated for a SPECIFIC release, so the heading
// is pinned to that release — NOT the live server version, which drifts ahead of
// the bullets on every patch. The v{version} brand badge shows the live version.
export const HIGHLIGHTS_VERSION = '3.1.0'

// Each entry is an i18n key so the bullets translate. The list is intentionally
// short and accurate to the 3.1.0 line (LDAP reinstated + hardened, bulk ops,
// camera capture, new collaboration UIs, file version history).
export const HIGHLIGHT_KEYS = [
  'ui.about.highlights.retirements',
  'ui.about.highlights.ldap',
  'ui.about.highlights.bulk_ops',
  'ui.about.highlights.capture',
  'ui.about.highlights.new_ui',
] as const
