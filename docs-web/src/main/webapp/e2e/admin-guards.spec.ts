import { test, expect } from '@playwright/test'
import { unique, login, confirmDanger } from './helpers'
// unique, login, confirmDanger are used by both the non-admin guard test and the
// behavior-B disabled-user tests below.

// Exercises resolveNavGuard: a NON-admin user reaching an admin-only route by
// direct URL is bounced to the documents list, while a non-admin route
// (/settings/account) is reachable. The non-admin is created via Settings > Users
// as admin, driven from a FRESH context (no admin storageState).

test('non-admin is redirected away from admin routes but can reach account settings', async ({ page, browser }) => {
  const username = unique('guard').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const password = 'GuardPass123'
  const email = `${username}@example.com`

  // --- As admin: create the non-admin user. ---
  await page.goto('/#/settings/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#add-user-name').fill(username)
  await dialog.locator('#add-user-email').fill(email)
  await dialog.locator('#add-user-pass').fill(password)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()

  try {
    // --- Fresh context logged in AS the non-admin user. ---
    const userContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const userPage = await userContext.newPage()
    await login(userPage, username, password)

    // A non-admin route is reachable.
    await userPage.goto('/#/settings/account')
    await expect(userPage).toHaveURL(/#\/settings\/account/)
    await expect(userPage.getByRole('link', { name: 'teedy' }).first()).toBeVisible()

    // Admin-only /settings/ldap: the guard bounces to the documents list.
    await userPage.goto('/#/settings/ldap')
    await expect(userPage).toHaveURL(/#\/document$/)
    await expect(userPage.locator('#ldap-host')).toHaveCount(0)

    // A second admin-only route (/settings/users) is likewise blocked.
    await userPage.goto('/#/settings/users')
    await expect(userPage).toHaveURL(/#\/document$/)

    await userContext.close()
  } finally {
    // --- Cleanup: delete the created user as admin. ---
    await page.goto('/#/settings/users')
    const row = page.getByRole('row', { name: new RegExp(username) })
    await row.getByRole('button', { name: 'Delete' }).click()
    await confirmDanger(page)
    await expect(page.getByText('User deleted')).toBeVisible()
  }
})

// --- Behavior B (disabled-user enforcement, #17) -----------------------------
// An admin disables a normal user from Settings → Users; that user can no longer
// log in through the native form. Re-enabling restores login. Both the disable and
// enable are driven through the REAL row toggle button (not the API), and the
// login denial / restoration is asserted by driving the actual login form in a
// fresh cookie-less context.
//
// REALNESS: the disabled login is asserted by a genuine form submit that must be
// REJECTED (stays on /login, no session) — if the backend stopped enforcing
// isDisabled(), the user would land in the app and the assertion would fail. The
// re-enable then proves the block was reversible (not a permanent delete).
test('an admin can disable a user (login denied) and re-enable them (login restored)', async ({ page, browser }) => {
  const username = unique('disc').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const password = 'DisablePass123'
  const email = `${username}@example.com`

  // --- As admin: create the user. ---
  await page.goto('/#/settings/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const addDialog = page.getByRole('dialog')
  await addDialog.locator('#add-user-name').fill(username)
  await addDialog.locator('#add-user-email').fill(email)
  await addDialog.locator('#add-user-pass').fill(password)
  await addDialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()

  try {
    const row = page.getByRole('row', { name: new RegExp(username) })

    // Sanity: the enabled user CAN log in in a fresh context.
    {
      const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
      const p = await ctx.newPage()
      await login(p, username, password)
      await expect(p).toHaveURL(/#\/document$/)
      await ctx.close()
    }

    // --- Disable via the real row toggle (opens the danger confirm). ---
    await row.getByRole('button', { name: 'Disable account' }).click()
    await confirmDanger(page)
    await expect(page.getByText('Account disabled')).toBeVisible()
    // The row now shows the DISABLED badge and the toggle flips to "Enable account".
    await expect(row.getByText('disabled', { exact: false })).toBeVisible()
    await expect(row.getByRole('button', { name: 'Enable account' })).toBeVisible()

    // --- Disabled: the native form login is DENIED. ---
    {
      const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
      const p = await ctx.newPage()
      await p.goto('/#/login')
      await p.getByLabel('Username').fill(username)
      await p.locator('#login-pass').fill(password)
      await p.getByRole('button', { name: 'Sign in' }).click()
      // A disabled account is rejected exactly like a bad password: an alert shows
      // and the form stays on /login with no authenticated session.
      await expect(p.getByRole('alert')).toBeVisible()
      await expect(p).toHaveURL(/#\/login/)
      await expect(p.getByRole('button', { name: 'Logout' })).toHaveCount(0)
      // Authoritative: the session is genuinely anonymous, not established.
      const me = await ctx.request.get('/api/user')
      expect((await me.json()).anonymous).toBe(true)
      await ctx.close()
    }

    // --- Re-enable via the row toggle (no danger confirm on enable). ---
    await row.getByRole('button', { name: 'Enable account' }).click()
    await expect(page.getByText('Account enabled')).toBeVisible()
    await expect(row.getByRole('button', { name: 'Disable account' })).toBeVisible()

    // --- Re-enabled: login is restored. ---
    {
      const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
      const p = await ctx.newPage()
      await login(p, username, password)
      await expect(p).toHaveURL(/#\/document$/)
      await ctx.close()
    }
  } finally {
    await page.goto('/#/settings/users')
    const row = page.getByRole('row', { name: new RegExp(username) })
    await row.getByRole('button', { name: 'Delete' }).click()
    await confirmDanger(page)
    await expect(page.getByText('User deleted')).toBeVisible()
  }
})

// The disable/enable toggle is HIDDEN for the guest and admin rows: the backend
// force-ignores disabling those accounts, so the UI must not present a false
// affordance (canToggleDisabled in SettingsUsers.vue). Guest and admin rows carry
// neither a "Disable account" nor an "Enable account" button; a normal user row does.
test('the disable/enable toggle is hidden for the guest and admin rows', async ({ page }) => {
  const username = unique('togg').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const password = 'TogglePass123'
  const email = `${username}@example.com`

  await page.goto('/#/settings/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const addDialog = page.getByRole('dialog')
  await addDialog.locator('#add-user-name').fill(username)
  await addDialog.locator('#add-user-email').fill(email)
  await addDialog.locator('#add-user-pass').fill(password)
  await addDialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()

  try {
    // admin row: no toggle in either state (matches "admin" exactly to avoid other rows).
    const adminRow = page.getByRole('row', { name: /(^|\s)admin(\s|$)/ }).first()
    await expect(adminRow).toBeVisible()
    await expect(adminRow.getByRole('button', { name: 'Disable account' })).toHaveCount(0)
    await expect(adminRow.getByRole('button', { name: 'Enable account' })).toHaveCount(0)

    // guest row (the built-in guest account is always listed): no toggle either.
    const guestRow = page.getByRole('row', { name: /(^|\s)guest(\s|$)/ }).first()
    await expect(guestRow).toBeVisible()
    await expect(guestRow.getByRole('button', { name: 'Disable account' })).toHaveCount(0)
    await expect(guestRow.getByRole('button', { name: 'Enable account' })).toHaveCount(0)

    // A normal user row DOES expose the toggle — proving the hide above is
    // account-specific, not a blanket absence of the control.
    const userRow = page.getByRole('row', { name: new RegExp(username) })
    await expect(userRow.getByRole('button', { name: 'Disable account' })).toBeVisible()
  } finally {
    await page.goto('/#/settings/users')
    const row = page.getByRole('row', { name: new RegExp(username) })
    await row.getByRole('button', { name: 'Delete' }).click()
    await confirmDanger(page)
    await expect(page.getByText('User deleted')).toBeVisible()
  }
})
