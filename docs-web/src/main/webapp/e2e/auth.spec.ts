import { test, expect } from '@playwright/test'

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
    // Authenticated shell: the left-panel brand + logout control only render when
    // a real session exists.
    await expect(page.getByRole('link', { name: 'teedy' }).first()).toBeVisible()
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
