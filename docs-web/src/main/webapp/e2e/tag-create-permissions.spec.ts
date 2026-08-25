import { test, expect, type Page, type APIRequestContext } from './fixtures'
import {
  unique,
  uniqueTag,
  login,
  deleteDocApi,
  deleteTagByNameApi,
  deleteUserApi,
  expectResponseOk,
  ROUTE_ROOT,
  gotoRouteReady,
  gotoRaw,
  expectRouteReady,
} from './helpers'

// #306 — permissions set DURING creation, on the tag management page.
//
// Until now this page could only create a bare tag: the permissions section lived on the tag's
// own edit page, so sharing a new tag meant create -> find it in the tree -> open it -> grant.
// The create card now opens the same full form the #288 side panel and the edit page host, and
// the grants chosen in it are applied to the tag the moment the server hands back an id.
//
// The premise is built by this spec, not inherited: its own second account, its own two tags —
// one created WITH a read grant for that account and one without — and one document per tag. The
// discriminator is the pair: the granted tag and its document are visible to the second account,
// the ungranted ones are not. A single-tag version would pass on an instance that simply shows
// everything to everyone.

async function apiCreateDoc(
  request: APIRequestContext,
  title: string,
  tagId: string,
): Promise<string> {
  const body = new URLSearchParams([
    ['title', title],
    ['language', 'eng'],
    ['tags', tagId],
  ])
  const res = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: body.toString(),
  })
  await expectResponseOk(res, `create document ${title}`)
  return (await res.json()).id as string
}

/** The tag's id, straight from the server — the spec never guesses it from the tree. */
async function apiTagIdByName(request: APIRequestContext, name: string): Promise<string> {
  const res = await request.get('/api/tag/list')
  await expectResponseOk(res, 'list tags')
  const tags = (await res.json()).tags as Array<{ id: string; name: string }>
  const found = tags.find((t) => t.name === name)
  expect(found, `tag ${name} exists on the server`).toBeTruthy()
  return found!.id
}

/** The tag's DIRECT ACLs as the server holds them — the authoritative read-back. */
async function apiTagAcls(
  request: APIRequestContext,
  id: string,
): Promise<Array<{ perm: string; name: string | null; type: string }>> {
  const res = await request.get(`/api/tag/${id}`)
  await expectResponseOk(res, `read tag ${id}`)
  return (await res.json()).acls
}

// A hash-route change is a same-document navigation, so the SPA answers it from its own cache
// (the tag list has a 60s staleTime). A full reload re-inits the query client, which is what
// makes the second account's view the true post-create one.
async function freshGoto(p: Page, url: string, routeRoot: string): Promise<void> {
  await gotoRaw(p, url)
  await p.reload()
  await expectRouteReady(p, url, routeRoot)
}

test('a tag created with a read grant is shared from its first save; one created without stays private', async ({
  page,
  browser,
  cleanup,
}) => {
  const username = unique('tcperm').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const password = 'Password1e2e'
  const sharedTag = uniqueTag('tcps')
  const privateTag = uniqueTag('tcpp')
  const sharedDoc = unique('tcp-shared-doc')
  const privateDoc = unique('tcp-private-doc')

  const userCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  cleanup.defer('close the second user context', () => userCtx.close())
  const userPage = await userCtx.newPage()

  // --- Admin: the account the new tag will be shared with ---
  await gotoRouteReady(page, '/#/settings/users', ROUTE_ROOT.settingsUsers)
  await page.getByRole('button', { name: 'Add user' }).click()
  const userDialog = page.getByRole('dialog', { name: 'Add user' })
  await userDialog.locator('#add-user-name').fill(username)
  await userDialog.locator('#add-user-email').fill(`${username}@example.com`)
  await userDialog.locator('#add-user-pass').fill(password)
  await userDialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()
  cleanup.defer('delete the second user', () => deleteUserApi(page.request, username))

  // --- Admin: create the shared tag, permissions and all, in ONE pass ---
  await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
  await expect(page.getByRole('heading', { name: 'Tags' })).toBeVisible()
  // The resting state is the compact row; the permissions live one click away.
  await expect(page.locator('.acl-editor')).toHaveCount(0)
  await page.locator('.tag-new-permissions-btn').click()

  const form = page.locator('.tag-list-page .acl-editor')
  await expect(form, 'the shared form brings its permissions section').toBeVisible()
  await page.locator('#tag-new-name').fill(sharedTag)
  cleanup.defer('delete the shared tag', () => deleteTagByNameApi(page.request, sharedTag))

  const addForm = page.locator('.acl-add')
  await addForm.locator('input').first().fill(username)
  await page.getByRole('option', { name: new RegExp(username) }).click()
  // The perm Select defaults to READ ("Can view").
  await addForm.getByRole('button', { name: 'Add', exact: true }).click()

  // The grant is COLLECTED, not sent: it shows in the list while the tag it belongs to does
  // not exist yet anywhere on the server.
  const grantRow = page.locator('.acl-row', { hasText: username })
  await expect(grantRow).toBeVisible()
  await expect(grantRow.getByText('Can view')).toBeVisible()
  const beforeSave = await page.request.get('/api/tag/list')
  await expectResponseOk(beforeSave, 'list tags before the save')
  const namesBeforeSave = ((await beforeSave.json()).tags as Array<{ name: string }>).map(
    (t) => t.name,
  )
  expect(namesBeforeSave, 'nothing is created until Create is pressed').not.toContain(sharedTag)

  await page.locator('.tag-create-btn').click()
  await expect(page.getByText('Tag created')).toBeVisible()
  await expect(page.locator('.tag-tree').getByText(sharedTag, { exact: true })).toBeVisible()

  // The read-back that matters: the grant reached the tag the save created.
  const sharedTagId = await apiTagIdByName(page.request, sharedTag)
  const sharedAcls = await apiTagAcls(page.request, sharedTagId)
  expect(
    sharedAcls.some((a) => a.perm === 'READ' && a.name === username && a.type === 'USER'),
    `the collected READ grant is on tag ${sharedTag} — got ${JSON.stringify(sharedAcls)}`,
  ).toBe(true)

  // --- Admin: the control tag, created the way this page has always created tags ---
  await page.getByPlaceholder('Tag name').fill(privateTag)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.locator('.tag-tree').getByText(privateTag, { exact: true })).toBeVisible()
  cleanup.defer('delete the control tag', () => deleteTagByNameApi(page.request, privateTag))

  const privateTagId = await apiTagIdByName(page.request, privateTag)
  const privateAcls = await apiTagAcls(page.request, privateTagId)
  expect(
    privateAcls.some((a) => a.name === username),
    'the control tag carries no grant for the second account',
  ).toBe(false)

  // --- Admin: one document per tag, so the grant's REACH is observable too ---
  const sharedDocId = await apiCreateDoc(page.request, sharedDoc, sharedTagId)
  cleanup.defer('purge the shared document', () => deleteDocApi(page.request, sharedDocId))
  const privateDocId = await apiCreateDoc(page.request, privateDoc, privateTagId)
  cleanup.defer('purge the control document', () => deleteDocApi(page.request, privateDocId))

  // --- The second account: sees the tag it was granted at creation, and nothing else ---
  await login(userPage, username, password)
  await freshGoto(userPage, '/#/tag', ROUTE_ROOT.tagList)
  await expect(
    userPage.locator('.tag-tree').getByText(sharedTag, { exact: true }),
    'the grant set before the first save is live',
  ).toBeVisible()
  await expect(
    userPage.locator('.tag-tree').getByText(privateTag, { exact: true }),
    'a tag created without grants stays the creator\'s own',
  ).toHaveCount(0)

  await freshGoto(userPage, '/#/document', ROUTE_ROOT.documentList)
  await expect(userPage.getByText(new RegExp(sharedDoc))).toBeVisible()
  await expect(userPage.getByText(new RegExp(privateDoc))).toHaveCount(0)
})
