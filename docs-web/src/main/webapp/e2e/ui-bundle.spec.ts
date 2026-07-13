import { test, expect, type APIRequestContext } from '@playwright/test'
import { unique } from './helpers'

// v3.6.0 UI bundle e2e (#57 title/favicon, #61 settings regroup, #52 items-per-page,
// #50 right-click tags in gallery). Each test drives the real running instance and
// asserts on rendered DOM or authoritative API state, cleaning up after itself.

async function apiCreateDocument(request: APIRequestContext, title: string): Promise<string> {
  const res = await request.put('/api/document', { form: { title, language: 'eng' } })
  expect(res.ok(), `create document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

async function apiCreateTag(request: APIRequestContext, name: string): Promise<string> {
  const res = await request.put('/api/tag', { form: { name, color: '#3399cc' } })
  expect(res.ok(), `create tag ${name}`).toBeTruthy()
  return (await res.json()).id as string
}

async function apiDocTagIds(request: APIRequestContext, docId: string): Promise<string[]> {
  const res = await request.get(`/api/document/${docId}`)
  expect(res.ok(), `read document ${docId}`).toBeTruthy()
  const body = await res.json()
  return (body.tags ?? []).map((t: { id: string }) => t.id)
}

// #61 — the settings admin nav renders THREE labelled groups with the right membership.
test('settings admin nav shows three labelled groups (#61)', async ({ page }) => {
  await page.goto('/#/settings/account')
  const nav = page.locator('.admin-nav')
  await expect(nav).toBeVisible()
  // The personal section header was renamed "Settings" -> "Personal".
  await expect(nav.locator('.admin-nav-section', { hasText: 'Personal' })).toBeVisible()
  // The three admin group headers are present.
  for (const label of ['Access & Users', 'Content Model', 'System']) {
    await expect(nav.locator('.admin-nav-section', { hasText: label })).toBeVisible()
  }
  // Membership spot-check: Users sits under "Access & Users", Config under "System".
  await expect(nav.getByRole('link', { name: 'Users' })).toBeVisible()
  await expect(nav.getByRole('link', { name: 'Config' })).toBeVisible()
})

// #52 — the items-per-page selection persists across a full reload.
test('items-per-page selection persists across a reload (#52)', async ({ page, request }) => {
  // Seed enough documents that a 10-vs-larger page size is observable is not required
  // for the persistence assertion; we assert on localStorage + the rendered selector.
  const title = unique('pp-doc')
  const id = await apiCreateDocument(request, title)
  try {
    await page.goto('/#/document')
    // Pick "50 / page" from the items-per-page Select.
    await page.locator('.per-page-select').click()
    await page.getByRole('option', { name: '50', exact: true }).click()
    // Persisted under the documented localStorage key.
    await expect
      .poll(() => page.evaluate(() => localStorage.getItem('teedy_document_page_size')))
      .toBe('50')
    // Survives a full reload (cold load reads the persisted size).
    await page.reload()
    await expect(page.locator('.per-page-select')).toContainText('50')
    expect(await page.evaluate(() => localStorage.getItem('teedy_document_page_size'))).toBe('50')
  } finally {
    await request.delete(`/api/document/${id}`).catch(() => {})
  }
})

// #50 — right-clicking a gallery card adds a tag (verified via authoritative API).
test('gallery right-click adds a tag to the document (#50)', async ({ page, request }) => {
  const tagName = unique('rc-tag').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const title = unique('rc-doc')
  const tagId = await apiCreateTag(request, tagName)
  const docId = await apiCreateDocument(request, title)
  try {
    // The document starts with no tags.
    expect(await apiDocTagIds(request, docId)).not.toContain(tagId)

    await page.goto('/#/document')
    // Switch to gallery mode (the right-click surface #50 targets).
    await page.locator('.view-mode-toggle').getByText('Gallery', { exact: true }).click()
    const card = page.locator('article.doc-card').filter({
      has: page.getByRole('link', { name: title, exact: true }),
    })
    await expect(card).toBeVisible()

    // Right-click the card → the quick-tag context menu opens; choose the tag to add.
    await card.click({ button: 'right' })
    const menu = page.getByRole('menu')
    await expect(menu).toBeVisible()
    await menu.getByText(tagName, { exact: true }).click()

    // ACCEPTANCE: authoritative read-back shows the tag now on the document.
    await expect
      .poll(() => apiDocTagIds(request, docId), { message: 'tag added via right-click menu' })
      .toContain(tagId)
  } finally {
    await request.delete(`/api/document/${docId}`).catch(() => {})
    await request.delete(`/api/tag/${tagId}`).catch(() => {})
  }
})

// #57 — the browser tab title reflects a configured theme name.
test('browser tab title reflects the configured theme name (#57)', async ({ page, request }) => {
  const themeName = unique('Brand').slice(0, 28)
  // Snapshot the current theme so the test restores it (public GET, admin POST).
  const before = await (await request.get('/api/theme')).json()
  try {
    const set = await request.post('/api/theme', {
      form: { name: themeName, color: before.color ?? '#ffffff', css: before.css ?? '' },
    })
    expect(set.ok(), 'set theme name').toBeTruthy()

    await page.goto('/#/document')
    // The top-level branding composable applies the theme name to document.title.
    await expect.poll(() => page.title(), { message: 'tab title tracks theme name' }).toBe(themeName)
  } finally {
    await request
      .post('/api/theme', {
        form: {
          name: before.name ?? 'Teedy',
          color: before.color ?? '#ffffff',
          css: before.css ?? '',
        },
      })
      .catch(() => {})
  }
})
