import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type CleanupFixture,
  type Page,
} from './fixtures'
import {
  unique,
  uniqueTag,
  deleteDocApi,
  deleteTagApi,
  deleteUserApi,
  expectResponseOk,
  gotoDocumentList,
  login,
} from './helpers'

// #293 — the tag-reduction run over the document-list selection.
//
// The rule: a tag comes off a document only when a tag BELOW it — at any depth — is on that same
// document, because the document is already found through that deeper tag. The reporter's one
// condition was that it must not destroy anything unseen ("some preview/dry-run would be good"),
// and the maintainer's was that it acts on the CURRENT SELECTION rather than the whole instance.
// This spec walks that contract end to end and reads the result back through the API, so the
// acceptance is the documents' actual tags rather than what the dialog said it did.
//
// PREMISE, fully self-constructed. Every tag and document below is seeded by this spec through a
// FRESH account's own session. That matters twice over: the preview lists tags by their full path,
// so an assertion on exact paths is only meaningful over a tree this spec owns, and the run's
// scope is a selection made in the list — a shared account would put other specs' documents on the
// same page, where a stray checkbox would silently widen the batch.

const SEED_USER_PASSWORD = 'TagReduce123'

/** The seeded tree and the two documents the assertions are about. */
interface Seeded {
  /** Insurance — the root, redundant on the deep document. */
  root: string
  /** Car — the middle tag, redundant on the deep document too (transitivity). */
  middle: string
  /** 2026 — the deepest tag, the only one that survives. */
  leaf: string
  /** Travel — an unrelated root, on the untouched document. */
  other: string
  deepTitle: string
  deepId: string
  untouchedTitle: string
  untouchedId: string
}

async function apiCreateTag(request: APIRequestContext, name: string, parentId?: string): Promise<string> {
  const form: Record<string, string> = { name, color: '#3399cc' }
  if (parentId) form.parent = parentId
  const res = await request.put('/api/tag', { form })
  await expectResponseOk(res, `create tag ${name}`)
  return (await res.json()).id as string
}

async function apiCreateDocument(
  request: APIRequestContext,
  title: string,
  tagIds: string[],
): Promise<string> {
  // Hand-built body rather than `form`: several tags means a REPEATED `tags` parameter, which the
  // object form of Playwright's `form` option cannot express.
  const body = new URLSearchParams()
  body.set('title', title)
  body.set('language', 'eng')
  for (const tagId of tagIds) body.append('tags', tagId)
  const res = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: body.toString(),
  })
  await expectResponseOk(res, `create the document ${title}`)
  return (await res.json()).id as string
}

/** The tag names currently on a document, sorted — the authoritative read-back. */
async function tagNamesOf(request: APIRequestContext, documentId: string): Promise<string[]> {
  const res = await request.get(`/api/document/${documentId}`)
  await expectResponseOk(res, `read the document ${documentId} back`)
  const body = (await res.json()) as { tags: { name: string }[] }
  return body.tags.map((tag) => tag.name).sort()
}

async function seed(request: APIRequestContext, cleanup: CleanupFixture): Promise<Seeded> {
  // One stem, four derived names of equal shape: every assertion below is either "this path is
  // listed" or "this name is NOT listed", and a bare stem would be a substring of all of them.
  const stem = uniqueTag('tred')
  const names = { root: `${stem}-a`, middle: `${stem}-b`, leaf: `${stem}-c`, other: `${stem}-d` }

  const rootId = await apiCreateTag(request, names.root)
  cleanup.defer(`delete the tag ${names.root}`, () => deleteTagApi(request, rootId))
  const middleId = await apiCreateTag(request, names.middle, rootId)
  cleanup.defer(`delete the tag ${names.middle}`, () => deleteTagApi(request, middleId))
  const leafId = await apiCreateTag(request, names.leaf, middleId)
  cleanup.defer(`delete the tag ${names.leaf}`, () => deleteTagApi(request, leafId))
  const otherId = await apiCreateTag(request, names.other)
  cleanup.defer(`delete the tag ${names.other}`, () => deleteTagApi(request, otherId))

  // The whole chain on one document: the case the reporter described, three levels deep.
  const deepTitle = unique('treddeep')
  const deepId = await apiCreateDocument(request, deepTitle, [rootId, middleId, leafId])
  cleanup.defer('purge the deep document', () => deleteDocApi(request, deepId))

  // The control: also selected, also carrying the root tag, but its second tag is NOT below the
  // root — so nothing on it is redundant and the run must leave it exactly as it is.
  const untouchedTitle = unique('tredkeep')
  const untouchedId = await apiCreateDocument(request, untouchedTitle, [rootId, otherId])
  cleanup.defer('purge the untouched document', () => deleteDocApi(request, untouchedId))

  return { ...names, deepTitle, deepId, untouchedTitle, untouchedId }
}

async function createSeedUser(baseURL: string): Promise<string> {
  const username = unique('treduser').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const admin = await pwRequest.newContext({ baseURL })
  const adminLogin = await admin.post('/api/user/login', {
    form: { username: 'admin', password: 'admin', remember: false },
  })
  await expectResponseOk(adminLogin, 'admin login for the tag-reduction user seed')
  const created = await admin.put('/api/user', {
    form: {
      username,
      password: SEED_USER_PASSWORD,
      email: `${username}@example.com`,
      storage_quota: 1_000_000_000,
    },
  })
  await expectResponseOk(created, `create the tag-reduction user ${username}`)
  await admin.dispose()
  return username
}

async function deleteSeedUser(baseURL: string, username: string): Promise<void> {
  const admin = await pwRequest.newContext({ baseURL })
  const adminLogin = await admin.post('/api/user/login', {
    form: { username: 'admin', password: 'admin', remember: false },
  })
  await expectResponseOk(adminLogin, 'admin login for the tag-reduction user teardown')
  await deleteUserApi(admin, username)
  await admin.dispose()
}

async function openSeedUserRequest(baseURL: string, username: string): Promise<APIRequestContext> {
  const asUser = await pwRequest.newContext({ baseURL })
  const userLogin = await asUser.post('/api/user/login', {
    form: { username, password: SEED_USER_PASSWORD, remember: false },
  })
  await expectResponseOk(userLogin, `log the tag-reduction user ${username} in`)
  return asUser
}

/** Ticks one document's selection checkbox by its title. */
async function select(page: Page, title: string): Promise<void> {
  await page.getByRole('row', { name: new RegExp(title) }).getByRole('checkbox').check()
}

// Seeding is four tag creates, two document creates and a real form login before the page opens,
// the body then drives a two-step dialog and reads both documents back, and the account is torn
// down afterwards — all of it sequential HTTP.
const SEED_TIMEOUT = 90_000

test.describe('tag reduction over a selection, on an isolated account', () => {
  // Cleared so the shared admin session cannot log the browser in first.
  test.use({ storageState: { cookies: [], origins: [] } })

  test('previews the redundant tags, then removes exactly those on confirm (#293)', async ({
    page,
    baseURL,
    cleanup,
  }) => {
    test.setTimeout(SEED_TIMEOUT)
    const username = await createSeedUser(baseURL!)
    const asUser = await openSeedUserRequest(baseURL!, username)
    const seeded = await seed(asUser, cleanup)
    cleanup.defer('delete the tag-reduction user', () => deleteSeedUser(baseURL!, username))
    cleanup.defer('dispose the tag-reduction API context', () => asUser.dispose())
    await login(page, username, SEED_USER_PASSWORD)
    await gotoDocumentList(page)

    // PREMISE: both seeded documents are on screen with the tags the assertions rest on.
    await expect(page.getByRole('row', { name: new RegExp(seeded.deepTitle) })).toBeVisible()
    await expect(page.getByRole('row', { name: new RegExp(seeded.untouchedTitle) })).toBeVisible()
    expect(await tagNamesOf(asUser, seeded.deepId), 'the deep document starts with all three').toEqual(
      [seeded.root, seeded.middle, seeded.leaf].sort(),
    )

    await select(page, seeded.deepTitle)
    await select(page, seeded.untouchedTitle)
    const bar = page.locator('.bulk-bar')
    await expect(bar).toBeVisible()
    await expect(bar.getByText('2 selected')).toBeVisible()

    await bar.getByRole('button', { name: 'Reduce tags' }).click()
    // Located by its own class rather than by role: the app renders other dialogs, and every
    // assertion below is about THIS one's contents.
    const dialog = page.locator('.tag-reduction-dialog')
    await expect(dialog).toBeVisible()

    // The PREVIEW. Both ancestors are named — the rule is transitive, so the run reaches past the
    // direct parent — and the document with nothing redundant on it is not listed at all.
    const rows = dialog.locator('.reduction-doc')
    await expect(rows).toHaveCount(1)
    await expect(rows.first().locator('.reduction-doc-title')).toHaveText(seeded.deepTitle)
    await expect(rows.first().locator('.reduction-tag')).toHaveText([
      seeded.root,
      `${seeded.root} / ${seeded.middle}`,
    ])
    await expect(dialog).not.toContainText(seeded.untouchedTitle)
    // The deepest tag is what makes the two above it redundant; it must never be offered. Asserted
    // over the whole dialog (one element, so the negation is unambiguous) — the -c suffix appears
    // in no other seeded name.
    await expect(dialog).not.toContainText(seeded.leaf)

    // …and the preview really is a preview. Read the documents back BEFORE confirming: nothing
    // has moved yet on either of them.
    expect(await tagNamesOf(asUser, seeded.deepId), 'the preview removed nothing').toEqual(
      [seeded.root, seeded.middle, seeded.leaf].sort(),
    )

    await dialog.locator('.reduction-confirm-btn').click()
    await expect(dialog.locator('.reduction-result')).toBeVisible()
    // A finished run cannot be run again from its own report.
    await expect(dialog.locator('.reduction-confirm-btn')).toHaveCount(0)

    // ACCEPTANCE, read off the documents themselves rather than the dialog: the chain collapsed to
    // its deepest tag, and the other selected document is untouched.
    expect(await tagNamesOf(asUser, seeded.deepId)).toEqual([seeded.leaf])
    expect(await tagNamesOf(asUser, seeded.untouchedId), 'nothing redundant, nothing removed').toEqual(
      [seeded.root, seeded.other].sort(),
    )

    // The tags themselves survive — this run un-assigns, it never deletes a tag.
    const tagList = await asUser.get('/api/tag/list')
    await expectResponseOk(tagList, 'read the tag list back')
    const names = ((await tagList.json()) as { tags: { name: string }[] }).tags.map((tag) => tag.name)
    expect(names).toEqual(expect.arrayContaining([seeded.root, seeded.middle, seeded.leaf, seeded.other]))
  })

  test('offers no reduction while nothing is selected, and says when there is nothing to reduce', async ({
    page,
    baseURL,
    cleanup,
  }) => {
    test.setTimeout(SEED_TIMEOUT)
    const username = await createSeedUser(baseURL!)
    const asUser = await openSeedUserRequest(baseURL!, username)
    const seeded = await seed(asUser, cleanup)
    cleanup.defer('delete the tag-reduction user', () => deleteSeedUser(baseURL!, username))
    cleanup.defer('dispose the tag-reduction API context', () => asUser.dispose())
    await login(page, username, SEED_USER_PASSWORD)
    await gotoDocumentList(page)

    // The affordance costs the default list nothing: no toolbar, so no reduction button. This is
    // also what keeps the document-list visual baselines valid — they are captured in this state.
    await expect(page.locator('.bulk-bar')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Reduce tags' })).toHaveCount(0)

    // Only the document with nothing redundant on it: the run must say so rather than removing
    // something to have an effect.
    await select(page, seeded.untouchedTitle)
    await page.locator('.bulk-bar').getByRole('button', { name: 'Reduce tags' }).click()
    const dialog = page.locator('.tag-reduction-dialog')
    await expect(dialog.locator('.reduction-none')).toBeVisible()
    await expect(dialog.locator('.reduction-confirm-btn')).toBeDisabled()

    expect(await tagNamesOf(asUser, seeded.untouchedId)).toEqual([seeded.root, seeded.other].sort())
  })
})
