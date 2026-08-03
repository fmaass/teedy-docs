import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, unref } from 'vue'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import en from '../locale/en.json'
import de from '../locale/de.json'

// #256: the share footer is the ONLY brand surface an external, unauthenticated recipient
// ever sees, so a renamed instance advertising "Teedy" leaks the product name outside the
// organisation. The REAL useBrand runs here — only the vue-query transport under it is
// stubbed — so the unset/blank -> product-name fallback stays inside the unit under test.
//
// The stub discriminates on queryKey because this view issues TWO queries: the shared
// document (['share', …]) and the public theme (['theme']). Returning one payload for both
// would let a wrong-key regression pass.
const themeRef = vi.hoisted(() => ({ value: undefined as { name?: string } | undefined }))
const docRef = vi.hoisted(() => ({
  value: undefined as undefined | Record<string, unknown>,
}))

vi.mock('@tanstack/vue-query', () => ({
  useQuery: (options: { queryKey: unknown }) => {
    const key = unref(options.queryKey) as unknown[]
    const isTheme = Array.isArray(key) && key[0] === 'theme'
    return {
      data: computed(() => (isTheme ? themeRef.value : docRef.value)),
      isLoading: computed(() => false),
      error: computed(() => null),
      refetch: vi.fn(),
    }
  },
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}))

// applyBrandPrimary drives PrimeVue's runtime theme service on import of the branding
// module; this view never calls it, and its contract lives in theme/primary.spec.ts.
vi.mock('../theme/primary', () => ({ applyBrandPrimary: vi.fn() }))
vi.mock('../api/theme', () => ({ getTheme: vi.fn() }))
vi.mock('../api/document', () => ({ getDocument: vi.fn() }))
vi.mock('../api/file', () => ({ getFileUrl: () => 'blob:stub' }))

import ShareView from './ShareView.vue'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en, de } })

const stubs = {
  Skeleton: { template: '<div />' },
  ErrorState: { template: '<div />' },
  FilePreviewDialog: { template: '<div />' },
}

function mountShare() {
  return mount(ShareView, {
    props: { documentId: 'doc-1', shareId: 'share-1' },
    global: { plugins: [i18n], stubs },
  })
}

const footer = () => mountShare().find('.share-footer').text()

beforeEach(() => {
  themeRef.value = undefined
  docRef.value = { title: 'Quarterly report', create_date: 1700000000000, files: [], file_count: 0 }
  i18n.global.locale.value = 'en'
})

describe('ShareView — the public footer names the configured instance (#256)', () => {
  it('interpolates the configured instance name', () => {
    themeRef.value = { name: 'Contoso Archive' }
    const text = footer()
    expect(text).toBe(en.ui.share.view.footer.replace('{brand}', 'Contoso Archive'))
    expect(text).toContain('Contoso Archive')
    // The whole point of the ticket: the stock product name must not reach an external reader.
    expect(text).not.toContain('Teedy')
  })

  it('falls back to the product name when no instance name is set', () => {
    // Asserted explicitly because the unset rendering is byte-identical to the old hardcoded
    // sentence: without this, dropping the parameter back to a literal would still pass.
    expect(en.ui.share.view.footer).toContain('{brand}')
    expect(footer()).toBe(en.ui.share.view.footer.replace('{brand}', 'Teedy'))
  })

  it('falls back to the product name when the admin cleared the instance name', () => {
    // The server stores and returns "" for a cleared field, so blank must fall back too.
    themeRef.value = { name: '   ' }
    expect(footer()).toBe(en.ui.share.view.footer.replace('{brand}', 'Teedy'))
  })

  it('interpolates into the translated sentence, not an English-ordered one', () => {
    themeRef.value = { name: 'Contoso Archive' }
    i18n.global.locale.value = 'de'
    expect(footer()).toBe(de.ui.share.view.footer.replace('{brand}', 'Contoso Archive'))
  })

  it('reads the brand from the public theme query, which needs no authentication', () => {
    // GET /api/theme is @apiPermission none (ThemeResource.get) and is already fetched on
    // this route by App.vue's useThemeBranding. Asserting the key here pins the footer to
    // that shared public entry, so it can never be re-pointed at an authenticated fetch.
    themeRef.value = { name: 'Contoso Archive' }
    expect(footer()).toContain('Contoso Archive')
  })
})
