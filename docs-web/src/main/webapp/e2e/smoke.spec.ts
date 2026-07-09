import { test, expect, type ConsoleMessage } from '@playwright/test'

// Runs authenticated (project-wide admin storageState). Verifies the app shell +
// main nav render and the primary routes load without browser console errors.
test.describe('smoke navigation', () => {
  test('app shell and main nav render after login', async ({ page }) => {
    await page.goto('/#/document')
    // Left-panel brand + the two footer nav links are the stable shell anchors.
    await expect(page.getByRole('link', { name: 'teedy' }).first()).toBeVisible()
    await expect(page.getByRole('link', { name: 'Manage tags' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Settings' })).toBeVisible()
  })

  test('primary routes load without console errors', async ({ page }) => {
    const consoleErrors: string[] = []
    page.on('console', (msg: ConsoleMessage) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })
    page.on('pageerror', (err) => consoleErrors.push(err.message))

    // Documents list
    await page.goto('/#/document')
    await expect(page.getByRole('link', { name: 'teedy' }).first()).toBeVisible()

    // Tags management
    await page.goto('/#/tag')
    await expect(page).toHaveURL(/#\/tag/)
    await expect(page.getByRole('link', { name: 'teedy' }).first()).toBeVisible()

    // Account settings
    await page.goto('/#/settings/account')
    await expect(page).toHaveURL(/#\/settings\/account/)
    await expect(page.getByRole('link', { name: 'teedy' }).first()).toBeVisible()

    expect(consoleErrors, `console errors: ${consoleErrors.join(' | ')}`).toEqual([])
  })
})
