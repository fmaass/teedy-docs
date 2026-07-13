import { test, expect } from '@playwright/test'
import { unique, login, deleteUser, openNav, closeNav } from './helpers'

// #64: /settings is a landing HUB (a grouped, annotated list), not the old redirect
// to the Account form. These specs assert, against the real app:
//   - navigating to /#/settings renders the hub (its H1 + section headings), NOT the
//     Account settings form;
//   - the hub shows the grouped sections;
//   - a hub entry is a real link that navigates to its leaf settings route.
// The default project is logged in as admin, so all three admin groups are visible.

test('the /settings landing renders the hub, not the account form', async ({ page }) => {
  await page.goto('/#/settings')
  // Stays on /settings (no redirect to /settings/account).
  await expect(page).toHaveURL(/#\/settings$/)

  // The hub heading (H1 "Settings") is present…
  await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible()
  // …and this is NOT the Account form (whose "User account" H1 / username field is absent).
  await expect(page.getByRole('heading', { name: 'User account' })).toHaveCount(0)

  // The grouped section headings render (Personal always; admin groups for admin).
  await expect(page.getByRole('heading', { level: 2, name: 'Personal' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 2, name: 'Access & Users' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 2, name: 'Content Model' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 2, name: 'System' })).toBeVisible()

  // The load-bearing feature: an entry carries a one-line description.
  await expect(
    page.getByText('Your profile, password, language, two-factor authentication and active sessions.'),
  ).toBeVisible()
})

test('a hub entry navigates to its leaf settings route', async ({ page }) => {
  await page.goto('/#/settings')
  // Click the Users entry (an admin group leaf) — a real router-link.
  await page.getByRole('link', { name: /Users/ }).first().click()
  await expect(page).toHaveURL(/#\/settings\/users$/)
  await expect(page.getByRole('button', { name: 'Add user' })).toBeVisible()
})

// A NON-admin reaches the hub (it is for everyone) but sees only the Personal
// section — the three admin groups are gated on isAdmin. Created via Settings › Users
// as admin, then driven from a fresh cookie-less context.
test('a non-admin sees the hub with only the Personal section', async ({ page, browser }) => {
  const username = unique('hub').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const password = 'HubPass123'
  const email = `${username}@example.com`

  await page.goto('/#/settings/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#add-user-name').fill(username)
  await dialog.locator('#add-user-email').fill(email)
  await dialog.locator('#add-user-pass').fill(password)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()

  try {
    const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const userPage = await ctx.newPage()
    await login(userPage, username, password)

    await userPage.goto('/#/settings')
    await expect(userPage).toHaveURL(/#\/settings$/)
    // The hub renders for the non-admin: its H1 + the Personal section.
    await expect(userPage.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible()
    await expect(userPage.getByRole('heading', { level: 2, name: 'Personal' })).toBeVisible()
    // But NONE of the admin group headings are present.
    await expect(userPage.getByRole('heading', { level: 2, name: 'Access & Users' })).toHaveCount(0)
    await expect(userPage.getByRole('heading', { level: 2, name: 'Content Model' })).toHaveCount(0)
    await expect(userPage.getByRole('heading', { level: 2, name: 'System' })).toHaveCount(0)

    await ctx.close()
  } finally {
    await deleteUser(page, username)
  }
})

// #62: the running app version renders as a muted label in the sidebar footer,
// directly ABOVE the two footer nav buttons (Manage Tags / Settings), and only in
// the settings/tag (admin) context — not in the documents view. Works on both the
// desktop aside and the mobile nav Drawer.
test('the app version sits above the sidebar footer buttons on settings, absent in documents (#62)', async ({ page }) => {
  await page.goto('/#/settings')
  const nav = await openNav(page)

  const version = nav.locator('.panel-footer-version')
  await expect(version).toBeVisible()
  await expect(version).toHaveText(/^v\d+\.\d+\.\d+/)

  // It renders above the first footer nav button (Manage Tags) within the footer.
  const firstFooterLink = nav.locator('.panel-footer .footer-link').first()
  const vBox = await version.boundingBox()
  const lBox = await firstFooterLink.boundingBox()
  expect(vBox!.y).toBeLessThan(lBox!.y)
  await closeNav(page)

  // Gated to the admin (settings/tag) context — absent in the documents sidebar.
  await page.goto('/#/document')
  const docNav = await openNav(page)
  await expect(docNav.locator('.panel-footer-version')).toHaveCount(0)
  await closeNav(page)
})
