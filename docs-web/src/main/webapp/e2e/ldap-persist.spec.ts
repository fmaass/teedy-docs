import { test, expect } from './fixtures'

// #83 regression: LDAP connection settings must survive a disable and REPOPULATE the admin form
// after the settings page is reloaded. The bug: GET /app/config_ldap returned only {enabled:false}
// once LDAP was disabled, so re-opening the page showed empty fields even though the values were
// still stored in T_CONFIG. This drives the REAL admin UI (no live LDAP server needed — config CRUD
// only) and asserts the fields come back after a full reload.
//
// RED before the fix: after the reload + re-enable, #ldap-host is empty (the disabled GET dropped
// every field) and the host assertion fails. GREEN after: the retained values repopulate.

const HOST = 'ldap.persist.example.com'
const ADMIN_DN = 'cn=admin,dc=persist,dc=example,dc=com'
const BASE_DN = 'dc=persist,dc=example,dc=com'
const FILTER = '(uid=USERNAME)'
const EMAIL = 'ldap-default@persist.example.com'

test.describe('LDAP settings persistence (#83)', () => {
  test('retained settings repopulate the form after disable + reload', async ({ page }) => {
    // 1. Configure and enable LDAP through the UI.
    await page.goto('/#/settings/ldap')
    await expect(page.getByRole('heading', { name: 'LDAP authentication' })).toBeVisible()
    await page.locator('#ldap-enabled').click()
    await expect(page.locator('#ldap-host')).toBeVisible()

    await page.locator('#ldap-host').fill(HOST)
    await page.locator('#ldap-admin-dn').fill(ADMIN_DN)
    await page.locator('#ldap-admin-password').fill('bind-secret')
    await page.locator('#ldap-base-dn').fill(BASE_DN)
    await page.locator('#ldap-filter').fill(FILTER)
    await page.locator('#ldap-default-email').fill(EMAIL)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    // The two saves emit the SAME success toast (life 2000ms). On slower CI the first toast can still
    // be showing when the second appears, so scope to .first() — two identical toasts would otherwise
    // trip Playwright's strict-mode "resolved to 2 elements" and flake the assertion.
    await expect(page.getByText('LDAP configuration saved').first()).toBeVisible()

    // 2. Disable LDAP (only 'enabled' is persisted false; the connection settings remain stored).
    await page.locator('#ldap-enabled').click()
    await expect(page.locator('#ldap-host')).toHaveCount(0)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('LDAP configuration saved').first()).toBeVisible()

    try {
      // 3. FULL page reload → brand-new SPA (fresh QueryClient + a fresh SettingsLdap mount that
      //    re-seeds its form from a fresh GET /app/config_ldap). This is load-bearing: the component
      //    seeds its form ONCE (a `seeded` guard) and disable only flips `enabled` in the in-memory
      //    reactive form, so a same-route hash nav would NOT refetch and the test would pass on stale
      //    in-memory values even if the #83 GET bug were present. Only a real reload exercises the GET
      //    path where #83 lived.
      await page.reload()
      await expect(page.getByRole('heading', { name: 'LDAP authentication' })).toBeVisible()

      // 4. Re-enable and assert the retained settings repopulated the form.
      await page.locator('#ldap-enabled').click()
      await expect(page.locator('#ldap-host')).toBeVisible()
      await expect(page.locator('#ldap-host')).toHaveValue(HOST)
      await expect(page.locator('#ldap-admin-dn')).toHaveValue(ADMIN_DN)
      await expect(page.locator('#ldap-base-dn')).toHaveValue(BASE_DN)
      await expect(page.locator('#ldap-filter')).toHaveValue(FILTER)
      await expect(page.locator('#ldap-default-email')).toHaveValue(EMAIL)
    } finally {
      // Cleanup: leave LDAP disabled so this spec does not affect others.
      await page.goto('/#/settings/ldap').catch(() => {})
      const enabled = page.locator('#ldap-enabled')
      if (await enabled.isChecked().catch(() => false)) {
        await enabled.click().catch(() => {})
      }
      await page.getByRole('button', { name: 'Save', exact: true }).click().catch(() => {})
    }
  })
})
