import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve as resolvePath } from 'node:path'
import en from '../locale/en.json'
import de from '../locale/de.json'
import { HIGHLIGHTS_VERSION, HIGHLIGHT_KEYS } from './aboutHighlights'

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
})

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
