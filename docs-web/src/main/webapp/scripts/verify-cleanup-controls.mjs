#!/usr/bin/env node
// #187 — asserts the two deferred-cleanup CONTROL specs behaved, in both directions.
//
// e2e/cleanup-control.spec.ts contains two tests that are SUPPOSED to fail; a plain
// `playwright test` run therefore cannot be the gate. This script runs them with
// E2E_CLEANUP_CONTROL=1, reads the JSON report, and checks the OUTCOME:
//
//   negative control — must FAIL with the BODY's error, must NOT report the cleanup's,
//                      and must carry the hanging step as a `cleanup-failures` attachment.
//   positive control — must FAIL, and the reported error must be the cleanup's.
//
// Both checks break if fixtures.ts is reverted to teardown-in-`finally`: the attachment
// disappears (the old idiom has nowhere to record it) and the positive control goes green
// (a swallowed cleanup error is invisible).
//
// Requires the app to be up; pass PLAYWRIGHT_BASE_URL if it is not on :8080.
import { spawnSync } from 'node:child_process'

const run = spawnSync(
  'npx',
  [
    'playwright',
    'test',
    'e2e/cleanup-control.spec.ts',
    '--project=desktop',
    '--reporter=json',
    '--retries=0',
  ],
  {
    cwd: new URL('..', import.meta.url).pathname,
    env: { ...process.env, E2E_CLEANUP_CONTROL: '1', PLAYWRIGHT_JSON_OUTPUT_NAME: '' },
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  },
)

const raw = run.stdout ?? ''
const start = raw.indexOf('{')
if (start < 0) {
  console.error('FAIL: playwright produced no JSON report.\n' + raw + '\n' + (run.stderr ?? ''))
  process.exit(2)
}
let report
try {
  report = JSON.parse(raw.slice(start))
} catch (e) {
  console.error('FAIL: could not parse the JSON report: ' + e.message)
  process.exit(2)
}

const tests = []
const walk = (suite) => {
  for (const spec of suite.specs ?? []) {
    for (const t of spec.tests ?? []) tests.push({ title: spec.title, result: t.results?.[0] ?? null })
  }
  for (const child of suite.suites ?? []) walk(child)
}
for (const suite of report.suites ?? []) walk(suite)

const failures = []
const check = (ok, message) => {
  if (!ok) failures.push(message)
}

const find = (needle) => tests.find((t) => t.title.includes(needle))
const negative = find('negative: a body failure survives a hanging cleanup')
const positive = find('positive: a broken cleanup after a passing body turns the test red')

check(tests.length === 2, `expected 2 control tests, collected ${tests.length}`)
check(!!negative, 'negative control test not found in the report')
check(!!positive, 'positive control test not found in the report')

// Playwright's `error.message` carries a source snippet of the failing line after the
// message itself, so a substring match over the whole thing sees identifiers that merely
// APPEAR in the code. Compare the message line — the reported failure — separately from
// the full text.
const errorText = (r) => (r?.errors ?? []).map((e) => `${e.message ?? ''}`).join('\n')
const errorHeadline = (r) =>
  (r?.errors ?? [])
    .map((e) => `${e.message ?? ''}`.split('\n')[0])
    .join('\n')
const attachmentText = (r, name) =>
  (r?.attachments ?? [])
    .filter((a) => a.name === name)
    .map((a) => (a.body ? Buffer.from(a.body, 'base64').toString('utf8') : ''))
    .join('\n')

if (negative) {
  const r = negative.result
  const err = errorText(r)
  const headline = errorHeadline(r)
  const attached = attachmentText(r, 'cleanup-failures')
  check(r?.status === 'failed', `negative control status is "${r?.status}", expected "failed"`)
  check(
    headline.includes('BODY_FAILED_SENTINEL'),
    'negative control: the reported error is NOT the body error (BODY_FAILED_SENTINEL missing)',
  )
  check(
    !err.includes('did not settle within'),
    'negative control: the hanging cleanup SUPERSEDED the body error (its timeout is the reported failure)',
  )
  check(
    !err.includes('deferred cleanup step(s) failed'),
    'negative control: a cleanup failure was reported instead of being attached',
  )
  check(
    !/Test timeout of \d+ms exceeded/.test(err),
    'negative control: the hanging cleanup turned the failure into a test timeout',
  )
  check(
    attached.includes('HANGING_CLEANUP_SENTINEL'),
    'negative control: the cleanup failure was NOT attached as a `cleanup-failures` diagnostic',
  )
}

if (positive) {
  const r = positive.result
  const err = errorText(r)
  check(
    r?.status === 'failed',
    `positive control status is "${r?.status}", expected "failed" — a broken cleanup after a green body must go RED`,
  )
  check(
    err.includes('CLEANUP_BROKEN_SENTINEL') && err.includes('after a passing test body'),
    'positive control: the reported error does not name the broken cleanup step',
  )
}

if (failures.length) {
  console.error('CLEANUP CONTROL VERIFICATION FAILED:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}
console.log('OK: both #187 cleanup controls behaved (body failure preserved + attached; broken cleanup red).')
