import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref, effectScope, nextTick } from 'vue'

// --- Dependency mocks (NOT the unit under test) ---
// useThemeBranding wraps vue-query + the /theme API. We mock useQuery so we can
// drive the theme name + resolution state reactively and assert the composable
// applies it to the document title + favicon. The pure appliers are tested
// directly against jsdom.

const useQueryMock = vi.fn()
vi.mock('@tanstack/vue-query', () => ({
  useQuery: (opts: unknown) => useQueryMock(opts),
}))
vi.mock('../api/theme', () => ({ getTheme: vi.fn() }))

import {
  applyDocumentTitle,
  applyFavicon,
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
  document.title = ''
  document.head.querySelectorAll('link[rel~="icon"]').forEach((n) => n.remove())
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
