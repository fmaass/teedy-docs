import { test, expect } from './fixtures'

// #82 regression: a per-user UI language chosen in Settings must be stored SERVER-SIDE and reseed a
// FRESH device on the next login. Product behaviour proven end-to-end: pick Deutsch, log out, clear
// localStorage (simulate a brand-new device with no on-device choice), log back in → the UI comes up
// in German because the auth store seeds the locale from the server-side preference.
//
// Runs UNauthenticated (its own clean storageState) so it exercises the real form login twice and
// does not disturb the shared admin storageState. Restores the server preference to English in a
// finally so later specs see the English baseline.

const DE_ACCOUNT_TITLE = 'Benutzerkonto' // ui.account.title in de.json
const EN_ACCOUNT_TITLE = 'User account' // ui.account.title in en.json

async function login(page: import('./fixtures').Page): Promise<void> {
  await page.goto('/#/login')
  await page.getByLabel('Username').fill('admin')
  await page.locator('#login-pass').fill('admin')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/#\/document$/)
}

test.describe('per-user UI language persistence (#82)', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('language chosen in settings reseeds a fresh device after re-login', async ({ page }) => {
    try {
      // 1. Log in and choose Deutsch in Settings → Account (writes localStorage AND POSTs to /user).
      await login(page)
      await page.goto('/#/settings/account')
      await expect(page.getByRole('heading', { name: EN_ACCOUNT_TITLE })).toBeVisible()
      await page.locator('#account-locale').click()
      await page.getByRole('option', { name: 'Deutsch', exact: true }).click()
      await expect(page.getByRole('heading', { name: DE_ACCOUNT_TITLE })).toBeVisible()

      // 2. Log out (the header logout aria-label is "Logout" in both en and de).
      await page.getByRole('button', { name: 'Logout' }).click()
      await expect(page).toHaveURL(/#\/login/)

      // 3. Simulate a FRESH device: drop the on-device locale choice entirely.
      await page.evaluate(() => localStorage.removeItem('teedy-locale'))

      // 4. Log back in — the server-side preference (de) must reseed the UI language.
      await login(page)
      await page.goto('/#/settings/account')
      await expect(page.getByRole('heading', { name: DE_ACCOUNT_TITLE })).toBeVisible()
    } finally {
      // Restore the server-side preference to English so later specs see the English baseline.
      await page.request.post('/api/user', { form: { locale: 'en' } }).catch(() => {})
      await page.evaluate(() => localStorage.setItem('teedy-locale', 'en')).catch(() => {})
    }
  })
})
