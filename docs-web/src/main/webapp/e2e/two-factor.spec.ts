import { test, expect, request as pwRequest } from './fixtures'
import {
  unique,
  totpCode,
  login,
  expectResponseOk,
  describeApiFailure,
  ROUTE_ROOT,
  gotoRouteReady,
} from './helpers'

// The activation code is recomputed from the secret the page itself shows and verified by the REAL
// server, so a regression in enrollment, activation, the login challenge, or disable fails an assertion
// here rather than being asserted against a mock.
test.describe('Two-factor enrollment (behavior A, self-service)', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  async function seedUser(baseURL: string): Promise<{ username: string; password: string }> {
    const username = unique('2fa').replace(/[^a-z0-9]/gi, '').toLowerCase()
    const password = 'TotpPass123'
    const admin = await pwRequest.newContext({ baseURL })
    const adminLogin = await admin.post('/api/user/login', {
      form: { username: 'admin', password: 'admin', remember: false },
    })
    // #186 instrumentation: this assertion is the suite's most frequent intermittent
    // failure and used to report only "expected true, received false". It still fails on
    // a bad response — the message now carries the status, `Retry-After` (present OR
    // absent) and the body, so the next occurrence names its own cause instead of
    // requiring another run to guess at one.
    await expectResponseOk(adminLogin, 'admin login for 2FA seed')
    const created = await admin.put('/api/user', {
      form: { username, password, email: `${username}@example.com`, storage_quota: 1_000_000_000 },
    })
    await expectResponseOk(created, 'create 2FA seed user')
    await admin.dispose()
    return { username, password }
  }

  // Deletion requires a reassign target (#55) and its success is asserted, so a run never leaks its user.
  async function deleteUser(baseURL: string, username: string) {
    const admin = await pwRequest.newContext({ baseURL })
    const adminLogin = await admin.post('/api/user/login', {
      form: { username: 'admin', password: 'admin', remember: false },
    })
    // The teardown login is deliberately NOT asserted here: a new assertion point before the
    // DELETE could abort the teardown and leak the seed user. Its diagnostics are instead
    // carried into the delete's own (pre-existing) assertion, so a throttled/failed login stops
    // masquerading as an unexplained 403 on the DELETE without changing what runs.
    const loginFailure = adminLogin.ok() ? null : await describeApiFailure(adminLogin)
    const deleted = await admin.delete(`/api/user/${username}`, {
      params: { reassign_to_username: 'admin' },
    })
    await expectResponseOk(
      deleted,
      loginFailure === null
        ? `cleanup: delete ${username}`
        : `cleanup: delete ${username} (the preceding admin login FAILED — ${loginFailure})`,
    )
    await admin.dispose()
  }

  test('enroll -> challenged login -> disable', async ({ page, baseURL, cleanup }) => {
    const { username, password } = await seedUser(baseURL!)
    cleanup.defer('delete the 2FA seed user', () => deleteUser(baseURL!, username))

    await login(page, username, password)
    await gotoRouteReady(page, '/#/settings/account', ROUTE_ROOT.settingsAccount)
    const card = page.locator('[data-test="two-factor-card"]')
    await expect(card).toBeVisible()

    await page.locator('[data-test="totp-enable"]').click()
    const qr = page.locator('[data-test="totp-qr"]')
    await expect(qr).toBeVisible()
    // The URI must carry the SHA1/6-digit/30-second parameters the server verifier uses.
    await expect(qr).toHaveAttribute('data-otpauth-uri', /^otpauth:\/\/totp\/.*algorithm=SHA1&digits=6&period=30$/)
    const secret = (await page.locator('[data-test="totp-secret"]').textContent())?.trim() ?? ''
    expect(secret, 'the manual secret is shown').toBeTruthy()

    await page.locator('[data-test="totp-code"]').fill(totpCode(secret))
    await page.locator('[data-test="totp-activate"]').click()
    await expect(page.locator('[data-test="totp-disable"]')).toBeVisible()

    await page.getByRole('button', { name: 'Logout' }).click()
    await expect(page).toHaveURL(/#\/login/)
    await page.getByLabel('Username').fill(username)
    await page.locator('#login-pass').fill(password)
    // The code field only appears once the server challenges the now TOTP-active account.
    await expect(page.locator('#login-code')).toHaveCount(0)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.locator('#login-code')).toBeVisible()
    await page.locator('#login-code').fill(totpCode(secret))
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page).toHaveURL(/#\/document$/)

    await gotoRouteReady(page, '/#/settings/account', ROUTE_ROOT.settingsAccount)
    await expect(page.locator('[data-test="totp-disable"]')).toBeVisible()
    await page.locator('#totp-disable-pass').fill(password)
    await page.locator('[data-test="totp-disable"]').click()
    await expect(page.locator('[data-test="totp-enable"]')).toBeVisible()
  })
})
