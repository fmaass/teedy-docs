import { test, expect } from './fixtures'

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
    // The create default is 1,000,000,000 bytes, entered/shown in GB on the same
    // binary basis as formatStorage (1e9 / 1024^3 = 0.93), with a " GB" suffix.
    await expect(quota).toHaveValue(/0[.,]93\s*GB/)
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
    // Pre-filled from the user's current quota (shown in GB with a " GB" suffix).
    // Keep only the numeric GB so it can be re-entered to restore.
    const original = (await quota.inputValue()).replace(/[^0-9.]/g, '')

    // Set a distinctive new quota (in GB) and save. 2.5 GB stored on the binary basis is
    // 2.5 * 1024^3 = 2,684,354,560 bytes, which formatStorage renders back as "2.5 GB".
    // Asserting the SPECIFIC value (not just "GB") distinguishes the saved quota from the original.
    const newQuota = '2.5'
    const expectedRendered = '2.5 GB'
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
    await page.locator('#edit-user-quota').fill(original)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('User updated')).toBeVisible()
  })
})
