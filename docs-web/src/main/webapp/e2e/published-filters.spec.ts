import { test, expect, type APIRequestContext, type Page } from './fixtures'
import {
  unique,
  uniqueTag,
  createDocument,
  login,
  deleteDocApi,
  deleteTagApi,
  deleteUserApi,
  expectResponseOk,
  expectRouteReady,
  gotoDocumentList,
  gotoRouteReady,
  ROUTE_ROOT,
} from './helpers'

// #51 — a saved filter published to every user.
//
// The premise is built entirely by this spec: two fresh accounts of its own, a document each,
// a tag of its own, and filters of its own. Nothing is assumed about the instance's existing
// data — every row asserted is one this test caused, and the admin account owns none of it.
//
// The three claims under test are the ones the design turns on:
//
//  1. SPLIT VIEW — the reporter's ask. What Alice publishes reaches Bob in a section of its
//     own, named with its publisher, never mixed into Bob's own list.
//  2. USE, NOT COPY — Bob APPLIES Alice's filter and gets HIS OWN documents. The filter is a
//     free-text search that BOTH accounts have a matching document for, so a result set that
//     leaked across the ACL would be visible as Alice's document appearing in Bob's list. It
//     does not: the search is ACL-scoped server-side.
//  3. TAG VISIBILITY — a published filter naming a tag Bob cannot read is offered but
//     DISABLED, with a tooltip that explains why and names no tag. Not hidden (Bob would
//     never learn why a colleague's filter is missing) and not silently stripped (the tag
//     hydration drops unknown ids, so an applied filter would quietly select a wider set than
//     its author meant).

const PASSWORD = 'Password1e2e'

/** A username the server accepts: lower-case alphanumerics only. */
function accountName(prefix: string): string {
  return unique(prefix).replace(/[^a-z0-9]/gi, '').toLowerCase()
}

async function createUserApi(page: Page, username: string): Promise<void> {
  const res = await page.request.put('/api/user', {
    form: {
      username,
      password: PASSWORD,
      email: `${username}@example.com`,
      storage_quota: '100000000',
    },
  })
  await expectResponseOk(res, `seed user ${username}`)
}

/** Save a filter through the API, as the owner of the given request context. */
async function saveFilterApi(request: APIRequestContext, name: string, query: string): Promise<string> {
  const res = await request.put('/api/savedfilter', { form: { name, query } })
  await expectResponseOk(res, `save filter ${name}`)
  return (await res.json()).id as string
}

async function publishFilterApi(request: APIRequestContext, id: string): Promise<void> {
  // The route takes no parameters, but the resource consumes form-urlencoded — send an empty
  // body of that type rather than a typeless one, so the request matches the way the app's own
  // axios client (which posts a URLSearchParams) does.
  const res = await request.post(`/api/savedfilter/${id}/publish`, {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: '',
  })
  await expectResponseOk(res, `publish filter ${id}`)
}

async function deleteFilterApi(request: APIRequestContext, id: string): Promise<void> {
  if (!id) return
  const res = await request.delete(`/api/savedfilter/${id}`)
  if (res.ok()) return
  // Tolerant only against a read-back: a filter the body already removed is not a failure,
  // a filter that is still there is.
  const list = await request.get('/api/savedfilter')
  if (list.ok()) {
    const own = (await list.json()).saved_filters as Array<{ id: string }>
    if (!own.some((f) => f.id === id)) return
  }
  throw new Error(`teardown: saved filter ${id} survived deletion (HTTP ${res.status()})`)
}

async function createTagApi(request: APIRequestContext, name: string): Promise<string> {
  const res = await request.put('/api/tag', { form: { name, color: '#3f51b5' } })
  await expectResponseOk(res, `create tag ${name}`)
  return (await res.json()).id as string
}

function rowFor(page: Page, title: string) {
  return page.getByRole('row', { name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })
}

/**
 * Land on the document list with a genuinely FRESH saved-filter list.
 *
 * The reload is load-bearing, not defensive. The SPA caches `['savedFilters']` for 30 seconds
 * (main.ts `staleTime`), and a `goto` from one hash route to another is a same-document
 * navigation — the page never reloads, so the component re-mounts against the cached copy and
 * reaches no server. Every assertion here is about what ANOTHER account just published, well
 * inside that window, so without a real load this spec would read a snapshot taken before the
 * publication and pass or fail on the cache rather than on the feature.
 */
async function reloadDocumentList(page: Page) {
  await gotoDocumentList(page)
  await page.reload()
  await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
}

/** Open the saved-filters popover from the document-list toolbar. */
async function openSavedFilters(page: Page) {
  await page.getByRole('button', { name: 'Saved filters' }).click()
  await expect(page.locator('.saved-filters-list')).toBeVisible()
}

test('a published filter reaches another user in its own section, applies to THEIR documents, and stays disabled when it names a tag they cannot see', async ({
  page,
  browser,
  cleanup,
}) => {
  const aliceName = accountName('sf51a')
  const bobName = accountName('sf51b')
  const term = unique('sf51term')
  const aliceTitle = `${term}-alice`
  const bobTitle = `${term}-bob`
  const sharedFilterName = unique('sf51-shared')
  const taggedFilterName = unique('sf51-tagged')
  const bobFilterName = unique('sf51-bobs-own')
  const secretTagName = uniqueTag('sf51sec')

  // --- Accounts, and their own browser contexts ---
  await createUserApi(page, aliceName)
  await createUserApi(page, bobName)

  const aliceCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const alicePage = await aliceCtx.newPage()
  await login(alicePage, aliceName, PASSWORD)

  const bobCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const bobPage = await bobCtx.newPage()
  await login(bobPage, bobName, PASSWORD)

  // Teardown is registered UP FRONT, in the order it has to run (the cleanup fixture is
  // FIFO): each account's own content from its own context, then the contexts, then the
  // accounts. The ids are filled in below — a step whose id never got assigned is a no-op,
  // so an early failure still tears down whatever did get created.
  let aliceDocId = ''
  let bobDocId = ''
  let secretTagId = ''
  let sharedFilterId = ''
  let taggedFilterId = ''
  let bobFilterId = ''
  cleanup.defer('delete the published filter', () => deleteFilterApi(alicePage.request, sharedFilterId))
  cleanup.defer('delete the tag-referencing filter', () => deleteFilterApi(alicePage.request, taggedFilterId))
  cleanup.defer("delete bob's own filter", () => deleteFilterApi(bobPage.request, bobFilterId))
  cleanup.defer("purge alice's document", () => (aliceDocId ? deleteDocApi(alicePage.request, aliceDocId) : undefined))
  cleanup.defer("purge bob's document", () => (bobDocId ? deleteDocApi(bobPage.request, bobDocId) : undefined))
  cleanup.defer('delete the private tag', () => (secretTagId ? deleteTagApi(alicePage.request, secretTagId) : undefined))
  cleanup.defer('close the first user context', () => aliceCtx.close())
  cleanup.defer('close the second user context', () => bobCtx.close())
  cleanup.defer('delete the first user', () => deleteUserApi(page.request, aliceName))
  cleanup.defer('delete the second user', () => deleteUserApi(page.request, bobName))

  // --- Each account gets a document matching the SAME search term ---
  aliceDocId = (await createDocument(alicePage, aliceTitle)).id
  bobDocId = (await createDocument(bobPage, bobTitle)).id

  // Bob has a filter of his own, so the split view has both halves to show.
  bobFilterId = await saveFilterApi(bobPage.request, bobFilterName, 'search=nothing-matches-this')

  // --- Alice builds a filter in the UI, saves it, and publishes it ---
  await gotoRouteReady(alicePage, `/#/document?search=${term}`, ROUTE_ROOT.documentList)
  await expect(rowFor(alicePage, aliceTitle)).toBeVisible()

  await alicePage.getByRole('button', { name: 'Save filter' }).click()
  await alicePage.locator('#saved-filter-name').fill(sharedFilterName)
  await alicePage.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(alicePage.getByText('Filter saved')).toBeVisible()

  await openSavedFilters(alicePage)
  const publishButton = alicePage.getByRole('button', {
    name: `Share the saved filter "${sharedFilterName}" with everyone`,
  })
  await expect(publishButton).toBeVisible()
  // Same popover instability as the rest of the saved-filter suite (#81): PrimeVue keeps
  // repositioning the overlay after open, so the control is asserted present and then
  // clicked directly rather than waited on for stability.
  await publishButton.click({ force: true })
  await expect(alicePage.getByText('Filter shared with everyone')).toBeVisible()
  // The control now offers the opposite act — the publish state is readable, not just stored.
  await expect(
    alicePage.getByRole('button', { name: `Stop sharing the saved filter "${sharedFilterName}"` }),
  ).toBeVisible()

  sharedFilterId = await (async () => {
    const res = await alicePage.request.get('/api/savedfilter')
    await expectResponseOk(res, "read back alice's filters")
    const own = (await res.json()).saved_filters as Array<{ id: string; name: string; published: boolean }>
    const match = own.find((f) => f.name === sharedFilterName)
    expect(match, 'the filter alice just saved is in her own list').toBeTruthy()
    expect(match!.published, 'the publish control actually published it').toBe(true)
    return match!.id
  })()

  // --- Alice also publishes a filter that names a tag only she can read ---
  secretTagId = await createTagApi(alicePage.request, secretTagName)
  taggedFilterId = await saveFilterApi(alicePage.request, taggedFilterName, `tags=${secretTagId}`)
  await publishFilterApi(alicePage.request, taggedFilterId)

  // --- Bob: the split view ---
  await reloadDocumentList(bobPage)
  await openSavedFilters(bobPage)

  await expect(bobPage.locator('.saved-filters-section')).toHaveText([
    'My filters',
    'Shared by others',
  ])
  const shared = bobPage.locator('.saved-filters-shared')
  await expect(shared.getByRole('button', { name: sharedFilterName, exact: true })).toBeVisible()
  // Bob's OWN filter stays in his own section — a shared filter is never mixed into it.
  await expect(shared.getByRole('button', { name: bobFilterName, exact: true })).toHaveCount(0)
  // The row names its publisher, so two same-named filters could be told apart.
  await expect(
    shared.locator('.saved-filters-item', { hasText: sharedFilterName }).locator('.saved-filters-owner'),
  ).toHaveText(`by ${aliceName}`)

  // --- Bob: the tag-visibility rule, before anything is applied ---
  const lockedRow = shared.locator('.saved-filters-item.unavailable')
  await expect(lockedRow).toHaveCount(1)
  await expect(lockedRow).toContainText(taggedFilterName)
  await expect(lockedRow).toHaveAttribute(
    'title',
    `${taggedFilterName} — cannot be applied: it uses tags you cannot see`,
  )
  await expect(lockedRow.getByRole('button', { name: taggedFilterName, exact: false })).toBeDisabled()
  // The explanation counts, it does not name: Alice's tag appears nowhere on Bob's page.
  const bobBody = await bobPage.locator('body').innerText()
  expect(bobBody, "the invisible tag's name must not reach Bob").not.toContain(secretTagName)

  // --- Bob: applying the shared filter gives him HIS results ---
  const applyShared = shared.getByRole('button', { name: sharedFilterName, exact: true })
  await expect(applyShared).toBeVisible()
  await applyShared.click({ force: true })

  await expect(bobPage).toHaveURL(new RegExp(`[?&]search=${term}`))
  // POST-refresh barrier: assert the LIVE result set, so this fails if applying the shared
  // filter did not actually drive the query.
  await expect(rowFor(bobPage, bobTitle)).toBeVisible()
  await expect(rowFor(bobPage, aliceTitle)).toHaveCount(0)
  const filteredBody = await bobPage.locator('body').innerText()
  expect(filteredBody, "alice's matching document is not in bob's result set").not.toContain(aliceTitle)

  // --- Withdrawing the publication takes it back off Bob's list ---
  const withdraw = await alicePage.request.delete(`/api/savedfilter/${sharedFilterId}/publish`)
  await expectResponseOk(withdraw, 'withdraw the publication')

  await reloadDocumentList(bobPage)
  await openSavedFilters(bobPage)
  await expect(bobPage.locator('.saved-filters-shared')).toHaveCount(1)
  await expect(
    bobPage.locator('.saved-filters-shared').getByRole('button', { name: sharedFilterName, exact: true }),
  ).toHaveCount(0)
})
