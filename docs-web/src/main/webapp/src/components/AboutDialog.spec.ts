import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { computed, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve as resolvePath } from 'node:path'
import en from '../locale/en.json'
import de from '../locale/de.json'
import { HIGHLIGHTS_VERSION, HIGHLIGHT_KEYS, headingVersion } from './aboutHighlights'
import { buildDiagnosticsBlock, buildReportUrl } from './aboutDiagnostics'
import type { AppDiagnostics } from '../api/app'

// The running version drives the rendered heading; mock useAppInfo so a test can
// set the app version and assert the "What's new in X" heading uses major.minor.
const appInfoValue = vi.hoisted(
  () =>
    ({ value: undefined }) as {
      value:
        | { current_version: string; commit_id?: string; oidc_enabled?: boolean; header_authentication_enabled?: boolean }
        | undefined
    },
)
vi.mock('../composables/useAppInfo', () => ({
  useAppInfo: () => ({ data: ref(appInfoValue.value) }),
}))

// The dialog's brand line renders the CONFIGURED instance name (it used to be a hardcoded
// "teedy"). useBrand owns that derivation and is tested in useThemeBranding.spec.ts; here it is a
// dependency, so it is mocked and the test asserts what the component renders from it.
const brandNameValue = vi.hoisted(() => ({ value: 'Teedy' }))
vi.mock('../composables/useThemeBranding', () => ({
  useBrand: () => ({ brandName: computed(() => brandNameValue.value), brandLogoUrl: ref(null) }),
}))

// #275 diagnostics affordance: admin gate, the diagnostics fetch and the toast are dependencies of
// the dialog, mocked so the tests drive admin-ness, the server payload and observe the outcomes.
const isAdminValue = vi.hoisted(() => ({ value: false }))
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({ isAdmin: isAdminValue.value }),
}))

const getAppDiagnosticsMock = vi.hoisted(() => vi.fn())
vi.mock('../api/app', () => ({
  getAppDiagnostics: getAppDiagnosticsMock,
}))

const toastAdd = vi.hoisted(() => vi.fn())
vi.mock('primevue/usetoast', () => ({
  useToast: () => ({ add: toastAdd }),
}))

import AboutDialog from './AboutDialog.vue'

// --- About dialog "What's new" must reflect the 3.8 release ---
//
// The bullets are hand-curated for the minor LINE and grow with its patches (3.8.0
// brought imported mail keeping its original .eml, the fullscreen file preview, the
// cover thumbnail in the document header, grid-view sorting and drag reordering, the
// self-rebuilding search index and full file names on hover; 3.8.1/3.8.2 added the
// four below). The heading is pinned to that line and must stay in step with the
// project's MAJOR.MINOR — the final test guards against the heading drifting behind
// a minor bump, which is exactly how the 3.1.0 bullets survived unchanged to 3.4.0.

describe('AboutDialog highlights', () => {
  it('pins the What\'s-new heading to the 3.8 highlights', () => {
    expect(HIGHLIGHTS_VERSION).toBe('3.8.0')
  })

  it('surfaces concrete 3.8 highlights in both locales', () => {
    const enText = HIGHLIGHT_KEYS.map((k) => resolve(en, k).toLowerCase()).join(' ')
    const deText = HIGHLIGHT_KEYS.map((k) => resolve(de, k).toLowerCase()).join(' ')
    // The retained .eml original and the fullscreen preview are two load-bearing 3.8 additions.
    expect(enText).toContain('.eml')
    expect(enText).toContain('fullscreen')
    expect(enText).toContain('cover thumbnail')
    // The German bullets use the interface's own labels — "Vollbild" for fullscreen
    // and "Titelbild" for the cover — not the English words.
    expect(deText).toContain('.eml')
    expect(deText).toContain('vollbild')
    expect(deText).toContain('titelbild')
    // The stale 3.7 bullets (account dark mode, the uncapped activity log) must be gone.
    expect(enText).not.toContain('dark-mode')
    expect(enText).not.toContain('activity log')
    expect(deText).not.toContain('aktivitätsprotokoll')
  })

  // The bullets describe the whole 3.8 LINE and accumulate across its patches: a
  // 3.8.2 user opens the same "What's new in 3.8" list, so the features shipped by
  // 3.8.1/3.8.2 have to be in it. Leaving them out is how the branding page, the
  // tag wildcards, WebP previews and the scrolling fullscreen PDF would have been
  // invisible to exactly the people who had just installed them.
  it('carries the 3.8.1/3.8.2 additions as resolvable bullets', () => {
    const added = [
      'ui.about.highlights.custom_branding',
      'ui.about.highlights.tag_wildcards',
      'ui.about.highlights.webp_previews',
      'ui.about.highlights.pdf_scroll_and_gallery_open',
    ]
    for (const key of added) {
      expect(HIGHLIGHT_KEYS as readonly string[], `listed: ${key}`).toContain(key)
      // An unresolved key renders as the raw dotted key in the dialog, so presence
      // in the list is only half of it — en must carry the sentence.
      expect(resolve(en, key), `en:${key}`).toBeTruthy()
    }
    const addedText = added.map((k) => resolve(en, k).toLowerCase()).join(' ')
    expect(addedText).toContain('branding')
    expect(addedText).toContain('tag:invoice*')
    expect(addedText).toContain('webp')
    expect(addedText).toContain('scroll')
  })

  it('every highlight key resolves in both locales (no missing bullet)', () => {
    for (const key of HIGHLIGHT_KEYS) {
      expect(resolve(en, key), `en:${key}`).toBeTruthy()
      expect(resolve(de, key), `de:${key}`).toBeTruthy()
    }
  })

  // Recurrence guard: the pinned heading MAJOR.MINOR MUST equal the project's
  // MAJOR.MINOR (the docs-parent <version> in pom.xml). A minor/major release
  // that bumps the version but not these bullets fails here — the exact drift
  // that let the 3.1.0 highlights ship at 3.4.0. Patch releases are exempt: a
  // patch bump reuses the current minor's bullets, so only x.y must agree.
  it('keeps HIGHLIGHTS_VERSION MAJOR.MINOR in step with the project version', () => {
    const projectVersion = readProjectVersion()
    expect(minorOf(HIGHLIGHTS_VERSION)).toBe(minorOf(projectVersion))
    // Falsification: an exact-match-only guard that ignored the minor would let
    // a stale 3.4.x heading pass at project 3.5.x — assert the guard actually
    // fails on a minor drift, so it can never silently degrade to that.
    expect(minorOf('3.4.1')).not.toBe(minorOf(projectVersion))
  })

  // The RENDERED heading must use the CURRENT app version's MAJOR.MINOR, never a
  // separately-maintained patch constant — so a future patch (3.5.2 -> 3.5.9, or
  // a 3.6.x server before the bullets are recurated) can never show a mismatching
  // "What's new in X". This mounts the real component and reads the <h3>.
  it('renders the What\'s-new heading with the CURRENT app version major.minor', () => {
    // A patch AHEAD of the curated constant: heading must still read "3.5", not "3.5.0".
    appInfoValue.value = { current_version: '3.5.2' }
    expect(renderedHeading()).toBe('What\'s new in 3.5')

    // A DIFFERENT minor: the heading follows the app, proving it is derived from the
    // live version and not the pinned HIGHLIGHTS_VERSION constant.
    appInfoValue.value = { current_version: '3.6.0' }
    expect(renderedHeading()).toBe('What\'s new in 3.6')
    // headingVersion is the single derivation both component and this test rely on.
    expect(headingVersion('3.6.0')).toBe('3.6')
  })
})

// The About dialog was the THIRD place that hardcoded the product name (it read "teedy v3.8.1"
// on every instance, lowercase, regardless of what the operator had called their deployment).
// It now renders the configured brand, with the same fallback as the rest of the chrome.
describe('AboutDialog brand name', () => {
  it('renders the CONFIGURED application name, not a hardcoded product name', () => {
    brandNameValue.value = 'Contoso Archive'
    expect(mountDialog().get('.about-name').text()).toBe('Contoso Archive')
  })

  it('renders the fallback brand on an instance that has not been renamed', () => {
    brandNameValue.value = 'Teedy'
    const name = mountDialog().get('.about-name').text()
    expect(name).toBe('Teedy')
    // The old literal was lowercase; the brand is the product name proper.
    expect(name).not.toBe('teedy')
  })

  it('keeps the running version beside the brand', () => {
    brandNameValue.value = 'Contoso Archive'
    appInfoValue.value = { current_version: '3.8.2' }
    expect(mountDialog().get('.about-version').text()).toBe('v3.8.2')
  })
})

// #275: the About dialog links the running build to its GitHub commit — a short, monospace SHA
// beside the version. It appears only when the server stamped one (commit_id present); an
// unstamped/local build (commit_id absent) shows only the version.
describe('AboutDialog build commit (#275)', () => {
  const SHA = '1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f'

  it('links the short commit SHA to its GitHub commit when the server provides one', () => {
    appInfoValue.value = { current_version: '3.8.3', commit_id: SHA }
    const link = mountDialog().get('.about-commit')
    expect(link.text()).toBe('1e2f3a4') // the 7-character short form
    expect(link.attributes('href')).toBe(`https://github.com/fmaass/teedy-docs/commit/${SHA}`)
    expect(link.attributes('title')).toBe(SHA) // the full SHA on hover
    expect(link.attributes('target')).toBe('_blank')
  })

  it('shows no commit link on a build that did not stamp one (commit_id absent)', () => {
    appInfoValue.value = { current_version: '3.8.3' }
    expect(mountDialog().find('.about-commit').exists()).toBe(false)
  })
})

// #275 part 2: the admin-only report-a-bug / diagnostics affordance. The two buttons render only for
// an admin; the copy button writes the composed environment block to the clipboard, and the report
// button opens a prefilled GitHub issue with that block URL-encoded into the body. The raw block is
// deliberately NOT rendered as text (only static button labels are), so nothing volatile enters the
// visual baseline.
describe('AboutDialog diagnostics affordance (#275)', () => {
  const SHA = '1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f'
  const APP_INFO = {
    current_version: '3.8.3',
    commit_id: SHA,
    oidc_enabled: false,
    header_authentication_enabled: false,
  }
  const DIAG: AppDiagnostics = {
    jetty_version: '12.1.11',
    java_version: '21.0.4',
    java_vendor: 'Eclipse Adoptium',
    os_name: 'Linux',
    os_version: '6.1.0',
    os_arch: 'amd64',
  }

  beforeEach(() => {
    isAdminValue.value = false
    getAppDiagnosticsMock.mockReset()
    getAppDiagnosticsMock.mockResolvedValue(DIAG)
    toastAdd.mockReset()
    appInfoValue.value = { ...APP_INFO }
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders both admin actions for an admin and neither for a non-admin', async () => {
    isAdminValue.value = true
    const admin = mountDiagnostics()
    await flushPromises()
    expect(admin.find('.about-report-bug').exists()).toBe(true)
    expect(admin.find('.about-copy-diagnostics').exists()).toBe(true)

    getAppDiagnosticsMock.mockClear()
    isAdminValue.value = false
    const user = mountDiagnostics()
    await flushPromises()
    expect(user.find('.about-report-bug').exists()).toBe(false)
    expect(user.find('.about-copy-diagnostics').exists()).toBe(false)
    // A non-admin never triggers the admin-only diagnostics fetch.
    expect(getAppDiagnosticsMock).not.toHaveBeenCalled()
  })

  it('copies the composed environment block and toasts success', async () => {
    isAdminValue.value = true
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })

    const wrapper = mountDiagnostics()
    await flushPromises()
    await wrapper.find('.about-copy-diagnostics').trigger('click')
    await flushPromises()

    const expectedBlock = [
      '### Environment',
      '- Teedy version: 3.8.3',
      `- Build commit: ${SHA}`,
      '- Jetty: 12.1.11',
      '- Java: 21.0.4 (Eclipse Adoptium)',
      '- OS: Linux 6.1.0 (amd64)',
      '- Auth mode: internal',
    ].join('\n')
    // The block the component composes must match the builder's output exactly.
    expect(buildDiagnosticsBlock(APP_INFO, DIAG)).toBe(expectedBlock)
    expect(writeText).toHaveBeenCalledWith(expectedBlock)
    expect(toastAdd).toHaveBeenCalledWith(expect.objectContaining({ severity: 'success' }))
  })

  it('toasts an error when the clipboard is unavailable (insecure origin)', async () => {
    isAdminValue.value = true
    // A plain-http origin has no navigator.clipboard: reading .writeText throws synchronously and
    // must land in the error toast, not escape as an unhandled rejection (the FileActionMenu guard).
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })

    const wrapper = mountDiagnostics()
    await flushPromises()
    await wrapper.find('.about-copy-diagnostics').trigger('click')
    await flushPromises()

    expect(toastAdd).toHaveBeenCalledWith(expect.objectContaining({ severity: 'error' }))
  })

  it('opens a prefilled, correctly-encoded GitHub issue on report', async () => {
    isAdminValue.value = true
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)

    const wrapper = mountDiagnostics()
    await flushPromises()
    await wrapper.find('.about-report-bug').trigger('click')
    await flushPromises()

    expect(openSpy).toHaveBeenCalledTimes(1)
    const [rawUrl, target, features] = openSpy.mock.calls[0]
    expect(target).toBe('_blank')
    expect(features).toBe('noopener')
    expect(rawUrl).toBe(buildReportUrl(buildDiagnosticsBlock(APP_INFO, DIAG)))

    // Independent of the builder: the query is genuinely URL-encoded and decodes back to a body that
    // carries the human scaffold AND the environment block.
    const url = new URL(rawUrl as string)
    expect(url.origin + url.pathname).toBe('https://github.com/fmaass/teedy-docs/issues/new')
    expect(url.searchParams.get('labels')).toBe('bug')
    const body = url.searchParams.get('body') ?? ''
    expect(body).toContain('Steps to reproduce')
    expect(body).toContain('### Environment')
    expect(body).toContain('- Teedy version: 3.8.3')
    expect(body).toContain(`- Build commit: ${SHA}`)
    expect(body).toContain('- Java: 21.0.4 (Eclipse Adoptium)')
    // The raw '#' is percent-encoded on the wire (%23), so the block is not sitting in the URL plain.
    expect(rawUrl).toContain('%23%23%23%20Environment')
    expect(rawUrl).not.toContain('### Environment')
  })
})

// Mount AboutDialog (visible) with admin actions clickable: Dialog renders its default slot inline,
// and Button is a lightweight stub that forwards @click so the two action handlers can be exercised.
function mountDiagnostics() {
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })
  return mount(AboutDialog, {
    props: { visible: true },
    global: {
      plugins: [i18n],
      stubs: {
        Dialog: { template: '<div><slot /></div>' },
        Button: {
          props: ['label', 'icon', 'severity', 'text', 'size', 'outlined'],
          emits: ['click'],
          template: '<button @click="$emit(\'click\', $event)">{{ label }}</button>',
        },
      },
    },
  })
}

// Mount AboutDialog (visible) with the overlay machinery stubbed out, so its body is in the DOM.
function mountDialog() {
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })
  return mount(AboutDialog, {
    props: { visible: true },
    global: {
      plugins: [i18n],
      stubs: {
        // Render the Dialog's default slot inline so the heading is in the DOM
        // without PrimeVue's teleport/overlay machinery.
        Dialog: { template: '<div><slot /></div>' },
        Button: true,
      },
    },
  })
}

// Mount AboutDialog (visible) and return its rendered "What's new" heading text.
function renderedHeading(): string {
  return mountDialog().get('.about-heading').text()
}

// The MAJOR.MINOR prefix of a semantic version (e.g. "3.5.0" -> "3.5").
function minorOf(version: string): string {
  const parts = version.split('.')
  if (parts.length < 2) {
    throw new Error(`Not a MAJOR.MINOR[.PATCH] version: ${version}`)
  }
  return `${parts[0]}.${parts[1]}`
}

function readProjectVersion(): string {
  const here = dirname(fileURLToPath(import.meta.url))
  // src/main/webapp/src/components -> repo root is six levels up.
  const pomPath = resolvePath(here, '../../../../../../pom.xml')
  const pom = readFileSync(pomPath, 'utf8')
  // The docs-parent project version: the <version> that follows the parent artifactId,
  // NOT any plugin/dependency <version> further down the file.
  const m = pom.match(/<artifactId>docs-parent<\/artifactId>[\s\S]*?<version>([^<]+)<\/version>/)
  if (!m) throw new Error(`Could not read the docs-parent <version> from ${pomPath}`)
  return m[1].trim()
}

function resolve(bundle: unknown, dotted: string): string {
  const value = dotted
    .split('.')
    .reduce<unknown>(
      (acc, part) => (acc && typeof acc === 'object' ? (acc as Record<string, unknown>)[part] : undefined),
      bundle,
    )
  return value as string
}
