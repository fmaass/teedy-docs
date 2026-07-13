import { test, expect } from './fixtures'

// The admin LDAP settings screen (/settings/ldap): the enabled toggle reveals the
// fields; client-side validation blocks save (filter must contain USERNAME;
// required-when-enabled fields show inline errors); a DISABLED config saves fine.
// No real LDAP server is needed — this drives the config UI + validation only.

test.describe('LDAP settings', () => {
  test('enabled toggle reveals the fields; disabled hides them', async ({ page }) => {
    await page.goto('/#/settings/ldap')
    await expect(page.getByRole('heading', { name: 'LDAP authentication' })).toBeVisible()

    // Disabled by default (default admin config): the host field is not rendered.
    await expect(page.locator('#ldap-host')).toHaveCount(0)

    // Toggle on — the connection fields appear.
    await page.locator('#ldap-enabled').click()
    await expect(page.locator('#ldap-host')).toBeVisible()
    await expect(page.locator('#ldap-base-dn')).toBeVisible()
    await expect(page.locator('#ldap-filter')).toBeVisible()

    // Toggle off — fields collapse again.
    await page.locator('#ldap-enabled').click()
    await expect(page.locator('#ldap-host')).toHaveCount(0)
  })

  test('saving a disabled config succeeds', async ({ page }) => {
    await page.goto('/#/settings/ldap')
    await expect(page.getByRole('heading', { name: 'LDAP authentication' })).toBeVisible()
    // Ensure disabled state, then save — no required fields apply when disabled.
    await expect(page.locator('#ldap-host')).toHaveCount(0)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('LDAP configuration saved')).toBeVisible()
  })

  test('required-when-enabled fields and the USERNAME filter rule block save', async ({ page }) => {
    await page.goto('/#/settings/ldap')
    await page.locator('#ldap-enabled').click()
    await expect(page.locator('#ldap-host')).toBeVisible()

    // Clear the required host and break the filter (remove USERNAME), then save.
    await page.locator('#ldap-host').fill('')
    await page.locator('#ldap-filter').fill('(objectClass=user)')
    await page.getByRole('button', { name: 'Save', exact: true }).click()

    // Inline errors appear and the config is NOT saved (validation blocks it).
    await expect(page.getByText('Host is required.')).toBeVisible()
    await expect(page.getByText('The filter must contain USERNAME.')).toBeVisible()
    await expect(page.getByText('LDAP configuration saved')).toHaveCount(0)
  })
})
