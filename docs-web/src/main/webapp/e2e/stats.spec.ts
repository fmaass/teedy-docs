import { test, expect, type APIRequestContext } from '@playwright/test'
import { unique, login } from './helpers'

// #40: admin-only global statistics dashboard. As admin, seed a document (so the totals and
// the documents-created series are non-empty), open the dashboard, assert the totals row and a
// rendered chart canvas, then switch the window and assert the refetch happened against the
// real /app/stats endpoint (a network barrier, not a cosmetic check). Finally assert a NON-admin
// gets no data: the API is 403 and the direct-URL route bounces to the document list.
//
// DETERMINISM: the window switch is verified by WAITING for the GET /app/stats?window=30
// response (an authoritative barrier — the new query really ran), then asserting the canvas is
// present. Chart.js renders into a <canvas>; we assert the canvas element exists per chart.

async function apiCreateDocument(request: APIRequestContext, title: string): Promise<string> {
  const res = await request.put('/api/document', { form: { title, language: 'eng' } })
  expect(res.ok(), `create document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

test('admin opens the stats dashboard, sees totals and charts, and switches window (#40)', async ({
  page,
  request,
}) => {
  const title = unique('stats-doc')
  let docId: string | undefined

  try {
    docId = await apiCreateDocument(request, title)

    // Navigate to the dashboard and wait for the initial window=7 fetch to resolve.
    const initial = page.waitForResponse(
      (r) => r.url().includes('/api/app/stats') && r.url().includes('window=7') && r.status() === 200,
    )
    await page.goto('/#/settings/stats')
    await initial

    // Totals row: the five labelled cards are present (scoped to the totals grid so the
    // admin nav's own "Users"/"Tags" links can never ambiguate the match).
    const totals = page.locator('.totals-grid')
    await expect(totals).toBeVisible()
    for (const label of ['Documents', 'Files (all versions)', 'Users', 'Tags', 'Favorites']) {
      await expect(totals.getByText(label, { exact: true })).toBeVisible()
    }
    // The five cards each carry a numeric value.
    expect(await totals.locator('.total-value').count()).toBe(5)

    // At least one chart rendered: chart.js draws into a <canvas>. Both series produce a canvas.
    const canvases = page.locator('.chart-canvas-wrap canvas')
    await expect(canvases.first()).toBeVisible()
    expect(await canvases.count()).toBeGreaterThanOrEqual(1)

    // The per-user storage table shows the admin user (who owns the seeded doc's storage row).
    await expect(page.getByRole('cell', { name: 'admin', exact: true }).first()).toBeVisible()

    // Switch the window to 30 days: a real refetch against /app/stats?window=30 is the barrier.
    const switched = page.waitForResponse(
      (r) => r.url().includes('/api/app/stats') && r.url().includes('window=30') && r.status() === 200,
    )
    await page.getByRole('button', { name: '30 days' }).click()
    await switched

    // After the refetch the chart canvas is still rendered (now for the 30-day series).
    await expect(page.locator('.chart-canvas-wrap canvas').first()).toBeVisible()
  } finally {
    if (docId) await request.delete(`/api/document/${docId}`).catch(() => {})
  }
})

test('a non-admin gets 403 from the stats API and is redirected away from the route (#40)', async ({
  page,
  browser,
}) => {
  const username = unique('statsguard').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const password = 'StatsGuard123'
  const email = `${username}@example.com`

  // As admin: create the non-admin user.
  await page.goto('/#/settings/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#add-user-name').fill(username)
  await dialog.locator('#add-user-email').fill(email)
  await dialog.locator('#add-user-pass').fill(password)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()

  try {
    const userContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const userPage = await userContext.newPage()
    await login(userPage, username, password)

    // The stats API is admin-only: a non-admin call is 403 and returns no stats payload.
    const apiRes = await userContext.request.get('/api/app/stats?window=7')
    expect(apiRes.status()).toBe(403)
    const body = await apiRes.text()
    expect(body).not.toContain('"totals"')

    // Direct-URL navigation to the admin route bounces to the documents list (no dashboard).
    await userPage.goto('/#/settings/stats')
    await expect(userPage).toHaveURL(/#\/document$/)
    await expect(userPage.locator('.chart-canvas-wrap canvas')).toHaveCount(0)

    await userContext.close()
  } finally {
    // Teardown via the admin API (the admin page's authenticated context) rather than the
    // UI + toast — the delete is cleanup, not an assertion, so it must not add a flaky wait.
    await page.request.delete(`/api/user/${username}`).catch(() => {})
  }
})
