import { test, expect, type Page } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import {
  unique,
  createDocument,
  login,
  deleteDocApi,
  deleteUserApi,
  ROUTE_ROOT,
  gotoRouteReady,
  gotoRaw,
  expectRouteReady,
} from './helpers'

const here = dirname(fileURLToPath(import.meta.url))
const txtFixture = resolve(here, 'fixtures/sample.txt')

// #300 slice 1 — access counters, and the privacy line drawn through them.
//
// The premise is built entirely by this spec: two fresh accounts of its own, a document of its own
// with a file of its own, and a READ grant to each account. Nothing is assumed about the instance's
// existing data — every number asserted is one this test caused.
//
// The two halves under test are opposites of each other, which is why both accounts are needed:
//   * a non-admin sees THEIR OWN count and no trace of anyone else's — not the other account's
//     number, not the other account's name, not a total that would let them derive either;
//   * an administrator sees the same document's accesses aggregated across BOTH accounts, named.
//
// Every "open" is a full page load, never an in-app navigation: the SPA caches a document detail
// for 30s, so a soft navigation inside that window serves the cached copy and reaches no server —
// it would neither record an access nor be one. A reload is what makes each open a real one.

/**
 * Open the document view EXACTLY ONCE, and wait for the route to mount.
 *
 * The arithmetic is the point: one call must produce one recorded access, so the page is either
 * navigated to (cold cache, so the navigation itself fetches the document) or reloaded (a full load
 * with an empty cache) — never both, which would count two.
 */
async function openDocument(p: Page, id: string): Promise<void> {
  const url = `/#/document/view/${id}/content`
  if (p.url().includes(`/document/view/${id}`)) {
    await p.reload()
  } else {
    await gotoRaw(p, url)
  }
  await expectRouteReady(p, url, ROUTE_ROOT.documentContent)
}

/** The access badge in the document header — the CALLER's own open count. */
function headerCount(p: Page) {
  return p.locator('.doc-header-meta .access-count .access-count-value')
}

async function createUserApi(p: Page, username: string, password: string): Promise<void> {
  const res = await p.request.put('/api/user', {
    form: {
      username,
      password,
      email: `${username}@example.com`,
      storage_quota: '100000000',
    },
  })
  expect(res.ok(), `seeding user ${username} must succeed (HTTP ${res.status()})`).toBeTruthy()
}

async function grantRead(p: Page, documentId: string, username: string): Promise<void> {
  const res = await p.request.put('/api/acl', {
    form: { source: documentId, perm: 'READ', target: username, type: 'USER' },
  })
  expect(res.ok(), `granting READ to ${username} must succeed (HTTP ${res.status()})`).toBeTruthy()
}

test('a personal access count is private to its user, while the admin ranking aggregates every user', async ({
  page,
  browser,
  cleanup,
}) => {
  const password = 'Password1e2e'
  const alice = unique('acc300a').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const bob = unique('acc300b').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const title = unique('access-counter-doc')

  // --- Admin seeds the whole premise ---
  await createUserApi(page, alice, password)
  cleanup.defer('delete the first access-counter user', () => deleteUserApi(page.request, alice))
  await createUserApi(page, bob, password)
  cleanup.defer('delete the second access-counter user', () => deleteUserApi(page.request, bob))

  const { id } = await createDocument(page, title)
  cleanup.defer('purge the access-counter document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/content`, ROUTE_ROOT.documentContent)
  await page.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(txtFixture)
  await expect(page.getByText('Files uploaded').first()).toBeVisible()

  await grantRead(page, id, alice)
  await grantRead(page, id, bob)

  // --- Alice: her own count grows with her own opens ---
  const aliceCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  cleanup.defer('close the first user context', () => aliceCtx.close())
  const alicePage = await aliceCtx.newPage()
  await login(alicePage, alice, password)

  await openDocument(alicePage, id)
  await expect(headerCount(alicePage), "Alice's first open is her first recorded access").toHaveText('1')
  await openDocument(alicePage, id)
  await expect(headerCount(alicePage)).toHaveText('2')

  // The file panel carries her own per-file count too. The seeded file is a plain text file, whose
  // grid card is an icon — the panel renders it without fetching a byte of it — so the count stays
  // at zero across those two document opens. That is the anti-double-count property: showing a
  // document is not accessing every file in it.
  await expect(
    alicePage.locator('.file-preview-label .access-count-value'),
    'rendering the file panel is not an access of the file it lists',
  ).toHaveText('0')

  // --- Bob: opens the same document three times, entirely independently ---
  const bobCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  cleanup.defer('close the second user context', () => bobCtx.close())
  const bobPage = await bobCtx.newPage()
  await login(bobPage, bob, password)

  for (const expected of ['1', '2', '3']) {
    await openDocument(bobPage, id)
    await expect(headerCount(bobPage)).toHaveText(expected)
  }

  // --- The privacy line: Bob's three opens are invisible to Alice ---
  await openDocument(alicePage, id)
  await expect(
    headerCount(alicePage),
    "Alice's count is her own opens only — Bob's three must not appear in it",
  ).toHaveText('3')
  const aliceBody = await alicePage.locator('body').innerText()
  expect(aliceBody, "Bob's username must not surface anywhere on Alice's document view").not.toContain(bob)

  // And the aggregate screen is not merely hidden from her: the route bounces a non-admin.
  await gotoRaw(alicePage, '/#/settings/stats')
  await expectRouteReady(alicePage, '/#/document', ROUTE_ROOT.documentList)

  // --- The administrator sees both users, named, on the same document ---
  await gotoRaw(page, '/#/settings/stats')
  await page.reload()
  await expectRouteReady(page, '/#/settings/stats', ROUTE_ROOT.settingsStats)

  const row = page.locator('.access-section tbody tr', { hasText: title })
  await expect(row, 'the seeded document is ranked for the administrator').toBeVisible()

  // Alice's 3 and Bob's 3 are both inside the one total. The admin's own seeding opens are in there
  // too but are not pinned to an exact number — the seeding flow's own navigations are not the
  // subject, and asserting them would make this test about the SPA's caching instead.
  const total = Number((await row.locator('td').nth(1).innerText()).trim())
  expect(total, "the administrator total spans both users' opens").toBeGreaterThanOrEqual(6)

  const userText = (await row.locator('.access-user').allInnerTexts()).join(' | ')
  expect(userText, "Alice's three opens are attributed to her by name").toContain(`${alice} · 3`)
  expect(userText, "Bob's three opens are attributed to him by name").toContain(`${bob} · 3`)
  expect(userText, 'the administrator sees their own accesses in the same breakdown').toMatch(
    /admin · \d+/,
  )
})
