import { test, expect } from './fixtures'
import { login } from './helpers'

// #82 regression: a per-user UI language chosen in Settings must be stored SERVER-SIDE and reseed a
// FRESH device on the next login. Product behaviour proven end-to-end: pick Deutsch, log out, clear
// localStorage AND fully reload the SPA (simulate a brand-new device with no on-device choice and no
// in-memory locale), log back in → the UI comes up in German because the auth store seeds the locale
// from the server-side preference.
//
// The FULL RELOAD after clearing localStorage is load-bearing for BOTH correctness and test validity:
//   * Without it, goto('/#/login') is only a hash navigation — the SPA is never re-created, so the
//     previous session's in-memory locale ('de') survives. The login form then renders in German and
//     the English-labelled login helper times out (the original never-run failure).
//   * More importantly, the stale in-memory 'de' would make the final assertion pass EVEN IF the
//     server-side reseed were broken — a false-green. After a real reload the SPA boots at the English
//     default (i18n locale 'en', no teedy-locale), so German can ONLY reappear via the server reseed.
//     That makes the DE assertion a genuine test of the #82 behaviour.
//
// Runs UNauthenticated (its own clean storageState) so it exercises the real form login twice and
// does not disturb the shared admin storageState. Restores the server preference to English in a
// finally so later specs see the English baseline.

const DE_ACCOUNT_TITLE = 'Benutzerkonto' // ui.account.title in de.json
const EN_ACCOUNT_TITLE = 'User account' // ui.account.title in en.json

test.describe('per-user UI language persistence (#82)', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('language chosen in settings reseeds a fresh device after re-login', async ({ page }) => {
    try {
      // 1. Log in and choose Deutsch in Settings → Account (writes localStorage AND POSTs to /user).
      await login(page, 'admin', 'admin')
      await page.goto('/#/settings/account')
      await expect(page.getByRole('heading', { name: EN_ACCOUNT_TITLE })).toBeVisible()
      await page.locator('#account-locale').click()
      await page.getByRole('option', { name: 'Deutsch', exact: true }).click()
      await expect(page.getByRole('heading', { name: DE_ACCOUNT_TITLE })).toBeVisible()

      // 2. Log out (the header logout aria-label is "Logout" in both en and de).
      await page.getByRole('button', { name: 'Logout' }).click()
      await expect(page).toHaveURL(/#\/login/)

      // 3. Simulate a FRESH device: drop the on-device locale choice AND fully reboot the SPA so the
      //    in-memory locale resets to the English default — only the server-side preference can bring
      //    German back after re-login (see file header).
      await page.evaluate(() => localStorage.removeItem('teedy-locale'))
      await page.reload()
      // The reloaded, unauthenticated login form comes up in English (no on-device locale) — this
      // both settles the reload and asserts the pre-condition the login helper depends on.
      await expect(page.getByLabel('Username')).toBeVisible()

      // 4. Log back in — the server-side preference (de) must reseed the UI language.
      await login(page, 'admin', 'admin')
      await page.goto('/#/settings/account')
      await expect(page.getByRole('heading', { name: DE_ACCOUNT_TITLE })).toBeVisible()
    } finally {
      // Restore the server-side preference to English so later specs see the English baseline.
      await page.request.post('/api/user', { form: { locale: 'en' } }).catch(() => {})
      await page.evaluate(() => localStorage.setItem('teedy-locale', 'en')).catch(() => {})
    }
  })
})
