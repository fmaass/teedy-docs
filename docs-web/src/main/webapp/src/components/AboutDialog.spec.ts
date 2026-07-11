import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve as resolvePath } from 'node:path'
import en from '../locale/en.json'
import de from '../locale/de.json'
import { HIGHLIGHTS_VERSION, HIGHLIGHT_KEYS } from './aboutHighlights'

// --- About dialog "What's new" must reflect the 3.4.0 release ---
//
// The bullets are hand-curated per release (clickable tag chips, saved filters,
// document relations, PDF/image rotation, OIDC + quota admin UIs, /apidoc + docs).
// The heading is pinned to that release and must stay in step with the project
// version — the final test guards against the heading drifting behind a version
// bump, which is exactly how the 3.1.0 bullets survived unchanged to 3.4.0.

describe('AboutDialog highlights', () => {
  it('pins the What\'s-new heading to the 3.4 highlights', () => {
    expect(HIGHLIGHTS_VERSION).toBe('3.4.1')
  })

  it('surfaces concrete 3.4.0 highlights in both locales', () => {
    const enText = HIGHLIGHT_KEYS.map((k) => resolve(en, k).toLowerCase()).join(' ')
    const deText = HIGHLIGHT_KEYS.map((k) => resolve(de, k).toLowerCase()).join(' ')
    // Saved filters + the /apidoc reference are two load-bearing 3.4.0 additions.
    expect(enText).toContain('filter combination')
    expect(enText).toContain('/apidoc')
    expect(deText).toContain('/apidoc')
    // The stale 3.1.0 bullets (LDAP reinstatement, camera capture) must be gone.
    expect(enText).not.toContain('ldap')
    expect(enText).not.toContain('camera')
  })

  it('every highlight key resolves in both locales (no missing bullet)', () => {
    for (const key of HIGHLIGHT_KEYS) {
      expect(resolve(en, key), `en:${key}`).toBeTruthy()
      expect(resolve(de, key), `de:${key}`).toBeTruthy()
    }
  })

  // Recurrence guard: the pinned heading version MUST equal the project version
  // (the docs-parent <version> in pom.xml). If a release bumps the version but not
  // these bullets, this fails — the exact drift that let the 3.1.0 highlights ship
  // at 3.4.0.
  it('keeps HIGHLIGHTS_VERSION in step with the project version', () => {
    expect(HIGHLIGHTS_VERSION).toBe(readProjectVersion())
  })
})

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
