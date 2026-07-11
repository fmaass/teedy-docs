import { describe, it, expect } from 'vitest'
import en from '../locale/en.json'
import de from '../locale/de.json'
import { HIGHLIGHTS_VERSION, HIGHLIGHT_KEYS } from './aboutHighlights'

// --- BL-019: About dialog "What's new" must reflect 3.1.0 ---
//
// This release REINSTATES LDAP (commits 40f7c2f9, 7c672f11, ADR-0013). The 3.0.0
// highlights claimed LDAP was REMOVED, which is now false. The heading version was
// also pinned to 3.0.0 and never refreshed. These are the two load-bearing facts.

describe('AboutDialog highlights (BL-019)', () => {
  it('pins the What\'s-new heading to the 3.1.0 highlights', () => {
    expect(HIGHLIGHTS_VERSION).toBe('3.1.0')
  })

  it('does NOT claim LDAP was removed in either locale (this release reinstates it)', () => {
    const enRetire = resolve(en, 'ui.about.highlights.retirements')
    const deRetire = resolve(de, 'ui.about.highlights.retirements')
    expect(enRetire.toLowerCase()).not.toContain('ldap')
    expect(deRetire.toLowerCase()).not.toContain('ldap')
  })

  it('surfaces the LDAP reinstatement in the highlights in both locales', () => {
    const enText = HIGHLIGHT_KEYS.map((k) => resolve(en, k).toLowerCase()).join(' ')
    const deText = HIGHLIGHT_KEYS.map((k) => resolve(de, k).toLowerCase()).join(' ')
    expect(enText).toContain('ldap')
    expect(deText).toContain('ldap')
  })

  it('every highlight key resolves in both locales (no missing bullet)', () => {
    for (const key of HIGHLIGHT_KEYS) {
      expect(resolve(en, key), `en:${key}`).toBeTruthy()
      expect(resolve(de, key), `de:${key}`).toBeTruthy()
    }
  })
})

function resolve(bundle: unknown, dotted: string): string {
  const value = dotted
    .split('.')
    .reduce<unknown>(
      (acc, part) => (acc && typeof acc === 'object' ? (acc as Record<string, unknown>)[part] : undefined),
      bundle,
    )
  return value as string
}
