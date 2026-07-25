import { test, expect, type Page } from './fixtures'
import { unique, login, createDocument, deleteDocApi, deleteUser } from './helpers'

// Global activity history (#177) — the account-wide audit feed restored from the AngularJS app.
//
// The properties that matter, and that a "the page renders" test would not catch:
//   - an ADMIN sees rows authored by OTHER users (the cross-user feed), while
//   - a NON-ADMIN sees only their own rows AND no `Acl` rows — the authorization predicate must
//     survive the new filters, which are AND-composed into it server-side;
//   - filters NARROW the feed (a class filter leaves only that class), and
//   - "load older" pages the FILTERED stream rather than restarting the unfiltered one;
//   - a resolvable target navigates to the right entity.
//
// Runs at both viewports. The history view is reachable from the header button at either size, so
// no desktop/mobile fork is needed.

const historyRows = (page: Page) => page.locator('.p-datatable tbody tr')

async function gotoHistory(page: Page) {
  await page.goto('/#/history')
  await expect(page.getByRole('heading', { name: 'Activity history' })).toBeVisible()
  await expect(page.locator('.p-datatable')).toBeVisible()
}

test('the header button opens the global history view', async ({ page }) => {
  await page.goto('/#/document')
  await page.getByRole('button', { name: 'Activity history', exact: true }).click()
  await expect(page).toHaveURL(/#\/history$/)
  await expect(page.getByRole('heading', { name: 'Activity history' })).toBeVisible()
})

test('an admin sees a cross-user feed that filters narrow and pages older', async ({ page, browser, cleanup }) => {
  const username = unique('hist').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const password = 'HistoryPass123'

  // Create a second user and have THEM create a document, so the admin's feed must contain a row
  // authored by somebody else. That row is the cross-user proof.
  await page.goto('/#/settings/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#add-user-name').fill(username)
  await dialog.locator('#add-user-email').fill(`${username}@example.com`)
  await dialog.locator('#add-user-pass').fill(password)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()

  const otherTitle = unique('hist-other')
  const userContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const userPage = await userContext.newPage()
  await login(userPage, username, password)
  const otherDoc = await createDocument(userPage, otherTitle)
  // FIFO cleanup, registered at creation. The second user's document is purged on THAT user's OWN
  // context — deleteDocApi's permanent delete is owner-scoped — so it must run BEFORE the context
  // closes, hence this registration order.
  cleanup.defer("purge the second user's document", () => deleteDocApi(userPage.request, otherDoc.id))
  cleanup.defer('close the second user context', () => userContext.close())
  cleanup.defer('delete the second user', () => deleteUser(page, username))

  // --- As ADMIN: the feed spans users. ---
  await gotoHistory(page)
  await expect(historyRows(page).first()).toBeVisible()

  // The cross-user proof, asserted THROUGH the user filter rather than by scanning page 1:
  // every spec in this run shares one app instance, so unrelated rows can push any particular
  // row past the 20-row first page. Filtering server-side is volume-independent — and it
  // exercises the `user` filter at the same time.
  await page.locator('.history-filter-user').fill(username)
  await page.locator('.history-filter-user').press('Enter')
  await expect.poll(async () => historyRows(page).count()).toBeGreaterThan(0)
  const otherAuthors = await page.locator('.p-datatable tbody tr td:nth-child(2)').allTextContents()
  // An admin CAN see another user's rows — the whole point of the global feed.
  expect(new Set(otherAuthors.map((a) => a.trim()))).toEqual(new Set([username]))
  await page.locator('.history-clear-filters').click()

  // A class filter NARROWS the feed: only Document rows remain, and the entity column proves it.
  await page.locator('.history-filter-class').click()
  await page.getByRole('option', { name: 'Document', exact: true }).click()
  await expect(page.locator('.activity-class').first()).toBeVisible()
  const classCells = await page.locator('.activity-class').allTextContents()
  expect(classCells.length).toBeGreaterThan(0)
  expect(new Set(classCells)).toEqual(new Set(['Document']))

  // "Load older" pages the FILTERED stream: the class filter still holds after appending.
  const loadOlder = page.locator('.activity-load-older')
  if (await loadOlder.isVisible().catch(() => false)) {
    const before = await historyRows(page).count()
    await loadOlder.click()
    await expect.poll(() => historyRows(page).count()).toBeGreaterThan(before)
    const afterCells = await page.locator('.activity-class').allTextContents()
    expect(new Set(afterCells)).toEqual(new Set(['Document']))
  }

  // Clearing the filters restores the wider feed.
  await page.locator('.history-clear-filters').click()
  await expect.poll(async () => new Set(await page.locator('.activity-class').allTextContents()).size)
    .toBeGreaterThan(1)

  // A resolvable Document target navigates to that document.
  const link = page.locator('.activity-target-link').first()
  await expect(link).toBeVisible()
  await link.click()
  await expect(page).toHaveURL(/#\/(document|tag)/)
})

test('a non-admin sees only their own rows and never an Acl row', async ({ page, browser, cleanup }) => {
  const username = unique('histown').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const password = 'HistoryPass123'

  await page.goto('/#/settings/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#add-user-name').fill(username)
  await dialog.locator('#add-user-email').fill(`${username}@example.com`)
  await dialog.locator('#add-user-pass').fill(password)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()

  // Admin activity that must NOT leak into the non-admin's feed.
  const adminTitle = unique('hist-admin-only')
  const adminDoc = await createDocument(page, adminTitle)
  cleanup.defer("purge the admin's document", () => deleteDocApi(page.request, adminDoc.id))

  const userContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const userPage = await userContext.newPage()
  await login(userPage, username, password)
  // Creating a document writes the user's own Document row AND two Acl rows — the Acl rows are
  // exactly what the authorization predicate must keep hidden.
  const ownDoc = await createDocument(userPage, unique('hist-own'))
  // FIFO: purge on the OWNING user's context before that context closes (deleteDocApi's permanent
  // delete is owner-scoped), then close it, then delete the account.
  cleanup.defer("purge the non-admin's document", () => deleteDocApi(userPage.request, ownDoc.id))
  cleanup.defer('close the non-admin context', () => userContext.close())
  cleanup.defer('delete the non-admin user', () => deleteUser(page, username))

  await gotoHistory(userPage)
  await expect(historyRows(userPage).first()).toBeVisible()

  // Every row is the caller's own.
  const authors = await userPage.locator('.p-datatable tbody tr td:nth-child(2)').allTextContents()
  expect(authors.length).toBeGreaterThan(0)
  expect(new Set(authors.map((a) => a.trim()))).toEqual(new Set([username]))

  // No Acl row, unfiltered...
  let classes = await userPage.locator('.activity-class').allTextContents()
  expect(classes).not.toContain('Permission')
  // ...and no admin-authored document title either.
  await expect(userPage.getByText(adminTitle)).toHaveCount(0)

  // ...and STILL none behind a type filter — the filter must AND into the scope, never
  // replace it (the OR-composition leak this phase exists to prevent).
  await userPage.locator('.history-filter-type').click()
  await userPage.getByRole('option', { name: 'Created', exact: true }).click()
  await expect(userPage.locator('.p-datatable')).toBeVisible()
  const filteredAuthors = await userPage
    .locator('.p-datatable tbody tr td:nth-child(2)')
    .allTextContents()
  for (const author of filteredAuthors) {
    expect(author.trim()).toBe(username)
  }
  classes = await userPage.locator('.activity-class').allTextContents()
  expect(classes).not.toContain('Permission')
})
