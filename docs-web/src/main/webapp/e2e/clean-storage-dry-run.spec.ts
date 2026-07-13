import { test, expect } from '@playwright/test'

// #60: the admin Config maintenance "Clean storage" action must first show a dry-run summary
// confirm ("delete N file(s), reclaim ~X") BEFORE the real cleanup runs. This proves the
// preview-before-delete flow end to end in a real browser: the confirm dialog carries the
// count/size wording from the side-effect-free dry-run endpoint, and accepting it runs the
// real cleanup (success toast). The dry-run request is observed on the wire so the test fails
// if the UI ever deletes without previewing first.
test('clean storage shows the dry-run summary confirm before deleting', async ({ page }) => {
  await page.goto('/#/settings/config')

  // The maintenance danger zone renders the Clean storage button.
  const cleanupBtn = page.getByRole('button', { name: 'Clean storage' })
  await expect(cleanupBtn).toBeVisible()

  // Observe the side-effect-free dry-run call that must precede any real cleanup.
  const dryRunResponse = page.waitForResponse(
    (r) => r.url().includes('/api/app/batch/clean_storage/dry_run') && r.request().method() === 'GET',
  )
  await cleanupBtn.click()
  const dryRun = await dryRunResponse
  expect(dryRun.ok()).toBeTruthy()
  const preview = await dryRun.json()
  expect(preview).toHaveProperty('total')
  expect(preview).toHaveProperty('reclaimed_bytes')

  // The confirm dialog opened with a preview-derived message — either the reclaim summary
  // (files to delete) or the "nothing to clean up" message for an empty run — NOT a blind
  // confirm, and the real cleanup has not run yet.
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText(/reclaim|nothing to clean up/i)

  // Accept: the real cleanup runs and reports success.
  const cleanupResponse = page.waitForResponse(
    (r) => r.url().endsWith('/api/app/batch/clean_storage') && r.request().method() === 'POST',
  )
  await dialog.getByRole('button', { name: 'Yes' }).click()
  const cleanup = await cleanupResponse
  expect(cleanup.ok()).toBeTruthy()
  await expect(page.getByText('Storage cleanup finished')).toBeVisible()
})
