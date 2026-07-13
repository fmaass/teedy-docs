import { test, expect, type ConsoleMessage } from '@playwright/test'
import { openNav } from './helpers'

// Runs authenticated (project-wide admin storageState). Verifies the app shell +
// main nav render and the primary routes load without browser console errors.
test.describe('smoke navigation', () => {
  test('app shell and main nav render after login', async ({ page }) => {
    await page.goto('/#/document')
    // The brand link + the two footer nav links live in the desktop side panel OR
    // the mobile Drawer. openNav() resolves to whichever is live (opening the Drawer
    // on mobile), so the SAME anchors are asserted at both viewports.
    const nav = await openNav(page)
    await expect(nav.getByRole('link', { name: 'teedy' }).first()).toBeVisible()
    await expect(nav.getByRole('link', { name: 'Manage tags' })).toBeVisible()
    await expect(nav.getByRole('link', { name: 'Settings' })).toBeVisible()
  })

  test('primary routes load without console errors', async ({ page }) => {
    const consoleErrors: string[] = []
    page.on('console', (msg: ConsoleMessage) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })
    page.on('pageerror', (err) => consoleErrors.push(err.message))

    // The header "About" action renders on the authenticated shell at BOTH
    // viewports (the brand link is hidden inside the closed Drawer on mobile), so
    // it's the viewport-agnostic "shell rendered" anchor for each route.
    const shellReady = page.getByRole('button', { name: 'About', exact: true })

    // Documents list
    await page.goto('/#/document')
    await expect(shellReady).toBeVisible()

    // Tags management
    await page.goto('/#/tag')
    await expect(page).toHaveURL(/#\/tag/)
    await expect(shellReady).toBeVisible()

    // Account settings
    await page.goto('/#/settings/account')
    await expect(page).toHaveURL(/#\/settings\/account/)
    await expect(shellReady).toBeVisible()

    expect(consoleErrors, `console errors: ${consoleErrors.join(' | ')}`).toEqual([])
  })
})
