import { test, expect, type Page } from './fixtures'
import {
  unique,
  uniqueTag,
  login,
  deleteDocApi,
  deleteTagByNameApi,
  deleteUserApi,
  ROUTE_ROOT,
  gotoRouteReady,
  gotoRaw,
  expectRouteReady,
} from './helpers'

// #280 — a tag can carry synonyms, and a synonym is a second name FOR THAT TAG: it finds the
// tag's documents in search, it offers the tag in a tag input, and it never crosses a permission
// boundary. This spec walks the reporter's own flow end to end and then checks the boundary:
//
//   1. give a tag a synonym on its edit page,
//   2. type the SYNONYM in the document editor's tag field and get the TAG offered, labelled
//      with the reason,
//   3. take that option and save — the document carries the CANONICAL tag,
//   4. search `tag:<synonym>` and find the document,
//   5. a second account, which cannot read that tag, resolves the same word to nothing.
//
// Everything the spec needs it creates itself: its own tag names, its own document, its own
// second user. It never asserts against tags or documents it did not make.

async function createTag(page: Page, name: string): Promise<void> {
  await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
  await page.getByPlaceholder('Tag name').fill(name)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.locator('.tag-tree').getByText(name, { exact: true })).toBeVisible()
}

/** Open a tag's edit page from the management tree. */
async function openTagEdit(page: Page, name: string): Promise<void> {
  await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
  await page.locator('.tag-tree').getByText(name, { exact: true }).click()
  await expect(page).toHaveURL(/#\/tag\//)
  await expect(page.locator('#tag-synonym')).toBeVisible()
}

async function addSynonym(page: Page, synonym: string): Promise<void> {
  await page.locator('#tag-synonym').fill(synonym)
  await page.locator('.synonym-row').getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.locator('.synonym-chip', { hasText: synonym })).toBeVisible()
}

/**
 * A full app reload. Hash-route navigation is a same-document change, so the SPA's cached tag
 * list (staleTime 60s) would answer from memory — and a synonym that has just been saved has to
 * reach the tag INPUTS, not only the form that wrote it.
 */
async function freshGoto(p: Page, url: string, routeRoot: string): Promise<void> {
  await gotoRaw(p, url)
  await p.reload()
  await expectRouteReady(p, url, routeRoot)
}

test('a synonym offers and finds its canonical tag, and never crosses a permission boundary', async ({
  page,
  browser,
  cleanup,
}) => {
  const tagName = uniqueTag('syn-tag')
  const synonym = uniqueTag('syn-alt')
  const docTitle = unique('syn-doc')
  const username = unique('synuser').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const email = `${username}@example.com`
  const password = 'Password1e2e'

  // --- Admin: a tag, and a synonym on it ---
  await createTag(page, tagName)
  cleanup.defer('delete the tag', () => deleteTagByNameApi(page.request, tagName))
  await openTagEdit(page, tagName)
  await addSynonym(page, synonym)
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Tag updated')).toBeVisible()

  // It is STORED, not merely on screen: a reload re-reads the tag from the server.
  await page.reload()
  await expect(page.locator('.synonym-chip', { hasText: synonym })).toBeVisible()

  // --- Admin: the document editor's tag field offers the TAG for the typed SYNONYM ---
  await freshGoto(page, '/#/document/add', ROUTE_ROOT.documentEdit)
  await page.locator('#edit-title').fill(docTitle)
  await page.locator('#edit-tags').click()
  await page.locator('input.tp-filter-input').fill(synonym)
  // The option is the CANONICAL tag, and it says why it is being offered.
  const viaOption = page.getByRole('option', { name: `${tagName} (via ${synonym})` })
  await expect(viaOption).toBeVisible()
  await viaOption.click()
  await page.keyboard.press('Escape')

  // What was assigned is the canonical tag — the chip carries the tag's own name.
  await expect(page.locator('#edit-tags').getByText(tagName, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
  const docId = page.url().split('/document/view/')[1].split(/[/?#]/)[0]
  cleanup.defer('purge the tagged document', () => deleteDocApi(page.request, docId))

  // --- Admin: searching the synonym finds the document the canonical tag is on ---
  await freshGoto(page, '/#/document', ROUTE_ROOT.documentList)
  const search = page.getByPlaceholder('Search')
  await search.fill(`tag:${synonym}`)
  await expect(page.getByText(docTitle, { exact: true })).toBeVisible()
  // ...and so does the canonical name, which the synonym has not replaced.
  await search.fill(`tag:${tagName}`)
  await expect(page.getByText(docTitle, { exact: true })).toBeVisible()
  await search.fill('')

  // --- A second account, with no access to that tag ---
  await gotoRouteReady(page, '/#/settings/users', ROUTE_ROOT.settingsUsers)
  await page.getByRole('button', { name: 'Add user' }).click()
  const userDialog = page.getByRole('dialog', { name: 'Add user' })
  await userDialog.locator('#add-user-name').fill(username)
  await userDialog.locator('#add-user-email').fill(email)
  await userDialog.locator('#add-user-pass').fill(password)
  await userDialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()
  cleanup.defer('delete the second user', () => deleteUserApi(page.request, username))

  const userCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  cleanup.defer('close the second user context', () => userCtx.close())
  const userPage = await userCtx.newPage()
  await login(userPage, username, password)

  // The word resolves to NOTHING for them: a synonym must not be a way to reach a tag — or a
  // document — the account cannot see. (An unresolvable tag term returns no documents at all,
  // which is what makes the absence of the title meaningful rather than merely unfiltered.)
  await freshGoto(userPage, '/#/document', ROUTE_ROOT.documentList)
  await userPage.getByPlaceholder('Search').fill(`tag:${synonym}`)
  await expect(userPage.getByText(docTitle, { exact: true })).toHaveCount(0)
  await expect(userPage.getByText(tagName, { exact: true })).toHaveCount(0)
})

test('a synonym that is already another tag name is refused at save, naming the conflict', async ({
  page,
  cleanup,
}) => {
  const targetTag = uniqueTag('syncol-a')
  const otherTag = uniqueTag('syncol-b')

  await createTag(page, targetTag)
  cleanup.defer('delete the edited tag', () => deleteTagByNameApi(page.request, targetTag))
  await createTag(page, otherTag)
  cleanup.defer('delete the conflicting tag', () => deleteTagByNameApi(page.request, otherTag))

  await openTagEdit(page, targetTag)
  await page.locator('#tag-synonym').fill(otherTag)

  // The form warns BEFORE the save — the reporter asked to see the word is taken while typing.
  await expect(page.locator('.synonym-notice.warn')).toContainText(otherTag)

  // The save is still the authority, and it refuses by NAME rather than with a generic failure.
  await page.locator('.synonym-row').getByRole('button', { name: 'Add', exact: true }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Failed to update tag')).toBeVisible()
  await expect(page.locator('.p-toast')).toContainText(otherTag)

  // Nothing was stored: a reload comes back with no chips at all.
  await page.reload()
  await expect(page.locator('.synonym-chip')).toHaveCount(0)
})
