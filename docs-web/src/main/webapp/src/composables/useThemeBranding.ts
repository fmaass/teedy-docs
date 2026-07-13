import { watch, onScopeDispose } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { getTheme, type ThemeConfig } from '../api/theme'
import { queryKeys } from '../api/queryKeys'

const DEFAULT_APP_NAME = 'Teedy'
const FAVICON_ENDPOINT = '/api/theme/image/favicon'

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
 * Top-level theme branding: keep the browser tab title and favicon in sync with
 * the server-side theme. Mounted once at the app root (App.vue) — NOT in a
 * per-route child that may not mount on every route — so the title reflects the
 * configured name on every page, including the anonymous login screen (GET
 * /api/theme is public).
 *
 * Two correctness properties:
 * - No flash: the branding is applied only AFTER the theme query resolves. Before
 *   that the server-rendered <title>/<link rel=icon> stay as-is, so a custom-branded
 *   cold load never shows the literal "Teedy" for a beat.
 * - Clean unmount: the original title + favicon href are restored on scope dispose,
 *   and any icon link this composable created is removed — so tearing the app down
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
    [() => isSuccess.value, () => theme.value?.name, () => theme.value?.favicon_version],
    ([resolved]) => {
      // Do NOT apply anything until the theme has actually resolved — this is what
      // prevents the pre-resolve "Teedy" flash on a custom-branded cold load.
      if (!resolved) return
      applyDocumentTitle(theme.value?.name)
      const { created, link } = applyFavicon(faviconBust(theme.value))
      if (created) createdIconLink = link
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
  })

  return { theme }
}
