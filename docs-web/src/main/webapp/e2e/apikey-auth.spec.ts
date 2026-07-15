import { test, expect, request as playwrightRequest } from './fixtures'
import { unique } from './helpers'

// API-key bearer authentication, end to end against the REAL server.
//
// The value under test is the whole loop: the UI mints a `tdapi_<hex>` token shown
// exactly ONCE, and a caller presenting it as `Authorization: Bearer <token>` is
// authenticated as the key's owner by ApiKeyBasedSecurityFilter. To prove that we:
//   1. Create a key via the real Settings → API keys UI and capture the one-time token.
//   2. From a SEPARATE, cookie-less request context (no session leak — the token is the
//      only credential) call /api/user and assert it returns the OWNER's identity
//      (anonymous:false, username "admin"), and /api/document/list returns 200 — a
//      genuinely auth-gated endpoint (it 403s for anonymous callers).
//   3. Negative: a garbage `tdapi_*` token is an INVALID credential — ApiKeyBasedSecurityFilter
//      REJECTS it with 401 (AuthenticationFailed) and terminates the chain, on EVERY /api route
//      (the gated endpoint AND /api/user). It is NOT downgraded to anonymous.
//   4. Negative: after the key is DELETED via the UI, the same token resolves to no key — the SAME
//      invalid-credential path — so it is rejected with 401. Revocation is honoured server-side.
//
// 401-vs-403 contract (credential-precedence work, this release; verified against the filters):
//   * A `Bearer tdapi_*`-shaped header resolving to no key (garbage OR revoked) => 401 (bad credential).
//   * NO credential at all on a gated endpoint => 403 (the resource denies the anonymous caller — an
//     authorization denial, not an authentication rejection). That distinction is asserted below.
//
// If bearer auth were removed the positive read-back (username "admin") and the 200 on
// the gated endpoint would both fail, so this is a real assertion of the server filter,
// not a self-check.

const GATED_ENDPOINT = '/api/document/list?limit=1'

test('an API key token authenticates as its owner; garbage and revoked tokens are rejected', async ({ page, baseURL }) => {
  const keyName = unique('e2e-apikey')

  // --- Mint the key through the real UI and capture the one-time token ---
  await page.goto('/#/settings/api-keys')
  await page.getByRole('button', { name: 'Create key' }).click()
  const createDialog = page.getByRole('dialog', { name: 'Create API key' })
  await createDialog.locator('#key-name').fill(keyName)
  await createDialog.getByRole('button', { name: 'Create' }).click()

  // The one-time key-display dialog shows the full token in a <code> element.
  const keyDialog = page.getByRole('dialog', { name: /API key created/i })
  await expect(keyDialog).toBeVisible()
  const token = ((await keyDialog.locator('code.key-value').innerText()) ?? '').trim()
  expect(token, 'displayed token').toMatch(/^tdapi_[0-9a-f]+$/)
  await keyDialog.getByRole('button', { name: 'Done' }).click()

  // A fresh request context with NO cookies: the bearer token is the sole credential,
  // so a passing assertion cannot be explained by a leaked admin session. Pass an
  // explicit empty storageState — request.newContext() otherwise inherits the project's
  // admin storageState (playwright.config.ts), which would silently authenticate the
  // "anonymous" calls below.
  const anon = await playwrightRequest.newContext({
    baseURL,
    storageState: { cookies: [], origins: [] },
  })
  try {
    // --- Positive: the token authenticates as the owner (admin) ---
    const meRes = await anon.get('/api/user', {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(meRes.status(), 'GET /api/user with valid token').toBe(200)
    const me = await meRes.json()
    expect(me.anonymous, 'authenticated (not anonymous)').toBe(false)
    expect(me.username, 'identity read-back').toBe('admin')

    // The token reaches a genuinely auth-gated endpoint (403 for anonymous callers).
    const listRes = await anon.get(GATED_ENDPOINT, {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(listRes.status(), 'gated endpoint with valid token').toBe(200)

    // --- Negative: NO credential at all is an authorization denial on the gated endpoint (403) ---
    // An absent credential is NOT a rejected credential: the api-key filter falls through to anonymous
    // and the RESOURCE denies the anonymous caller — a genuine 403 authorization denial, which must
    // stay 403 (distinct from the 401 bad-credential rejections below).
    const anonList = await anon.get(GATED_ENDPOINT)
    expect(anonList.status(), 'gated endpoint anonymous').toBe(403)

    // --- Negative: a garbage tdapi_ token is an INVALID credential — rejected with 401 ---
    const garbageRes = await anon.get(GATED_ENDPOINT, {
      headers: { Authorization: 'Bearer tdapi_deadbeefdeadbeefdeadbeefdeadbeef' },
    })
    expect(garbageRes.status(), 'gated endpoint with garbage token').toBe(401)
    // The rejection terminates the chain on EVERY /api route, so /api/user rejects it too (401) —
    // it answers anonymous:true only when NO credential is presented, never for an invalid one.
    const garbageMe = await anon.get('/api/user', {
      headers: { Authorization: 'Bearer tdapi_deadbeefdeadbeefdeadbeefdeadbeef' },
    })
    expect(garbageMe.status(), 'garbage token rejected on /api/user').toBe(401)

    // --- Revoke the key via the UI, then reuse the SAME token: it must be rejected ---
    const row = page.getByRole('row', { name: new RegExp(keyName) })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: 'Delete API key' }).click()
    const confirm = page.getByRole('alertdialog')
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: 'Yes' }).click()
    await expect(page.getByRole('row', { name: new RegExp(keyName) })).toHaveCount(0)

    // A deleted key resolves to no key — the SAME invalid-credential path as a garbage token — so a
    // revoked token is rejected with 401 on every /api route, not silently downgraded to anonymous.
    const revokedList = await anon.get(GATED_ENDPOINT, {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(revokedList.status(), 'gated endpoint with revoked token').toBe(401)
    const revokedMe = await anon.get('/api/user', {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(revokedMe.status(), 'revoked token rejected on /api/user').toBe(401)
  } finally {
    await anon.dispose()
    // Best-effort cleanup if the revoke step above did not run (early failure).
    await page.goto('/#/settings/api-keys').catch(() => {})
    const leftover = page.getByRole('row', { name: new RegExp(keyName) })
    if (await leftover.count().catch(() => 0)) {
      await leftover.getByRole('button', { name: 'Delete API key' }).click().catch(() => {})
      const c = page.getByRole('alertdialog')
      if (await c.isVisible().catch(() => false)) {
        await c.getByRole('button', { name: 'Yes' }).click().catch(() => {})
      }
    }
  }
})
