// Curated "What's new" highlights for the About dialog. Extracted so the bullet
// set and the pinned heading version are a single source of truth shared by the
// AboutDialog component and its unit test (BL-019).
//
// The What's-New bullets are hand-curated for a SPECIFIC release, so the heading
// is pinned to that release — NOT the live server version, which drifts ahead of
// the bullets on every patch. The v{version} brand badge shows the live version.
export const HIGHLIGHTS_VERSION = '3.4.1'

// Each entry is an i18n key so the bullets translate. The list is intentionally
// short and accurate to the 3.4.0 line (clickable tag chips, saved filters,
// document relations, PDF/image rotation, OIDC + quota admin UIs, /apidoc + docs).
export const HIGHLIGHT_KEYS = [
  'ui.about.highlights.tag_chips',
  'ui.about.highlights.saved_filters',
  'ui.about.highlights.relations',
  'ui.about.highlights.rotation',
  'ui.about.highlights.admin_ui',
  'ui.about.highlights.apidoc_docs',
] as const
