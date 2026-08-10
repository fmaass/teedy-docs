import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref, effectScope, nextTick } from 'vue'

// --- Dependency mocks (NOT the unit under test) ---
// useThemeBranding wraps vue-query + the /theme API. We mock useQuery so we can
// drive the theme name + resolution state reactively and assert the composable
// applies it to the document title + favicon. The pure appliers are tested
// directly against jsdom.

const useQueryMock = vi.fn()
const invalidateQueriesMock = vi.hoisted(() => vi.fn())
vi.mock('@tanstack/vue-query', () => ({
  useQuery: (opts: unknown) => useQueryMock(opts),
  useQueryClient: () => ({ invalidateQueries: invalidateQueriesMock }),
}))
vi.mock('../api/theme', () => ({ getTheme: vi.fn() }))
// The palette applier drives PrimeVue's runtime theme service; its own contract is covered in
// theme/primary.spec.ts. Here we only assert that the composable hands it the configured colour.
const applyBrandPrimaryMock = vi.hoisted(() => vi.fn())
vi.mock('../theme/primary', () => ({ applyBrandPrimary: applyBrandPrimaryMock }))

import {
  applyDocumentTitle,
  applyFavicon,
  applyThemeStylesheet,
  applyThemeScript,
  useBrand,
  useInvalidateTheme,
  useThemeBranding,
} from './useThemeBranding'
import { queryKeys } from '../api/queryKeys'

// useThemeBranding registers onScopeDispose, so it must run inside an effect scope
// (as it does inside App.vue's setup). This helper mirrors that so the composable's
// cleanup is real and no "no active effect scope" warning is emitted.
function inScope<T>(fn: () => T): { result: T; scope: ReturnType<typeof effectScope> } {
  const scope = effectScope()
  const result = scope.run(fn)!
  return { result, scope }
}

beforeEach(() => {
  useQueryMock.mockReset()
  applyBrandPrimaryMock.mockReset()
  invalidateQueriesMock.mockReset()
  document.title = ''
  document.head.querySelectorAll('link[rel~="icon"]').forEach((n) => n.remove())
  document.getElementById('teedy-theme-stylesheet')?.remove()
  document.getElementById('teedy-theme-script')?.remove()
})

describe('applyDocumentTitle', () => {
  it('sets the tab title to the configured theme name', () => {
    applyDocumentTitle('Acme Docs')
    expect(document.title).toBe('Acme Docs')
  })

  it('falls back to Teedy for a blank/absent name', () => {
    applyDocumentTitle('   ')
    expect(document.title).toBe('Teedy')
    applyDocumentTitle(null)
    expect(document.title).toBe('Teedy')
  })
})

describe('applyFavicon', () => {
  it('points the icon link at the favicon endpoint, cache-busted by the given token', () => {
    const { created } = applyFavicon(1720000000000)
    const link = document.querySelector<HTMLLinkElement>('link[rel~="icon"]')!
    expect(link).not.toBeNull()
    expect(created).toBe(true)
    expect(link.getAttribute('href')).toBe('/api/theme/image/favicon?v=1720000000000')
  })

  it('reuses the single existing icon link (does not add a second) and reports created=false', () => {
    const existing = document.createElement('link')
    existing.rel = 'icon'
    document.head.appendChild(existing)
    const first = applyFavicon('one')
    const second = applyFavicon('two')
    expect(first.created).toBe(false)
    expect(second.created).toBe(false)
    expect(document.querySelectorAll('link[rel~="icon"]').length).toBe(1)
    expect(document.querySelector<HTMLLinkElement>('link[rel~="icon"]')!.getAttribute('href'))
      .toBe('/api/theme/image/favicon?v=two')
  })
})

describe('applyThemeStylesheet', () => {
  it('links the compiled theme stylesheet, cache-busted by the stylesheet version', () => {
    const { created, link } = applyThemeStylesheet('abc123')
    expect(created).toBe(true)
    expect(link.rel).toBe('stylesheet')
    expect(link.getAttribute('href')).toBe('/api/theme/stylesheet?v=abc123')
    expect(document.querySelectorAll('#teedy-theme-stylesheet').length).toBe(1)
  })

  it('reuses the one link it owns and re-points it when the version changes', () => {
    applyThemeStylesheet('v1')
    const { created } = applyThemeStylesheet('v2')
    expect(created).toBe(false)
    expect(document.querySelectorAll('#teedy-theme-stylesheet').length).toBe(1)
    expect(document.getElementById('teedy-theme-stylesheet')!.getAttribute('href'))
      .toBe('/api/theme/stylesheet?v=v2')
  })
})

describe('applyThemeScript', () => {
  it('injects NOTHING when no custom script is configured (empty version)', () => {
    expect(applyThemeScript('')).toBeNull()
    expect(applyThemeScript(undefined)).toBeNull()
    expect(document.querySelectorAll('#teedy-theme-script').length).toBe(0)
  })

  it('loads the script as an EXTERNAL same-origin src (never inline)', () => {
    const script = applyThemeScript('s1')!
    expect(script).not.toBeNull()
    expect(script.getAttribute('src')).toBe('/api/theme/script?v=s1')
    // Nothing is ever executed from a string: no inline body, no eval.
    expect(script.textContent).toBe('')
    expect(document.querySelectorAll('#teedy-theme-script').length).toBe(1)
  })

  it('keeps a single tag across an unchanged version and replaces it on a change', () => {
    const first = applyThemeScript('s1')
    expect(applyThemeScript('s1')).toBe(first)
    applyThemeScript('s2')
    expect(document.querySelectorAll('#teedy-theme-script').length).toBe(1)
    expect(document.getElementById('teedy-theme-script')!.getAttribute('src'))
      .toBe('/api/theme/script?v=s2')
  })

  it('removes the tag when the admin deletes the script', () => {
    applyThemeScript('s1')
    expect(applyThemeScript('')).toBeNull()
    expect(document.querySelectorAll('#teedy-theme-script').length).toBe(0)
  })
})

describe('useInvalidateTheme', () => {
  it('invalidates the shared theme key so branding follows a mutation without a reload', () => {
    useInvalidateTheme()()
    expect(invalidateQueriesMock).toHaveBeenCalledWith({ queryKey: queryKeys.theme() })
  })
})

describe('useThemeBranding', () => {
  it('queries under the shared theme key with staleTime Infinity', () => {
    useQueryMock.mockReturnValue({ data: ref(undefined), isSuccess: ref(false) })
    inScope(() => useThemeBranding())
    expect(useQueryMock).toHaveBeenCalledTimes(1)
    const opts = useQueryMock.mock.calls[0][0]
    expect(opts.queryKey).toEqual(queryKeys.theme())
    expect(opts.staleTime).toBe(Infinity)
  })

  it('does NOT touch the title before the theme query resolves (no "Teedy" flash)', () => {
    document.title = 'Custom Branded Co'
    // Query not yet resolved: data undefined, isSuccess false.
    useQueryMock.mockReturnValue({ data: ref(undefined), isSuccess: ref(false) })
    inScope(() => useThemeBranding())
    // The server-rendered title is left untouched — no flash to the literal default.
    expect(document.title).toBe('Custom Branded Co')
  })

  it('applies the name + favicon only once the query resolves, and reacts to a name change', async () => {
    const theme = ref<{ name: string; favicon_version?: number } | undefined>(undefined)
    const isSuccess = ref(false)
    useQueryMock.mockReturnValue({ data: theme, isSuccess })
    inScope(() => useThemeBranding())
    // Still unresolved — nothing applied yet.
    expect(document.title).toBe('')

    // Resolution arrives.
    theme.value = { name: 'First Name', favicon_version: 111 }
    isSuccess.value = true
    await nextTick()
    expect(document.title).toBe('First Name')
    expect(document.querySelector<HTMLLinkElement>('link[rel~="icon"]')!.getAttribute('href'))
      .toBe('/api/theme/image/favicon?v=111')

    // A live theme rename re-applies.
    theme.value = { name: 'Renamed', favicon_version: 111 }
    await nextTick()
    expect(document.title).toBe('Renamed')
  })

  it('cache-busts the favicon on favicon_version — a NEW image (unchanged name) re-fetches', async () => {
    const theme = ref<{ name: string; favicon_version?: number } | undefined>({
      name: 'Same Name',
      favicon_version: 100,
    })
    const isSuccess = ref(true)
    useQueryMock.mockReturnValue({ data: theme, isSuccess })
    inScope(() => useThemeBranding())
    await nextTick()
    expect(document.querySelector<HTMLLinkElement>('link[rel~="icon"]')!.getAttribute('href'))
      .toBe('/api/theme/image/favicon?v=100')

    // Replace the favicon image WITHOUT renaming the theme: favicon_version bumps,
    // so the URL changes and the browser is forced past the 15-day image cache.
    theme.value = { name: 'Same Name', favicon_version: 200 }
    await nextTick()
    expect(document.querySelector<HTMLLinkElement>('link[rel~="icon"]')!.getAttribute('href'))
      .toBe('/api/theme/image/favicon?v=200')
  })

  it('applies the brand palette, stylesheet and script once the theme resolves', async () => {
    const theme = ref<Record<string, unknown> | undefined>({
      name: 'Branded',
      favicon_version: 7,
      main_color: '#ff5722',
      stylesheet_version: 'css1',
      script_version: 'js1',
    })
    const isSuccess = ref(true)
    useQueryMock.mockReturnValue({ data: theme, isSuccess })
    inScope(() => useThemeBranding())
    await nextTick()

    expect(applyBrandPrimaryMock).toHaveBeenCalledWith('#ff5722')
    expect(document.getElementById('teedy-theme-stylesheet')!.getAttribute('href'))
      .toBe('/api/theme/stylesheet?v=css1')
    expect(document.getElementById('teedy-theme-script')!.getAttribute('src'))
      .toBe('/api/theme/script?v=js1')

    // A mutation-driven refetch re-applies without a reload.
    theme.value = {
      name: 'Branded',
      favicon_version: 7,
      main_color: '#336699',
      stylesheet_version: 'css2',
      script_version: '',
    }
    await nextTick()
    expect(applyBrandPrimaryMock).toHaveBeenLastCalledWith('#336699')
    expect(document.getElementById('teedy-theme-stylesheet')!.getAttribute('href'))
      .toBe('/api/theme/stylesheet?v=css2')
    expect(document.getElementById('teedy-theme-script')).toBeNull()
  })

  it('injects NOTHING before the theme resolves — mount must not wait on the theme request', () => {
    useQueryMock.mockReturnValue({ data: ref(undefined), isSuccess: ref(false) })
    inScope(() => useThemeBranding())
    expect(document.getElementById('teedy-theme-stylesheet')).toBeNull()
    expect(document.getElementById('teedy-theme-script')).toBeNull()
    expect(applyBrandPrimaryMock).not.toHaveBeenCalled()
  })

  it('removes the stylesheet link and script tag it injected on unmount', async () => {
    const theme = ref<Record<string, unknown> | undefined>({
      name: 'Branded',
      favicon_version: 1,
      stylesheet_version: 'css1',
      script_version: 'js1',
    })
    useQueryMock.mockReturnValue({ data: theme, isSuccess: ref(true) })
    const scope = effectScope()
    scope.run(() => useThemeBranding())
    await nextTick()
    expect(document.getElementById('teedy-theme-stylesheet')).not.toBeNull()
    expect(document.getElementById('teedy-theme-script')).not.toBeNull()

    scope.stop()
    expect(document.getElementById('teedy-theme-stylesheet')).toBeNull()
    expect(document.getElementById('teedy-theme-script')).toBeNull()
  })

  it('restores the original title and removes the created icon link on unmount', async () => {
    document.title = 'Original Title'
    // No pre-existing icon link — the composable will CREATE one.
    const theme = ref<{ name: string; favicon_version?: number } | undefined>({
      name: 'Branded',
      favicon_version: 5,
    })
    const isSuccess = ref(true)
    useQueryMock.mockReturnValue({ data: theme, isSuccess })

    const scope = effectScope()
    scope.run(() => useThemeBranding())
    await nextTick()
    expect(document.title).toBe('Branded')
    expect(document.querySelectorAll('link[rel~="icon"]').length).toBe(1)

    // Unmount (scope dispose): the original title returns and the created link is gone.
    scope.stop()
    expect(document.title).toBe('Original Title')
    expect(document.querySelectorAll('link[rel~="icon"]').length).toBe(0)
  })

  it('restores a pre-existing icon link\'s original href on unmount (does not remove it)', async () => {
    document.title = 'Orig'
    const pre = document.createElement('link')
    pre.rel = 'icon'
    pre.setAttribute('href', '/favicon.ico')
    document.head.appendChild(pre)

    const theme = ref<{ name: string; favicon_version?: number } | undefined>({
      name: 'Branded',
      favicon_version: 9,
    })
    const isSuccess = ref(true)
    useQueryMock.mockReturnValue({ data: theme, isSuccess })

    const scope = effectScope()
    scope.run(() => useThemeBranding())
    await nextTick()
    // While mounted the reused link points at the theme favicon.
    expect(pre.getAttribute('href')).toBe('/api/theme/image/favicon?v=9')

    scope.stop()
    // The pre-existing link is KEPT (not removed) and its original href restored.
    expect(document.querySelectorAll('link[rel~="icon"]').length).toBe(1)
    expect(pre.getAttribute('href')).toBe('/favicon.ico')
    expect(document.title).toBe('Orig')
  })
})

// --- useBrand: the brand the APP CHROME renders -------------------------------
// The tab title had a display half from day one; the brand INSIDE the app did not — the
// panel/drawer and the About dialog each carried their own hardcoded "teedy" literal, and the
// uploaded logo had no consumer at all. useBrand is the single derivation all three now read,
// so the fallback rules live in one tested place.
describe('useBrand', () => {
  // #255 (advisory 2): this asserts the query CONFIGURATION — the shared key + infinite
  // staleTime — not network deduplication itself. That config is precisely what lets vue-query
  // serve useBrand and useThemeBranding from one cache entry and one request, but the single
  // fetch is TanStack's same-key guarantee (a real QueryClient with a fetch spy would be needed
  // to observe the request count, and this file mocks useQuery). The name is worded to match
  // what is verified rather than overstating it.
  it('configures the shared theme query key + infinite staleTime (so vue-query serves both consumers from one cache entry)', () => {
    useQueryMock.mockReturnValue({ data: ref(undefined) })
    useBrand()
    expect(useQueryMock).toHaveBeenCalledTimes(1)
    const opts = useQueryMock.mock.calls[0][0]
    expect(opts.queryKey).toEqual(queryKeys.theme())
    expect(opts.staleTime).toBe(Infinity)
  })

  it('renders the configured application name', () => {
    useQueryMock.mockReturnValue({ data: ref({ name: 'Contoso Archive', logo_version: 0 }) })
    expect(useBrand().brandName.value).toBe('Contoso Archive')
  })

  it('falls back to Teedy for an absent, empty or blank name — never an empty brand', () => {
    // Before the query resolves there is no payload at all: the brand shows the fallback for a
    // frame rather than blanking the panel (nothing is awaited before mount, #216/#245).
    useQueryMock.mockReturnValue({ data: ref(undefined) })
    expect(useBrand().brandName.value).toBe('Teedy')

    // An admin who CLEARS the name stores "", which the server returns verbatim.
    useQueryMock.mockReturnValue({ data: ref({ name: '' }) })
    expect(useBrand().brandName.value).toBe('Teedy')

    useQueryMock.mockReturnValue({ data: ref({ name: '   ' }) })
    expect(useBrand().brandName.value).toBe('Teedy')
  })

  it('trims a padded name rather than rendering the padding', () => {
    useQueryMock.mockReturnValue({ data: ref({ name: '  Acme Docs  ' }) })
    expect(useBrand().brandName.value).toBe('Acme Docs')
  })

  it('renders NO logo until one has actually been uploaded', () => {
    // logo_version is 0 exactly when no custom logo file exists (ThemeResource.imageVersion).
    // The bundled default is a Teedy asset — rendering it would give every unbranded instance a
    // logo it never chose.
    useQueryMock.mockReturnValue({ data: ref({ name: 'Acme', logo_version: 0 }) })
    expect(useBrand().brandLogoUrl.value).toBeNull()

    // A server older than the logo_version field: still no logo rather than a guess.
    useQueryMock.mockReturnValue({ data: ref({ name: 'Acme' }) })
    expect(useBrand().brandLogoUrl.value).toBeNull()

    // Unresolved query.
    useQueryMock.mockReturnValue({ data: ref(undefined) })
    expect(useBrand().brandLogoUrl.value).toBeNull()
  })

  it('points at the logo endpoint with the image version as the cache-bust token', () => {
    useQueryMock.mockReturnValue({ data: ref({ name: 'Acme', logo_version: 1754212345678 }) })
    expect(useBrand().brandLogoUrl.value).toBe('/api/theme/image/logo?v=1754212345678')
  })

  // #258: the uploaded background had no consumer at all — the Branding screen previewed it and
  // nothing rendered it. It is now the login page background, which makes the uploaded-vs-bundled
  // distinction load-bearing: GET /theme/image/background ALWAYS returns bytes (it falls back to a
  // bundled default), so "the endpoint answers" is NOT the test. background_version is 0 exactly
  // when no uploaded file exists (ThemeResource.imageVersion), and that is the test.
  it('renders NO background until an admin has actually uploaded one', () => {
    // The bundled default is being served here. Rendering it would give every existing install a
    // login background it never chose — a visible regression on upgrade.
    useQueryMock.mockReturnValue({ data: ref({ name: 'Acme', background_version: 0 }) })
    expect(useBrand().brandBackgroundUrl.value).toBeNull()

    // A server older than the background_version field: still nothing rather than a guess.
    useQueryMock.mockReturnValue({ data: ref({ name: 'Acme' }) })
    expect(useBrand().brandBackgroundUrl.value).toBeNull()

    // Unresolved query.
    useQueryMock.mockReturnValue({ data: ref(undefined) })
    expect(useBrand().brandBackgroundUrl.value).toBeNull()
  })

  it('points at the background endpoint with the image version as the cache-bust token', () => {
    useQueryMock.mockReturnValue({ data: ref({ name: 'Acme', background_version: 1754212345678 }) })
    expect(useBrand().brandBackgroundUrl.value).toBe('/api/theme/image/background?v=1754212345678')
  })

  it('follows an uploaded, replaced and RESET background without a reload', async () => {
    const theme = ref<{ name: string; background_version?: number } | undefined>({
      name: 'Acme',
      background_version: 0,
    })
    useQueryMock.mockReturnValue({ data: theme })
    const { brandBackgroundUrl } = useBrand()
    expect(brandBackgroundUrl.value).toBeNull()

    // Upload: the Branding screen invalidates the shared key and the refetch lands here.
    theme.value = { name: 'Acme', background_version: 1754212345678 }
    await nextTick()
    expect(brandBackgroundUrl.value).toBe('/api/theme/image/background?v=1754212345678')

    // Replacing the image bumps the token past the 15-day image cache.
    theme.value = { name: 'Acme', background_version: 1754299999999 }
    await nextTick()
    expect(brandBackgroundUrl.value).toBe('/api/theme/image/background?v=1754299999999')

    // Reset deletes the file, so the server reports 0 again and the login page goes back to its
    // plain surface — the bundled default must NOT leak in through the reset path.
    theme.value = { name: 'Acme', background_version: 0 }
    await nextTick()
    expect(brandBackgroundUrl.value).toBeNull()
  })

  it('follows a live rename and a replaced logo without a reload', async () => {
    const theme = ref<{ name: string; logo_version?: number } | undefined>({
      name: 'Before',
      logo_version: 0,
    })
    useQueryMock.mockReturnValue({ data: theme })
    const { brandName, brandLogoUrl } = useBrand()
    expect(brandName.value).toBe('Before')
    expect(brandLogoUrl.value).toBeNull()

    // The Branding screen invalidates the shared key after every mutation; the refetch lands here.
    theme.value = { name: 'After', logo_version: 42 }
    await nextTick()
    expect(brandName.value).toBe('After')
    expect(brandLogoUrl.value).toBe('/api/theme/image/logo?v=42')

    // Replacing the image again bumps the token, so the browser refetches past the 15-day cache.
    theme.value = { name: 'After', logo_version: 99 }
    await nextTick()
    expect(brandLogoUrl.value).toBe('/api/theme/image/logo?v=99')
  })
})
