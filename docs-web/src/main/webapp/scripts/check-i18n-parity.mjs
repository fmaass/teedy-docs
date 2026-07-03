#!/usr/bin/env node
// i18n key-parity guard (FE-01). Compares every locale under src/locale/ against
// the bundled reference en.json.
//
// Two failure classes:
//   - STALE keys  (present in a locale, absent from en.json): ALWAYS fail. These are
//     dead keys that no longer resolve to any UI string and only cause drift.
//   - MISSING keys (present in en.json, absent from a locale): fail only for locales
//     listed in STRICT_LOCALES (the languages we commit to shipping complete). For
//     every other locale, missing keys are reported as a non-fatal warning because
//     vue-i18n resolves them through fallbackLocale:'en' at runtime.
//
// Expand STRICT_LOCALES as a locale is brought to full parity with a human translator.
// Run: npm run i18n:check

import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const localeDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'locale')
const REFERENCE = 'en'
const STRICT_LOCALES = ['de']

function flatten(obj, prefix = '', out = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, out)
    else out[key] = v
  }
  return out
}

function loadKeys(locale) {
  const raw = JSON.parse(readFileSync(join(localeDir, `${locale}.json`), 'utf8'))
  return new Set(Object.keys(flatten(raw)))
}

const referenceKeys = loadKeys(REFERENCE)
const locales = readdirSync(localeDir)
  .filter((f) => f.endsWith('.json'))
  .map((f) => f.replace(/\.json$/, ''))
  .filter((l) => l !== REFERENCE)
  .sort()

let hasError = false
console.log(`i18n parity — reference "${REFERENCE}" has ${referenceKeys.size} keys`)
console.log(`strict locales (missing keys fail): ${STRICT_LOCALES.join(', ') || '(none)'}\n`)

// A strict production locale must exist — a deleted/renamed file must fail, not silently pass.
for (const strict of STRICT_LOCALES) {
  if (!locales.includes(strict)) {
    hasError = true
    console.error(`✗ strict locale "${strict}" has no ${strict}.json file`)
  }
}

for (const locale of locales) {
  const keys = loadKeys(locale)
  const stale = [...keys].filter((k) => !referenceKeys.has(k)).sort()
  const missing = [...referenceKeys].filter((k) => !keys.has(k)).sort()
  const strict = STRICT_LOCALES.includes(locale)

  if (stale.length) {
    hasError = true
    console.error(`✗ ${locale}: ${stale.length} STALE key(s) (remove these):`)
    for (const k of stale) console.error(`    ${k}`)
  }
  if (missing.length) {
    if (strict) {
      hasError = true
      console.error(`✗ ${locale}: ${missing.length} MISSING key(s) (strict locale must be complete):`)
      for (const k of missing) console.error(`    ${k}`)
    } else {
      console.warn(`  ${locale}: ${missing.length} missing key(s) — English fallback (best-effort locale)`)
    }
  }
  if (!stale.length && !missing.length) console.log(`✓ ${locale}: complete`)
}

if (hasError) {
  console.error('\ni18n parity check FAILED')
  process.exit(1)
}
console.log('\ni18n parity check passed')
