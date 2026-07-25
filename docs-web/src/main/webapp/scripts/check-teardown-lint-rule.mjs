#!/usr/bin/env node
// #187 — asserts the teardown-in-`finally` ESLint guardrail still FIRES.
//
// e2e/lint-fixtures/teardown-in-finally.ts is a file that must fail lint. `npm run lint`
// excludes it (it would be permanently red otherwise), so without this check the rule
// could be deleted, mis-scoped, or silently stop matching and nothing would notice.
// This runs ESLint on the fixture and requires an error on every case it declares,
// keyed on the rule id — not on a message string.
import { spawnSync } from 'node:child_process'

const FIXTURE = 'e2e/lint-fixtures/teardown-in-finally.ts'
const EXPECTED_CASES = 11

const run = spawnSync('npx', ['eslint', '--format', 'json', FIXTURE], {
  cwd: new URL('..', import.meta.url).pathname,
  encoding: 'utf8',
  maxBuffer: 16 * 1024 * 1024,
})

let results
try {
  results = JSON.parse(run.stdout)
} catch {
  console.error(`FAIL: eslint produced no JSON for ${FIXTURE}.\n${run.stdout}\n${run.stderr}`)
  process.exit(2)
}

const messages = (results[0]?.messages ?? []).filter((m) => m.ruleId === 'no-restricted-syntax')
if (messages.length !== EXPECTED_CASES) {
  console.error(
    `FAIL: the teardown-in-finally guardrail reported ${messages.length} violation(s) in ${FIXTURE}, expected ${EXPECTED_CASES}.\n` +
      `The rule is missing, mis-scoped, or no longer matches the banned shape.\n` +
      (results[0]?.messages ?? []).map((m) => `  ${m.line}:${m.column} ${m.ruleId} ${m.message}`).join('\n'),
  )
  process.exit(1)
}
console.log(`OK: the teardown-in-finally guardrail rejected all ${EXPECTED_CASES} banned shapes in ${FIXTURE}.`)
