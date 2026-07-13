import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve as resolvePath } from 'node:path'
import en from '../locale/en.json'
import de from '../locale/de.json'
import { HIGHLIGHTS_VERSION, HIGHLIGHT_KEYS, headingVersion } from './aboutHighlights'

// The running version drives the rendered heading; mock useAppInfo so a test can
// set the app version and assert the "What's new in X" heading uses major.minor.
const appInfoValue = vi.hoisted(() => ({ value: undefined as { current_version: string } | undefined }))
vi.mock('../composables/useAppInfo', () => ({
  useAppInfo: () => ({ data: ref(appInfoValue.value) }),
}))

import AboutDialog from './AboutDialog.vue'

// --- About dialog "What's new" must reflect the 3.5.0 release ---
//
// The bullets are hand-curated per release (personal favorites, gallery view
// mode, rich-text descriptions, admin statistics dashboard). The heading is
// pinned to that release and must stay in step with the project's MAJOR.MINOR —
// the final test guards against the heading drifting behind a minor bump, which
// is exactly how the 3.1.0 bullets survived unchanged to 3.4.0.

describe('AboutDialog highlights', () => {
  it('pins the What\'s-new heading to the 3.5 highlights', () => {
    expect(HIGHLIGHTS_VERSION).toBe('3.5.0')
  })

  it('surfaces concrete 3.5.0 highlights in both locales', () => {
    const enText = HIGHLIGHT_KEYS.map((k) => resolve(en, k).toLowerCase()).join(' ')
    const deText = HIGHLIGHT_KEYS.map((k) => resolve(de, k).toLowerCase()).join(' ')
    // Favorites + the gallery view are two load-bearing 3.5.0 additions.
    expect(enText).toContain('favorite')
    expect(enText).toContain('gallery')
    expect(deText).toContain('galerie')
    // The stale 3.4.0 bullets (saved filters, /apidoc reference) must be gone.
    expect(enText).not.toContain('/apidoc')
    expect(enText).not.toContain('saved filter')
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

// Mount AboutDialog (visible) and return its rendered "What's new" heading text.
function renderedHeading(): string {
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })
  const wrapper = mount(AboutDialog, {
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
  return wrapper.get('.about-heading').text()
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
