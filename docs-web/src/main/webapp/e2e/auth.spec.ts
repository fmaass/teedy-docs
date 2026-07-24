import { test, expect, request as pwRequest } from './fixtures'
import { unique, totpCode } from './helpers'

// The login-form specs must start UNauthenticated — override the project-wide
// admin storageState with a clean one.
test.describe('authentication', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('logs in with valid credentials', async ({ page }) => {
    await page.goto('/#/login')
    await page.getByLabel('Username').fill('admin')
    await page.locator('#login-pass').fill('admin')
    await page.getByRole('button', { name: 'Sign in' }).click()

    await expect(page).toHaveURL(/#\/document$/)
    // Authenticated shell: the header Logout control only renders when a real
    // session exists, and (unlike the side-panel/Drawer brand link) it is present
    // in the header at BOTH the desktop and mobile viewports.
    await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible()
  })

  test('shows an error for bad credentials', async ({ page }) => {
    await page.goto('/#/login')
    await page.getByLabel('Username').fill('admin')
    await page.locator('#login-pass').fill('wrong-password')
    await page.getByRole('button', { name: 'Sign in' }).click()

    // A rejected login renders the error <Message> (role=alert) and stays on the
    // login form — the backend supplies the message text, so assert the alert
    // itself rather than a specific string.
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page).toHaveURL(/#\/login/)
    await expect(page.getByRole('button', { name: 'Logout' })).toHaveCount(0)
  })

  test('logs out', async ({ page }) => {
    await page.goto('/#/login')
    await page.getByLabel('Username').fill('admin')
    await page.locator('#login-pass').fill('admin')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page).toHaveURL(/#\/document$/)

    await page.getByRole('button', { name: 'Logout' }).click()

    // Logout clears the session and the guard bounces back to the login form.
    await expect(page).toHaveURL(/#\/login/)
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
  })
})

// --- Behavior A (TOTP login, #18) --------------------------------------------
// A TOTP-enabled account challenges POST /user/login with 400 ValidationCodeRequired
// (no code) / 403 (wrong code). The SPA login form must detect the challenge, reveal
// the OTP field, and resubmit with the code. Full valid-OTP login IS automated here:
// we seed a fresh user, enable TOTP via the real /user/enable_totp (which returns the
// Base32 secret), recompute the current code with the SAME RFC-6238 algorithm the
// server verifies, and complete the login through the real form. Cleanup deletes the
// seeded user as admin.
//
// REALNESS: the challenge field only appears when the backend actually returns
// ValidationCodeRequired; the valid code is verified by the real server (a mock
// would pass a broken server); the wrong-code path asserts the genuine 403-driven
// invalid-code message. Removing the totpRequired reveal, or the code-threading, or
// the server's TOTP check would each fail one of these assertions.
test.describe('TOTP login (behavior A)', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  // Enable TOTP on a fresh user via REST and return its credentials + Base32 secret.
  // Uses a throwaway API context per user so enable_totp runs as THAT user's session.
  async function seedTotpUser(baseURL: string): Promise<{
    username: string; password: string; secret: string
  }> {
    const username = unique('totp').replace(/[^a-z0-9]/gi, '').toLowerCase()
    const password = 'TotpPass123'
    const email = `${username}@example.com`

    const admin = await pwRequest.newContext({ baseURL })
    const adminLogin = await admin.post('/api/user/login', {
      form: { username: 'admin', password: 'admin', remember: false },
    })
    expect(adminLogin.ok(), 'admin login for TOTP seed').toBeTruthy()
    const created = await admin.put('/api/user', {
      form: { username, password, email, storage_quota: 1_000_000_000 },
    })
    expect(created.ok(), 'create TOTP seed user').toBeTruthy()
    await admin.dispose()

    // Log in AS the new user and enable TOTP so the secret is set on THEIR account.
    const asUser = await pwRequest.newContext({ baseURL })
    const userLogin = await asUser.post('/api/user/login', {
      form: { username, password, remember: false },
    })
    expect(userLogin.ok(), 'seed user login before enable_totp').toBeTruthy()
    const enableRes = await asUser.post('/api/user/enable_totp')
    expect(enableRes.ok(), 'enable_totp').toBeTruthy()
    const secret = (await enableRes.json()).secret as string
    expect(secret, 'enable_totp returned a secret').toBeTruthy()
    // enable_totp only stages a PENDING secret; activate it so it becomes the active login factor.
    const activateRes = await asUser.post('/api/user/totp/activate', { form: { code: totpCode(secret) } })
    expect(activateRes.ok(), 'activate totp').toBeTruthy()
    await asUser.dispose()

    return { username, password, secret }
  }

  async function deleteUser(baseURL: string, username: string) {
    const admin = await pwRequest.newContext({ baseURL })
    await admin.post('/api/user/login', {
      form: { username: 'admin', password: 'admin', remember: false },
    })
    await admin.delete(`/api/user/${username}`)
    await admin.dispose()
  }

  test('reveals the OTP field on challenge and completes a full valid-code login', async ({ page, baseURL }) => {
    const { username, password, secret } = await seedTotpUser(baseURL!)
    try {
      await page.goto('/#/login')
      await page.getByLabel('Username').fill(username)
      await page.locator('#login-pass').fill(password)
      // The OTP field is NOT present before the challenge — a non-TOTP login never
      // shows it.
      await expect(page.locator('#login-code')).toHaveCount(0)

      await page.getByRole('button', { name: 'Sign in' }).click()

      // Backend returned 400 ValidationCodeRequired → the SPA reveals the OTP field
      // and stays on the login form (password was accepted, only the code remains).
      await expect(page.locator('#login-code')).toBeVisible()
      await expect(page).toHaveURL(/#\/login/)

      // Compute the CURRENT valid code and resubmit. The server verifies it, so a
      // wrong algorithm here would fail the login (this is not a self-check).
      await page.locator('#login-code').fill(totpCode(secret))
      await page.getByRole('button', { name: 'Sign in' }).click()

      // Full green: a valid OTP lands us in the authenticated app shell.
      await expect(page).toHaveURL(/#\/document$/)
      await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible()
    } finally {
      await deleteUser(baseURL!, username)
    }
  })

  test('shows the invalid-code error for a wrong OTP on the challenge', async ({ page, baseURL }) => {
    const { username, password } = await seedTotpUser(baseURL!)
    try {
      await page.goto('/#/login')
      await page.getByLabel('Username').fill(username)
      await page.locator('#login-pass').fill(password)
      await page.getByRole('button', { name: 'Sign in' }).click()

      // OTP field revealed by the challenge.
      const codeField = page.locator('#login-code')
      await expect(codeField).toBeVisible()

      // A deterministically-wrong 6-digit code → backend 403 → the SPA shows the
      // dedicated invalid-code message and keeps the field visible for a retry.
      // (One wrong attempt is well under the 5-attempt lockout threshold.)
      await codeField.fill('000000')
      await page.getByRole('button', { name: 'Sign in' }).click()

      await expect(page.getByRole('alert')).toContainText('Invalid validation code')
      await expect(codeField).toBeVisible()
      await expect(page).toHaveURL(/#\/login/)
      await expect(page.getByRole('button', { name: 'Logout' })).toHaveCount(0)
    } finally {
      await deleteUser(baseURL!, username)
    }
  })

  test('editing the username after a challenge retracts the OTP field', async ({ page, baseURL }) => {
    const { username, password } = await seedTotpUser(baseURL!)
    try {
      await page.goto('/#/login')
      await page.getByLabel('Username').fill(username)
      await page.locator('#login-pass').fill(password)
      await page.getByRole('button', { name: 'Sign in' }).click()
      await expect(page.locator('#login-code')).toBeVisible()

      // Retyping the username must hide the code prompt so a code meant for one
      // account can't be submitted against another (Login.vue watch guard).
      await page.getByLabel('Username').fill(username + 'x')
      await expect(page.locator('#login-code')).toHaveCount(0)
    } finally {
      await deleteUser(baseURL!, username)
    }
  })
})
