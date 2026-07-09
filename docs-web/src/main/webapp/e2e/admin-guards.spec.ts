import { test, expect } from '@playwright/test'
import { unique, login, confirmDanger } from './helpers'

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
