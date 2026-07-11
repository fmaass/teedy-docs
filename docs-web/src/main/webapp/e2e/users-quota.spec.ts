import { test, expect } from '@playwright/test'

// Per-user storage quota (#37): the create and edit dialogs expose a quota field, and editing
// a user's quota is reflected in the users table after save (post-refresh barrier). Runs as the
// default admin (storageState). Idempotent: it edits the guest user's quota (always present) and
// restores it, so a re-run never depends on leftover state.

test.describe('User storage quota', () => {
  test('the create dialog exposes a quota field defaulting to ~1GB', async ({ page }) => {
    await page.goto('/#/settings/users')
    await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible()

    await page.getByRole('button', { name: 'Add user' }).click()
    const quota = page.locator('#add-user-quota')
    await expect(quota).toBeVisible()
    // The create default is the ~1GB value, now a visible field.
    await expect(quota).toHaveValue(/1[.,]?000[.,]?000[.,]?000/)
    // Close without creating.
    await page.getByRole('button', { name: 'Cancel' }).click()
  })

  test('editing a user quota is reflected in the users table after save', async ({ page }) => {
    await page.goto('/#/settings/users')
    await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible()

    // Open the edit dialog for the guest row (present in every install).
    const guestRow = page.getByRole('row', { name: /guest/ })
    await guestRow.getByRole('button', { name: 'Edit' }).click()

    const quota = page.locator('#edit-user-quota')
    await expect(quota).toBeVisible()
    // Pre-filled from the user's current quota (non-empty).
    const original = await quota.inputValue()

    // Set a distinctive new quota and save. 2,500,000,000 bytes renders as "2.3 GB" via
    // formatStorage (2500000000 / 1024^3 = 2.33 -> one decimal). Asserting the SPECIFIC value
    // (not just "GB") distinguishes the saved quota from the original.
    const newQuota = '2500000000'
    const expectedRendered = '2.3 GB'
    await quota.fill(newQuota)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('User updated')).toBeVisible()

    // Post-refresh barrier: reload the users list and confirm the table reflects the new quota
    // as the exact rendered value, proving the save (not the pre-existing quota) is shown.
    await page.reload()
    await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible()
    await expect(page.getByRole('row', { name: /guest/ })).toContainText(expectedRendered)

    // Restore the original quota so the run is idempotent.
    await page.getByRole('row', { name: /guest/ }).getByRole('button', { name: 'Edit' }).click()
    await page.locator('#edit-user-quota').fill(original.replace(/[^\d]/g, ''))
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('User updated')).toBeVisible()
  })
})
