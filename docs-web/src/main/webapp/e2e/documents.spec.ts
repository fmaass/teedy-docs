import { test, expect } from '@playwright/test'
import { unique } from './helpers'

// Runs authenticated. Creates a document via the real Add-document form. On save,
// Teedy routes to the full document view (DocumentEdit -> document-view). We then
// return to the list, verify the new document appears there, and open it.
test('creates a document, sees it in the list, and opens it', async ({ page }) => {
  const title = `E2E doc ${Date.now()}`

  await page.goto('/#/document/add')
  await expect(page.getByRole('heading', { name: 'New document' })).toBeVisible()

  await page.locator('#edit-title').fill(title)
  await page.getByRole('button', { name: 'Save' }).click()

  // Save routes to the full document view, whose header shows the new title.
  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()

  // Return to the list — the new document appears in the table.
  await page.goto('/#/document')
  const titleCell = page.getByText(title, { exact: true })
  await expect(titleCell).toBeVisible()

  // Open it: clicking the row opens the slide-over panel showing the title, whose
  // "Open" button routes back to the full document view.
  await titleCell.click()
  await page.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
})

// --- Behavior D (document-list UX, #11/#24/#25) ------------------------------
// Three document-list papercuts: (1) double-clicking a row navigates straight to
// the full document view; (2) a document with >3 tags collapses the extra tags
// into a focusable "+N" control whose popover (teleported to <body>, so it is not
// clipped by the DataTable's overflow) reveals the remaining tags; (3) admin/
// settings table pages render at the wider content width.
//
// REALNESS: the dblclick test asserts the URL actually became /document/view/<id>
// (single-click only opens a slide-over, so this fails if dblclick isn't wired to
// navigate). The +N test seeds a doc with 5 tags and asserts exactly a "+2" control
// whose popover reveals the 4th and 5th tag names (removing TagOverflow or the
// slice(0,3) cap fails it). The wide-width test asserts the .settings-content--wide
// class the wideSettings route flag toggles.

test('double-clicking a document row navigates to the full document view (D #11)', async ({ page }) => {
  const title = unique('D-dblclick')

  await page.goto('/#/document/add')
  await page.locator('#edit-title').fill(title)
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)

  await page.goto('/#/document')
  const row = page.getByRole('row', { name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })
  await expect(row).toBeVisible()

  // Double-click navigates straight to the full document view — no slide-over,
  // no "Open" click.
  await row.dblclick()
  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
})

test('a document with more than 3 tags shows a focusable +N control whose popover reveals the rest (D #24)', async ({ page, baseURL, request }) => {
  // Seed 5 uniquely-named tags + a document carrying all of them via REST so the
  // list row deterministically overflows (>3 tags). The tags are named so their
  // creation order is stable; the first 3 render inline, the last 2 collapse.
  const base = baseURL!
  const runId = unique('Dtag')
  const tagNames = [1, 2, 3, 4, 5].map((n) => `${runId}-${n}`)
  const tagIds: string[] = []
  for (const name of tagNames) {
    const res = await request.put(`${base}/api/tag`, { form: { name, color: '#2aabd2' } })
    expect(res.ok(), `create tag ${name}`).toBeTruthy()
    tagIds.push((await res.json()).id)
  }

  const docTitle = unique('D-overflow')
  const docForm = new URLSearchParams()
  docForm.set('title', docTitle)
  docForm.set('language', 'eng')
  for (const id of tagIds) docForm.append('tags', id)
  const docRes = await request.put(`${base}/api/document`, {
    data: docForm.toString(),
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  })
  expect(docRes.ok(), 'create tagged document').toBeTruthy()
  const docId = (await docRes.json()).id as string

  try {
    await page.goto('/#/document')
    const row = page.getByRole('row', { name: new RegExp(docTitle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })
    await expect(row).toBeVisible()

    // Exactly the first 3 tags render inline as badges; the +N control accounts for
    // the remaining 2.
    const overflow = row.getByRole('button', { name: /more tags/i })
    await expect(overflow).toBeVisible()
    await expect(overflow).toHaveText(/\+2/)

    // It is focusable (keyboard-operable) and starts collapsed.
    await overflow.focus()
    await expect(overflow).toBeFocused()
    await expect(overflow).toHaveAttribute('aria-expanded', 'false')

    // Activating it opens the popover (teleported to <body>) with the 2 hidden tags,
    // and does NOT navigate the row (still on the list).
    await overflow.click()
    await expect(overflow).toHaveAttribute('aria-expanded', 'true')
    await expect(page).toHaveURL(/#\/document$/)
    const panel = page.locator('.tag-overflow-panel')
    await expect(panel).toBeVisible()
    await expect(panel.getByText(tagNames[3], { exact: true })).toBeVisible()
    await expect(panel.getByText(tagNames[4], { exact: true })).toBeVisible()
    // The first-3 tags are NOT repeated inside the overflow popover.
    await expect(panel.getByText(tagNames[0], { exact: true })).toHaveCount(0)
  } finally {
    await request.delete(`${base}/api/document/${docId}`)
    for (const id of tagIds) await request.delete(`${base}/api/tag/${id}`)
  }
})

test('admin/settings table pages render at the wider content width (D #25)', async ({ page }) => {
  // A wideSettings route (users) opts into the full-width layout; a narrow-measure
  // route (account) keeps the 800px cap. Assert the class the flag toggles.
  await page.goto('/#/settings/users')
  await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible()
  await expect(page.locator('.settings-content.settings-content--wide')).toBeVisible()
  // And its computed max-width is unconstrained (not the 800px text cap).
  const wideMax = await page
    .locator('.settings-content')
    .evaluate((el) => getComputedStyle(el).maxWidth)
  expect(wideMax).toBe('none')

  // The narrow account page does NOT get the wide modifier.
  await page.goto('/#/settings/account')
  await expect(page.locator('.settings-content')).toBeVisible()
  await expect(page.locator('.settings-content.settings-content--wide')).toHaveCount(0)
})
