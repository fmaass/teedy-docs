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
// #146: every shipped locale was brought to full en-parity (de by prior human work; the other ten —
// el/es/fr/it/pl/pt/ru/sq_AL/zh_CN/zh_TW — via LLM translation, pending native-speaker review, see the
// #146 follow-up issue) and is STRICT below. Consequence: a NEW en key must be translated to EVERY
// locale or this check fails. If that per-change burden is unwanted, revert STRICT_LOCALES to ['de']
// (the translations stay; they simply fall back to en at runtime until re-completed).
// Run: npm run i18n:check

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const localeDir = join(scriptDir, '..', 'src', 'locale')
const srcDir = join(scriptDir, '..', 'src')
const REFERENCE = 'en'
const STRICT_LOCALES = ['de', 'el', 'es', 'fr', 'it', 'pl', 'pt', 'ru', 'sq_AL', 'zh_CN', 'zh_TW']

// UNUSED-KEY guard (FE-01 hardening). After parity, the reference locale is scanned
// against the app source so dead keys can't silently re-accumulate (the ~46% dead
// weight the F4 sweep removed forced hand-translation of never-shown strings forever).
//
// A reference key is "used" if its exact dotted form appears as a quoted string
// anywhere in src/**/*.{vue,ts,js} — this covers t('x.y'), $t('x.y'), i18n.t('x.y'),
// :label="t('x.y')", and literal keys stored in data (e.g. DocumentSearchBar's
// `descKey: 'document.search_help.op_tag'`, AboutDialog's highlightKeys array).
//
// DYNAMIC_KEY_PREFIXES exempts namespaces indexed by a runtime-computed suffix — the
// key literal never appears in source, so a plain scan would false-positive them.
// Add a prefix here (with a source pointer) whenever you introduce a `t(`prefix_${x}`)`
// pattern. Keep this list MINIMAL — each entry is a hole in the dead-key guard.
const DYNAMIC_KEY_PREFIXES = [
  // SettingsMetadata.vue: t(`ui.metadata.type_${type.toLowerCase()}`)
  'ui.metadata.type_',
  // WorkflowStepEditor.vue: t(`ui.workflow_admin.transition_${name.toLowerCase()}`)
  'ui.workflow_admin.transition_',
]

// AWAITING_PREFIXES: keys for features intentionally on the roadmap but not yet
// ported to the Vue SPA (they have no consumer *yet*). They are legitimately
// reference-only, so the unused scan must not flag them. Remove a prefix here when
// the feature ships and its keys gain real consumers.
const AWAITING_PREFIXES = [
  'settings.security.',           // user self-enroll 2FA/TOTP (secret_key, QR, test) — not ported
  'settings.session.',            // opened-sessions management — not ported
  'settings.menu_opened_sessions',
  'settings.menu_two_factor_auth',
  'settings.inbox.',              // IMAP inbox import — not ported
  'settings.menu_inbox',
]

// Fail the build on newly-unused keys, or only warn? Warning-first so the remaining
// intentionally-awaiting keys don't block CI; flip to true once the awaiting features
// ship (or their prefixes are curated) to make the guard enforcing.
const UNUSED_FAILS = false

function flatten(obj, prefix = '', out = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, out)
    else out[key] = v
  }
  return out
}

function loadFlat(locale) {
  const raw = JSON.parse(readFileSync(join(localeDir, `${locale}.json`), 'utf8'))
  return flatten(raw)
}

function loadKeys(locale) {
  return new Set(Object.keys(loadFlat(locale)))
}

// Placeholder MULTISET of a message: {name} -> occurrence count. Catches the
// duplicate-interpolation class (#19: de had "{count} {count} gefunden" against
// en's single "{count}") that a key-set comparison is blind to.
function placeholderCounts(value) {
  const counts = new Map()
  if (typeof value !== 'string') return counts
  const re = /\{(\w+)\}/g
  let m
  while ((m = re.exec(value))) counts.set(m[1], (counts.get(m[1]) ?? 0) + 1)
  return counts
}

// HTML-TAG MULTISET of a message: each tag reduced to its name and role —
// opening `<name>`, closing `</name>`, self-closing `<name/>` — with attributes
// stripped, so `<a href="…">` counts as `<a>`. The sibling of placeholderCounts:
// a translator who drops a `<strong>`/`<br/>`, adds a `<small>` the source lacks,
// or leaves an unbalanced `</strong>` yields a tag multiset that differs from the
// reference (e.g. de settings.security.message_2 once carried a stray trailing
// `</strong>` and no opening tag). Names are lowercased so `<BR/>` == `<br/>`.
function tagCounts(value) {
  const counts = new Map()
  if (typeof value !== 'string') return counts
  const re = /<\s*(\/?)\s*([a-zA-Z][a-zA-Z0-9]*)\b[^>]*?(\/?)\s*>/g
  let m
  while ((m = re.exec(value))) {
    const name = m[2].toLowerCase()
    const token = m[1] === '/' ? `</${name}>` : m[3] === '/' ? `<${name}/>` : `<${name}>`
    counts.set(token, (counts.get(token) ?? 0) + 1)
  }
  return counts
}

// KNOWN_TAG_DEBT (tag-parity). Pre-existing markup loss in the native-review-pending
// locales (the #146 follow-up above): each string below dropped en's markup together
// with the *translated words it wrapped* — the "unread" word in `inbox.test_success`,
// the whole "<strong>unread</strong> … <a>Gmail</a>/<a>Outlook.com</a>/<a>Yahoo</a>"
// body in `inbox.message`. Restoring parity there needs the missing translation, not a
// mechanical tag re-wrap, so it is deferred to native-speaker review rather than
// fabricated here without the source translation. Listed per `${locale}::${key}` — NOT a
// blanket skip — so the debt is enumerated in the output on every run (never silent) and
// every other key/locale, German included, stays fully enforced. Prune an entry once
// native review restores its markup.
const KNOWN_TAG_DEBT = new Set([
  'fr::settings.inbox.message',
  'ru::settings.inbox.message',
  'ru::settings.inbox.test_success',
  'zh_CN::settings.inbox.message',
  'zh_CN::settings.inbox.test_success',
  'zh_TW::settings.inbox.message',
  'zh_TW::settings.inbox.test_success',
])

const referenceFlat = loadFlat(REFERENCE)
const referenceKeys = new Set(Object.keys(referenceFlat))
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
  const localeFlat = loadFlat(locale)
  const keys = new Set(Object.keys(localeFlat))
  const stale = [...keys].filter((k) => !referenceKeys.has(k)).sort()
  const missing = [...referenceKeys].filter((k) => !keys.has(k)).sort()
  const strict = STRICT_LOCALES.includes(locale)

  // Placeholder-parity: for every key the locale shares with the reference, the
  // {name} interpolation multiset must match — a missing/extra name (translator
  // dropped or typo'd a placeholder) or a differing occurrence count (the #19
  // duplicate) is a real UI defect. Plural messages (containing the vue-i18n `|`
  // branch separator) legitimately vary placeholder counts across languages with
  // different plural rules, so their COUNT is exempted (names still checked).
  const placeholderIssues = []
  for (const k of keys) {
    if (!referenceKeys.has(k)) continue
    const refVal = referenceFlat[k]
    const locVal = localeFlat[k]
    if (typeof refVal !== 'string' || typeof locVal !== 'string') continue
    const refP = placeholderCounts(refVal)
    const locP = placeholderCounts(locVal)
    const isPlural = refVal.includes('|') || locVal.includes('|')
    for (const name of new Set([...refP.keys(), ...locP.keys()])) {
      const rc = refP.get(name) ?? 0
      const lc = locP.get(name) ?? 0
      if (rc === 0 && lc > 0) placeholderIssues.push(`${k}: extra {${name}} not in reference`)
      else if (rc > 0 && lc === 0) placeholderIssues.push(`${k}: missing {${name}} (present in reference)`)
      else if (rc !== lc && !isPlural) placeholderIssues.push(`${k}: {${name}} appears ${lc}× vs ${rc}× in reference`)
    }
  }

  // HTML-tag-parity: for every key the locale shares with the reference, the balanced
  // HTML tag multiset (open/close/self-close, attributes stripped) must equal the
  // reference's. A dropped `<strong>`/`<br/>`, an unbalanced `</strong>`, or an added
  // `<small>` the source lacks is a rendering/markup defect a placeholder check is
  // blind to. Unlike placeholders, plural branches do not license a tag-count change:
  // no reference string carries tags inside a `|` branch, so counts are compared
  // unconditionally. Pairs in KNOWN_TAG_DEBT are enumerated separately (below) instead
  // of failing, so the machine-translated locales' pre-existing markup loss stays
  // visible without machine-fabricating the missing translation.
  const tagIssues = []
  const tagDebt = []
  for (const k of keys) {
    if (!referenceKeys.has(k)) continue
    const refVal = referenceFlat[k]
    const locVal = localeFlat[k]
    if (typeof refVal !== 'string' || typeof locVal !== 'string') continue
    const refT = tagCounts(refVal)
    const locT = tagCounts(locVal)
    const issuesForKey = []
    for (const tag of new Set([...refT.keys(), ...locT.keys()])) {
      const rc = refT.get(tag) ?? 0
      const lc = locT.get(tag) ?? 0
      if (rc !== lc) issuesForKey.push(`${k}: ${tag} appears ${lc}× vs ${rc}× in reference`)
    }
    if (!issuesForKey.length) continue
    if (KNOWN_TAG_DEBT.has(`${locale}::${k}`)) tagDebt.push(...issuesForKey)
    else tagIssues.push(...issuesForKey)
  }

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
  if (placeholderIssues.length) {
    if (strict) {
      hasError = true
      console.error(`✗ ${locale}: ${placeholderIssues.length} placeholder mismatch(es):`)
      for (const issue of placeholderIssues) console.error(`    ${issue}`)
    } else {
      console.warn(`  ${locale}: ${placeholderIssues.length} placeholder mismatch(es) (best-effort locale):`)
      for (const issue of placeholderIssues) console.warn(`    ${issue}`)
    }
  }
  if (tagIssues.length) {
    if (strict) {
      hasError = true
      console.error(`✗ ${locale}: ${tagIssues.length} HTML-tag mismatch(es):`)
      for (const issue of tagIssues) console.error(`    ${issue}`)
    } else {
      console.warn(`  ${locale}: ${tagIssues.length} HTML-tag mismatch(es) (best-effort locale):`)
      for (const issue of tagIssues) console.warn(`    ${issue}`)
    }
  }
  if (tagDebt.length) {
    console.warn(`  ${locale}: ${tagDebt.length} known HTML-tag debt (KNOWN_TAG_DEBT, awaiting native review — not failing):`)
    for (const issue of tagDebt) console.warn(`    ${issue}`)
  }
  if (!stale.length && !missing.length && !placeholderIssues.length && !tagIssues.length)
    console.log(`✓ ${locale}: complete${tagDebt.length ? ` (with ${tagDebt.length} known tag-debt)` : ''}`)
}

// ── Unused-key report ────────────────────────────────────────────────────────
function collectSource(dir, acc = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    const st = statSync(full)
    if (st.isDirectory()) {
      if (entry === 'node_modules' || entry === 'locale') continue
      collectSource(full, acc)
    } else if (/\.(vue|ts|js)$/.test(entry)) {
      acc.push(full)
    }
  }
  return acc
}

const sourceBlob = collectSource(srcDir)
  .map((f) => readFileSync(f, 'utf8'))
  .join('\n')

const isExempt = (key) =>
  DYNAMIC_KEY_PREFIXES.some((p) => key.startsWith(p)) ||
  AWAITING_PREFIXES.some((p) => key === p || key.startsWith(p))

// A key is "used" if its exact dotted form appears as a quoted literal in source.
const usedInSource = (key) =>
  sourceBlob.includes(`'${key}'`) ||
  sourceBlob.includes(`"${key}"`) ||
  sourceBlob.includes(`\`${key}\``)

const unused = [...referenceKeys]
  .filter((k) => !isExempt(k) && !usedInSource(k))
  .sort()

console.log(`\nunused-key scan — ${referenceKeys.size} reference keys, ${unused.length} with no source consumer`)
if (unused.length) {
  const level = UNUSED_FAILS ? 'error' : 'warn'
  console[level](
    `  ${unused.length} reference key(s) have no literal consumer in src ` +
      `(add to DYNAMIC_KEY_PREFIXES/AWAITING_PREFIXES if intentional, else prune):`,
  )
  for (const k of unused) console[level](`    ${k}`)
  if (UNUSED_FAILS) hasError = true
} else {
  console.log('✓ no unused reference keys')
}

if (hasError) {
  console.error('\ni18n parity check FAILED')
  process.exit(1)
}
console.log('\ni18n parity check passed')
