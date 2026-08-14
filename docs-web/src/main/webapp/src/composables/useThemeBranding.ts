import { computed, watch, onScopeDispose } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { getTheme, type ThemeConfig } from '../api/theme'
import { queryKeys } from '../api/queryKeys'
import { applyBrandPrimary } from '../theme/primary'

// The built-in product identity. This is the name Branding OVERRIDES for the chrome, and the
// fallback the chrome shows when no instance name is configured. The About dialog imports it to
// show the ACTUAL product name regardless of Branding (#282): About is where a user identifies the
// real product + build for a bug report, so it must not be renamed by the operator's brand.
export const DEFAULT_APP_NAME = 'Teedy'
const FAVICON_ENDPOINT = '/api/theme/image/favicon'
const LOGO_ENDPOINT = '/api/theme/image/logo'
const BACKGROUND_ENDPOINT = '/api/theme/image/background'
const STYLESHEET_ENDPOINT = '/api/theme/stylesheet'
const SCRIPT_ENDPOINT = '/api/theme/script'
const STYLESHEET_LINK_ID = 'teedy-theme-stylesheet'
const SCRIPT_TAG_ID = 'teedy-theme-script'

/**
 * Apply the custom application name to the browser tab title. A blank/absent
 * name falls back to the product default so the tab never shows an empty title.
 * This is only ever called ONCE the theme query has RESOLVED — before that the
 * caller leaves the server-rendered <title> untouched so a custom-branded cold
 * load never flashes "Teedy". Exported (pure over `document`) so it can be
 * unit-tested without mounting the whole app.
 */
export function applyDocumentTitle(name: string | undefined | null, doc: Document = document): void {
  const trimmed = (name ?? '').trim()
  doc.title = trimmed.length ? trimmed : DEFAULT_APP_NAME
}

/**
 * Point the tab favicon at the theme favicon endpoint, cache-busted by a token
 * that changes when the IMAGE changes (the theme payload's favicon_version — the
 * uploaded file's last-modified stamp). This forces the browser to re-fetch past
 * ThemeResource's 15-day image cache when the favicon is replaced, even if the
 * theme name is unchanged. Reuses the single <link rel="icon"> in index.html,
 * creating one only if absent; returns whether it created the link (so the caller
 * can remove exactly what it added on unmount).
 */
export function applyFavicon(
  bust: string | number | undefined | null,
  doc: Document = document,
): { created: boolean; link: HTMLLinkElement } {
  let link = doc.querySelector<HTMLLinkElement>('link[rel~="icon"]')
  let created = false
  if (!link) {
    link = doc.createElement('link')
    link.rel = 'icon'
    doc.head.appendChild(link)
    created = true
  }
  const token = encodeURIComponent(String(bust ?? '0'))
  link.href = `${FAVICON_ENDPOINT}?v=${token}`
  return { created, link }
}

/**
 * Link the server-compiled theme stylesheet (GET /api/theme/stylesheet) into the document.
 *
 * The endpoint has existed since the theme feature shipped but nothing ever loaded it — no
 * <link> in index.html, no dynamic creation anywhere — so the admin-set navbar colour and the
 * custom CSS were both inert in the SPA. This is what makes them real.
 *
 * Injected AFTER mount rather than as a blocking <link> in index.html: first paint must not wait
 * on the theme request (#216). The accepted tradeoff is that a heavily-customised instance can
 * show base styling for one frame before the custom CSS lands.
 *
 * `?v=` carries the stylesheet_version hash, so a changed stylesheet is re-fetched and an
 * unchanged one is not.
 */
export function applyThemeStylesheet(
  version: string | undefined | null,
  doc: Document = document,
): { created: boolean; link: HTMLLinkElement } {
  let link = doc.getElementById(STYLESHEET_LINK_ID) as HTMLLinkElement | null
  let created = false
  if (!link) {
    link = doc.createElement('link')
    link.id = STYLESHEET_LINK_ID
    link.rel = 'stylesheet'
    doc.head.appendChild(link)
    created = true
  }
  const token = encodeURIComponent(String(version ?? ''))
  link.href = `${STYLESHEET_ENDPOINT}?v=${token}`
  return { created, link }
}

/**
 * Load the admin-configured custom script (GET /api/theme/script) as an EXTERNAL, same-origin
 * <script src>. Never inline, never eval: the response carries its own JavaScript media type and
 * nosniff header, and keeping it external means no inline-script allowance is needed from any
 * future document CSP.
 *
 * An EMPTY script_version means there is no script configured, so nothing is injected at all.
 * When the version changes the tag is replaced, which makes the browser fetch and run the new
 * file — note that this cannot undo what the PREVIOUS script already did to the live page; a
 * reload is the clean slate.
 *
 * Returns the element in play, or null when there is no script to load.
 */
export function applyThemeScript(
  version: string | undefined | null,
  doc: Document = document,
): HTMLScriptElement | null {
  const token = (version ?? '').trim()
  const existing = doc.getElementById(SCRIPT_TAG_ID) as HTMLScriptElement | null

  if (!token) {
    existing?.remove()
    return null
  }

  const src = `${SCRIPT_ENDPOINT}?v=${encodeURIComponent(token)}`
  if (existing) {
    if (existing.src.endsWith(src)) return existing
    existing.remove()
  }

  const script = doc.createElement('script')
  script.id = SCRIPT_TAG_ID
  script.src = src
  script.async = true
  doc.head.appendChild(script)
  return script
}

/**
 * The favicon cache-bust token for a resolved theme: prefer the image-keyed
 * favicon_version (changes when the image is replaced); fall back to the theme
 * name only for older servers that predate favicon_version, so a replaced-image
 * bust is still best-effort there.
 */
function faviconBust(theme: ThemeConfig | undefined): string | number {
  if (theme?.favicon_version !== undefined) return theme.favicon_version
  return (theme?.name ?? '').trim() || DEFAULT_APP_NAME
}

/**
 * The brand the app CHROME renders: the configured instance name, the uploaded logo, and the
 * uploaded login background — each only when the operator has actually chosen one.
 *
 * Reads the SAME query key as {@link useThemeBranding}, so every consumer — the desktop panel
 * brand, the mobile drawer brand, the About dialog — is served from ONE cache entry and ONE
 * request; adding a consumer costs no extra network call. Nothing here is awaited before mount
 * (#216/#245): until the payload lands the brand renders the product fallback and then swaps in,
 * exactly as the tab title does.
 *
 * This exists because the in-app brand used to be a hardcoded `teedy` literal repeated at three
 * sites while the uploaded logo had no consumer at all — so an operator could rename their
 * instance and still see stock Teedy everywhere but the browser tab.
 */
export function useBrand() {
  const { data: theme } = useQuery({
    queryKey: queryKeys.theme(),
    queryFn: () => getTheme(),
    staleTime: Infinity,
  })

  // Blank as well as absent falls back: the server only defaults the name when the key is MISSING,
  // so an admin who clears the field stores "" and gets "" back. The brand is never empty.
  const brandName = computed(() => (theme.value?.name ?? '').trim() || DEFAULT_APP_NAME)

  // logo_version is 0 exactly when no custom logo file exists (ThemeResource.imageVersion), and the
  // image endpoint would then serve the BUNDLED Teedy asset — which an instance that never chose a
  // logo must not silently grow. When there is a real one, that same version is the cache-bust
  // token that gets the browser past the 15-day image cache after a replacement.
  const brandLogoUrl = computed(() => {
    const version = theme.value?.logo_version ?? 0
    return version > 0 ? `${LOGO_ENDPOINT}?v=${version}` : null
  })

  // The admin-uploaded login background (#258). Same 0-means-none rule as the logo, and here it is
  // the RELEASE-SAFETY property: GET /theme/image/background always answers, falling back to a
  // bundled default, so an install that never chose a background would silently grow one on
  // upgrade if the endpoint's answer were treated as the signal. background_version is the signal.
  const brandBackgroundUrl = computed(() => {
    // Coerced and range-checked because this value is interpolated into a CSS url(): only a
    // finite positive number ever reaches the stylesheet.
    const version = Number(theme.value?.background_version ?? 0)
    return Number.isFinite(version) && version > 0 ? `${BACKGROUND_ENDPOINT}?v=${version}` : null
  })

  return { brandName, brandLogoUrl, brandBackgroundUrl }
}

/**
 * Invalidate the shared theme cache. The Branding settings screen calls this after every
 * mutation so the tab title, favicon, derived palette, stylesheet and custom script all follow
 * the change without a reload.
 */
export function useInvalidateTheme() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: queryKeys.theme() })
}

/**
 * Top-level theme branding: keep the browser tab title, favicon, brand palette,
 * custom stylesheet and custom script in sync with the server-side theme. Mounted
 * once at the app root (App.vue) — NOT in a per-route child that may not mount on
 * every route — so branding reflects the configured theme on every page, including
 * the anonymous login screen (GET /api/theme is public).
 *
 * Three correctness properties:
 * - No flash: the branding is applied only AFTER the theme query resolves. Before
 *   that the server-rendered <title>/<link rel=icon> stay as-is, so a custom-branded
 *   cold load never shows the literal "Teedy" for a beat.
 * - No mount delay: nothing here is awaited before the app mounts (#216). The
 *   stylesheet and script are injected asynchronously once the payload arrives.
 * - Clean unmount: the original title + favicon href are restored on scope dispose,
 *   and any element this composable created is removed — so tearing the app down
 *   (or hot-reloading it) leaves the document as it found it.
 */
export function useThemeBranding() {
  const { data: theme, isSuccess } = useQuery({
    queryKey: queryKeys.theme(),
    queryFn: () => getTheme(),
    staleTime: Infinity,
  })

  // Snapshot the document state BEFORE we touch it, to restore on unmount.
  const originalTitle = document.title
  const originalIconLink = document.querySelector<HTMLLinkElement>('link[rel~="icon"]')
  const originalIconHref = originalIconLink?.getAttribute('href') ?? null
  let createdIconLink: HTMLLinkElement | null = null

  watch(
    [
      () => isSuccess.value,
      () => theme.value?.name,
      () => theme.value?.favicon_version,
      () => theme.value?.main_color,
      () => theme.value?.stylesheet_version,
      () => theme.value?.script_version,
    ],
    ([resolved]) => {
      // Do NOT apply anything until the theme has actually resolved — this is what
      // prevents the pre-resolve "Teedy" flash on a custom-branded cold load.
      if (!resolved) return
      applyDocumentTitle(theme.value?.name)
      const { created, link } = applyFavicon(faviconBust(theme.value))
      if (created) createdIconLink = link
      applyBrandPrimary(theme.value?.main_color)
      applyThemeStylesheet(theme.value?.stylesheet_version)
      applyThemeScript(theme.value?.script_version)
    },
    { immediate: true },
  )

  onScopeDispose(() => {
    document.title = originalTitle
    if (createdIconLink) {
      // We added the icon link; remove exactly what we created.
      createdIconLink.remove()
    } else if (originalIconLink) {
      // We reused an existing link; restore its original href (or drop the attr
      // if there wasn't one).
      if (originalIconHref === null) originalIconLink.removeAttribute('href')
      else originalIconLink.setAttribute('href', originalIconHref)
    }
    document.getElementById(STYLESHEET_LINK_ID)?.remove()
    document.getElementById(SCRIPT_TAG_ID)?.remove()
  })

  return { theme }
}
