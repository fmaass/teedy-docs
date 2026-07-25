import { test, expect } from './fixtures'

// #187 CONTROL SPECS — both of these tests are DESIGNED TO FAIL. They are the two
// directions the deferred-cleanup fixture must get right, and a green functional suite
// cannot prove either of them (it never exercises post-body-failure behaviour).
//
// They are SKIPPED unless E2E_CLEANUP_CONTROL=1, so the normal suite — local,
// scripts/e2e-run.sh and CI alike — is never turned red by them. The assertions about
// their outcome live in scripts/verify-cleanup-controls.mjs, which runs this file with
// the flag set, parses the JSON report, and exits non-zero unless BOTH controls behaved.
// Run it with the app up:
//
//   PLAYWRIGHT_BASE_URL=http://localhost:8090 node scripts/verify-cleanup-controls.mjs
//
// Reverting fixtures.ts to the old swallow-in-`finally` idiom makes that script fail:
// control 1 loses its `cleanup-failures` attachment, control 2 goes green.

const ENABLED = process.env.E2E_CLEANUP_CONTROL === '1'

test('@cleanup-control negative: a body failure survives a hanging cleanup', async ({ cleanup }) => {
  test.skip(!ENABLED, 'control spec — set E2E_CLEANUP_CONTROL=1 (see scripts/verify-cleanup-controls.mjs)')

  // A teardown step that never settles — the worst case, because under the old idiom it
  // converts a precise body failure into a bare test timeout.
  cleanup.defer('HANGING_CLEANUP_SENTINEL never settles', () => new Promise(() => {}), { timeout: 2_000 })

  throw new Error('BODY_FAILED_SENTINEL — this is the failure the report must show')
})

test('@cleanup-control positive: a broken cleanup after a passing body turns the test red', async ({ cleanup }) => {
  test.skip(!ENABLED, 'control spec — set E2E_CLEANUP_CONTROL=1 (see scripts/verify-cleanup-controls.mjs)')

  cleanup.defer('broken teardown', () => {
    throw new Error('CLEANUP_BROKEN_SENTINEL — teardown must not fail silently')
  })

  // The body itself is sound; only the teardown is broken.
  expect(1 + 1).toBe(2)
})
