import { test, expect } from '@playwright/test'

// OIDC settings smoke (#44): the admin opens /settings/oidc, sees the enabled toggle reveal the
// provider/claim fields, the client secret is masked (a Password input, never a plaintext value),
// and saving a disabled config succeeds without error. Runs as the default admin (storageState).
// No live IdP is needed — this drives the config UI only. Idempotent: it leaves OIDC disabled.

test.describe('OIDC settings', () => {
  test('the admin can open the OIDC settings and see the fields with a masked secret', async ({ page }) => {
    await page.goto('/#/settings/oidc')
    await expect(page.getByRole('heading', { name: 'OIDC authentication' })).toBeVisible()

    // Toggle on — the provider/claim fields appear.
    await page.locator('#oidc-enabled').click()
    await expect(page.locator('#oidc-issuer')).toBeVisible()
    await expect(page.locator('#oidc-client-id')).toBeVisible()
    await expect(page.locator('#oidc-username-claim')).toBeVisible()
    await expect(page.locator('#oidc-email-claim')).toBeVisible()

    // The client secret is a masked Password input: its type is "password", never plaintext.
    const secret = page.locator('#oidc-client-secret')
    await expect(secret).toBeVisible()
    await expect(secret).toHaveAttribute('type', 'password')

    // #59: the verbatim-username opt-in toggle is present in the enabled form.
    await expect(page.locator('#oidc-username-verbatim')).toBeVisible()

    // Toggle back off — fields collapse.
    await page.locator('#oidc-enabled').click()
    await expect(page.locator('#oidc-issuer')).toHaveCount(0)
    await expect(page.locator('#oidc-username-verbatim')).toHaveCount(0)
  })

  test('saving a disabled OIDC config succeeds without error', async ({ page }) => {
    await page.goto('/#/settings/oidc')
    await expect(page.getByRole('heading', { name: 'OIDC authentication' })).toBeVisible()

    // Ensure disabled (default), then save — no required fields apply when disabled.
    await expect(page.locator('#oidc-issuer')).toHaveCount(0)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('OIDC configuration saved')).toBeVisible()
  })
})
