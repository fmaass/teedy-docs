import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  MAX_TAG_NAME_LENGTH,
  MAX_TAG_PREFIX_LENGTH,
  TAG_SUFFIX_BUDGET,
  TAG_TAIL_BOUNDS,
  TAG_TAIL_MAX_LENGTH,
  uniqueTag,
} from './helpers'

// #200 — the generated-tag-name budget, checked as a unit.
//
// TagResource caps a tag name at 36 characters (TagResource.java:225 on create, :318 on
// update), and every generated tag name in the suite has to clear that cap — including
// the ones specs derive a suffixed name from. This file is a PURE check: no browser, no
// server, no Playwright. It runs under `npm run test:unit` (vitest.config.ts includes
// `e2e/**/*.check.ts`), which is why it is named `.check.ts` and not `.spec.ts` — the
// latter would also be collected by Playwright, which cannot run it without an app.
//
// The `.spec.ts` files stay Playwright's; this file is the one place the LENGTH
// arithmetic is exercised without a running Teedy.

const E2E_DIR = dirname(fileURLToPath(import.meta.url))

// The prefixes the suite actually passes to uniqueTag, read out of the spec files rather
// than restated here — a list transcribed by hand goes stale the day someone adds a tag.
function tagPrefixesInSuite(): string[] {
  const prefixes: string[] = []
  for (const file of readdirSync(E2E_DIR).filter((f) => f.endsWith('.spec.ts'))) {
    const source = readFileSync(join(E2E_DIR, file), 'utf8')
    for (const m of source.matchAll(/\buniqueTag\(\s*'([^']*)'/g)) prefixes.push(m[1])
  }
  return prefixes
}

// The suffixes specs append to a GENERATED tag name, and the file:line each comes from.
// TAG_SUFFIX_BUDGET has to cover the longest of them.
const DERIVED_SUFFIXES = [
  { suffix: '-00', source: 'facets.spec.ts:52 `${prefix}-${String(i).padStart(2, "0")}`' },
  { suffix: '-r', source: 'tags.spec.ts:19 `${name}-r`' },
  { suffix: '-1', source: 'documents.spec.ts:72 `${runId}-${n}`' },
]

describe('uniqueTag fits the server cap on tag names (#200)', () => {
  const prefixes = tagPrefixesInSuite()

  // Positive control: if the scan above ever silently matches nothing (renamed helper,
  // broken regex, moved specs), every length assertion below would pass vacuously.
  it('finds the tag prefixes the suite really uses', () => {
    expect(prefixes.length).toBeGreaterThanOrEqual(20)
    expect(prefixes).toContain('e2e-tag')
    expect(prefixes).toContain('focustag')
  })

  const longest = prefixes.reduce((a, b) => (b.length > a.length ? b : a), '')

  it('reserves enough headroom for the longest suffix any spec appends', () => {
    const longestSuffix = DERIVED_SUFFIXES.reduce((a, b) => (b.suffix.length > a.suffix.length ? b : a))
    expect(
      TAG_SUFFIX_BUDGET,
      `the budget must cover ${longestSuffix.source}`,
    ).toBeGreaterThanOrEqual(longestSuffix.suffix.length)
  })

  // (a) The whole point: worst case, longest real prefix, plus a derived suffix, still fits.
  it('the WORST-CASE name for the longest real prefix still fits the cap with a suffix on it', () => {
    const worstCase = `${longest}-${TAG_TAIL_BOUNDS.maxEpochMs.toString(36)}-${TAG_TAIL_BOUNDS.maxPid.toString(36)}-${TAG_TAIL_BOUNDS.maxCounter.toString(36)}`
    expect(worstCase.length, `worst-case uniqueTag("${longest}") = "${worstCase}"`).toBe(
      longest.length + TAG_TAIL_MAX_LENGTH,
    )
    expect(worstCase.length + TAG_SUFFIX_BUDGET).toBeLessThanOrEqual(MAX_TAG_NAME_LENGTH)

    // …and the real generator agrees with the arithmetic. The binding assertion is the
    // one against the BUDGET, not against 36: a name that fits today only because the
    // per-worker counter is still one digit is exactly the latent overrun #200 is about,
    // and it would sail past a bare `<= 36`.
    for (const prefix of prefixes) {
      const name = uniqueTag(prefix)
      expect(name.length, `uniqueTag("${prefix}") = "${name}" must stay within its budgeted tail`).toBeLessThanOrEqual(
        prefix.length + TAG_TAIL_MAX_LENGTH,
      )
      expect(name.length + TAG_SUFFIX_BUDGET, `uniqueTag("${prefix}") = "${name}"`).toBeLessThanOrEqual(
        MAX_TAG_NAME_LENGTH,
      )
      for (const { suffix } of DERIVED_SUFFIXES) {
        expect(`${name}${suffix}`.length).toBeLessThanOrEqual(MAX_TAG_NAME_LENGTH)
      }
    }
  })

  // REALNESS. Same longest real prefix, same worst-case bounds, the OLD construction
  // (helpers.ts:148 — decimal epoch/pid/counter): it overruns, so this check would have
  // been red before the fix and is red again the moment a site regresses to unique().
  it('the plain unique() construction does NOT fit — this check has teeth', () => {
    const legacyWorstCase = `${longest}-${TAG_TAIL_BOUNDS.maxEpochMs}-${TAG_TAIL_BOUNDS.maxPid}-${TAG_TAIL_BOUNDS.maxCounter}`
    expect(
      legacyWorstCase.length + TAG_SUFFIX_BUDGET,
      `worst-case unique("${longest}") = "${legacyWorstCase}"`,
    ).toBeGreaterThan(MAX_TAG_NAME_LENGTH)
    // Even bare, with no suffix, it has no headroom left at all.
    expect(legacyWorstCase.length).toBeGreaterThanOrEqual(MAX_TAG_NAME_LENGTH)
  })

  // (b) Structural uniqueness — the property the length clamp must not have cost us.
  it('never mints the same name twice', () => {
    const names = Array.from({ length: 5_000 }, () => uniqueTag('e2e-tag'))
    expect(new Set(names).size).toBe(names.length)
    for (const name of names) expect(name.length + TAG_SUFFIX_BUDGET).toBeLessThanOrEqual(MAX_TAG_NAME_LENGTH)
  })

  // (c) An unfittable prefix is a loud error, never a silent truncation — truncating
  // would eat the unique tail and bring the collisions back.
  it('throws on a prefix too long to fit, instead of truncating', () => {
    const tooLong = 'x'.repeat(MAX_TAG_PREFIX_LENGTH + 1)
    expect(() => uniqueTag(tooLong)).toThrow(/prefix is \d+ characters/)

    const longestThatFits = 'x'.repeat(MAX_TAG_PREFIX_LENGTH)
    const name = uniqueTag(longestThatFits)
    expect(name.startsWith(longestThatFits)).toBe(true)
    expect(name.length + TAG_SUFFIX_BUDGET).toBeLessThanOrEqual(MAX_TAG_NAME_LENGTH)
  })
})
