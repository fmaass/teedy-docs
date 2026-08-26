import { test, expect, type Page, type BrowserContext } from './fixtures'
import {
  unique,
  uniqueTag,
  login,
  deleteDocApi,
  deleteTagApi,
  deleteUserApi,
  ROUTE_ROOT,
  gotoRouteReady,
  gotoRaw,
  expectRouteReady,
  tagTreePanel,
  closeNav,
  confirmDanger,
} from './helpers'

// #287 — "humans are visual. Having option to add icons to tags would be great to add more
// meaning." The reporter settled the shape himself over the thread: one custom uploaded icon set
// for logos and the like, an EMOJI picker for everything else ("we can already mess around with
// copy paste emojis"), 16–24 px, and a toggle to hide them again.
//
// The premise is BUILT BY THIS SPEC, not inherited. The owner account is created here and logs in
// for the first time here, so it starts with an empty tag list — "this tag has an icon and it
// shows up everywhere" is then a property of the fixture rather than of whatever the rest of the
// suite left behind. The uploaded icon is the one thing that is genuinely instance-wide (there is
// one set), so it is created and torn down explicitly.
//
// What it proves end to end:
//   1. An emoji chosen in the tag form is stored, and drawn in the document-list chip AND in the
//      sidebar tag tree.
//   2. An administrator can add an image to the icon set, and any user can then put it on their
//      own tag — where it renders as the icon endpoint's image.
//   3. The hide-icons toggle removes them, leaving the chip exactly as an icon-less tag's.
//   4. Deleting the icon from the set leaves the tag with NO icon — never a broken image.

const PASSWORD = 'Password1e2e'

/** A 1×1 PNG. Real PNG bytes (the signature is what the server sniffs), and the smallest possible. */
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

/** The emoji the tag gets. A military medal — the very one the reporter pasted into the issue. */
const MEDAL = '\u{1F396}\u{FE0F}'

function accountName(prefix: string): string {
  return unique(prefix).replace(/[^a-z0-9]/gi, '').toLowerCase()
}

/** Create a user through the admin UI (the same path settings-crud drives). */
async function createUser(page: Page, username: string): Promise<void> {
  await gotoRouteReady(page, '/#/settings/users', ROUTE_ROOT.settingsUsers)
  await page.getByRole('button', { name: 'Add user' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add user' })
  await dialog.locator('#add-user-name').fill(username)
  await dialog.locator('#add-user-email').fill(`${username}@example.com`)
  await dialog.locator('#add-user-pass').fill(PASSWORD)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()
}

/** A fresh, cookie-less context so a login is a real first login rather than the shared state. */
async function freshContext(browser: {
  newContext: (o: Record<string, unknown>) => Promise<BrowserContext>
}): Promise<BrowserContext> {
  return browser.newContext({ storageState: { cookies: [], origins: [] } })
}

/** Open the icon-set section on the tag management page. It is collapsed until asked for. */
async function openIconSet(page: Page): Promise<void> {
  await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
  await closeNav(page)
  const header = page.locator('.tag-icon-set .icon-set-header')
  await expect(header).toBeVisible()
  if ((await header.getAttribute('aria-expanded')) !== 'true') {
    await header.click()
  }
  await expect(header).toHaveAttribute('aria-expanded', 'true')
}

test('a tag can carry an emoji or an uploaded icon, hidden on demand (#287)', async ({
  page,
  browser,
  cleanup,
}) => {
  // Two accounts, an upload, a document save and several full reloads.
  test.setTimeout(180_000)

  const owner = accountName('tgiown')
  const tagName = uniqueTag('tgi')
  const docTitle = unique('tgi-doc')
  const iconName = unique('tgi-icon')

  // --- Admin seeds the owner account ---
  await createUser(page, owner)
  cleanup.defer('delete the owner account', () => deleteUserApi(page.request, owner))

  const ownerCtx = await freshContext(browser)
  cleanup.defer('close the owner context', () => ownerCtx.close())
  const ownerPage = await ownerCtx.newPage()
  await login(ownerPage, owner, PASSWORD)

  // The premise, asserted rather than assumed: this account owns nothing.
  const listBefore = await ownerPage.request.get('/api/tag/list')
  expect(listBefore.ok(), 'read the owner tag list').toBeTruthy()
  expect(
    (await listBefore.json()).tags,
    'the fresh account owns no tags, so every chip below is this test s own',
  ).toEqual([])

  // ---------------------------------------------------------------------------------------
  // 1. An emoji, chosen in the tag form, on a brand-new tag
  // ---------------------------------------------------------------------------------------
  await gotoRouteReady(ownerPage, '/#/tag', ROUTE_ROOT.tagList)
  await closeNav(ownerPage)
  // The compact create row does not carry the icon field — the full form does, and it is one
  // click away, which is the path #306 built.
  await ownerPage.locator('.tag-new-permissions-btn').click()
  await ownerPage.locator('#tag-new-name').fill(tagName)
  // Scoped to the field's own source toggle: "Emoji" and "Icon set" are also words that appear
  // elsewhere on the tag page, and an unscoped role query would be a coin toss.
  await ownerPage
    .locator('.icon-source-toggle')
    .getByRole('button', { name: 'Emoji', exact: true })
    .click()
  const emojiBox = ownerPage.locator('#tag-new-icon-emoji')
  await expect(emojiBox).toBeVisible()
  await emojiBox.fill(MEDAL)
  // The form's own preview is the first place the choice shows.
  await expect(ownerPage.locator('.color-preview .tag-icon-emoji')).toHaveText(MEDAL)
  await ownerPage.locator('.tag-create-btn').click()
  await expect(ownerPage.getByText('Tag created')).toBeVisible()

  const created = await ownerPage.request.get('/api/tag/list')
  const tag = (await created.json()).tags.find((t: { name: string }) => t.name === tagName)
  expect(tag, `the tag ${tagName} was created`).toBeTruthy()
  const tagId = tag.id as string
  cleanup.defer('delete the tag', () => deleteTagApi(page.request, tagId))
  expect(tag.icon, 'the emoji was stored on the tag').toBe(`emoji:${MEDAL}`)

  // The management tree draws it beside the colour dot.
  await ownerPage.reload()
  await expectRouteReady(ownerPage, '/#/tag', ROUTE_ROOT.tagList)
  await closeNav(ownerPage)
  await expect(
    ownerPage.locator('.tag-node', { hasText: tagName }).locator('.tag-icon-emoji'),
  ).toHaveText(MEDAL)

  // ---------------------------------------------------------------------------------------
  // 2. ...and in the document list, which is where a chip is drawn most
  // ---------------------------------------------------------------------------------------
  const docRes = await ownerPage.request.put('/api/document', {
    form: { title: docTitle, language: 'eng', tags: tagId },
  })
  expect(docRes.ok(), `create the document ${docTitle}`).toBeTruthy()
  const docId = (await docRes.json()).id as string
  cleanup.defer('delete the document', () => deleteDocApi(page.request, docId))

  await gotoRaw(ownerPage, '/#/document')
  await ownerPage.reload()
  await expectRouteReady(ownerPage, '/#/document', ROUTE_ROOT.documentList)
  const listChip = ownerPage.locator('.teedy-tag', { hasText: tagName }).first()
  await expect(listChip).toBeVisible()
  await expect(listChip.locator('.tag-icon-emoji')).toHaveText(MEDAL)

  // And in the sidebar tag tree, which is a different renderer over the same list.
  const tree = await tagTreePanel(ownerPage)
  await expect(
    tree.getByRole('button', { name: new RegExp(tagName) }).locator('.tag-icon-emoji'),
  ).toHaveText(MEDAL)
  await closeNav(ownerPage)

  // ---------------------------------------------------------------------------------------
  // 3. An administrator uploads an icon; the owner puts it on the tag
  // ---------------------------------------------------------------------------------------
  await openIconSet(page)
  await page.locator('#tag-icon-name').fill(iconName)
  await page.locator('.tag-icon-set input[type="file"]').setInputFiles({
    name: 'medal.png',
    mimeType: 'image/png',
    buffer: PNG_1PX,
  })
  await expect(page.getByText('Icon added')).toBeVisible()

  const iconsRes = await page.request.get('/api/tag/icon')
  const icon = (await iconsRes.json()).icons.find((i: { name: string }) => i.name === iconName)
  expect(icon, `the icon ${iconName} is in the set`).toBeTruthy()
  const iconId = icon.id as string
  // The set is instance-wide, so this teardown is not optional even if the body fails.
  cleanup.defer('delete the uploaded icon', async () => {
    await page.request.delete(`/api/tag/icon/${iconId}`)
  })
  expect(icon.mimetype, 'the type was decided from the bytes').toBe('image/png')

  // A NON-admin may use it, which is the whole point of a shared set.
  await gotoRaw(ownerPage, `/#/tag/${tagId}`)
  await ownerPage.reload()
  await expect(ownerPage.locator('#tag-name')).toHaveValue(tagName)
  await ownerPage
    .locator('.icon-source-toggle')
    .getByRole('button', { name: 'Icon set', exact: true })
    .click()
  const setOption = ownerPage.locator('.icon-set-option').filter({ has: ownerPage.locator(`img[alt="${iconName}"]`) })
  await expect(setOption).toBeVisible()
  await setOption.click()
  await ownerPage.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(ownerPage.getByText('Tag updated')).toBeVisible()

  const afterSet = await ownerPage.request.get(`/api/tag/${tagId}`)
  expect((await afterSet.json()).icon, 'the tag now points at the uploaded icon').toBe(
    `set:${iconId}`,
  )

  // It renders as the icon endpoint's image on the document list.
  await gotoRaw(ownerPage, '/#/document')
  await ownerPage.reload()
  await expectRouteReady(ownerPage, '/#/document', ROUTE_ROOT.documentList)
  const chipImage = ownerPage
    .locator('.teedy-tag', { hasText: tagName })
    .first()
    .locator('img.tag-icon')
  await expect(chipImage).toBeVisible()
  await expect(chipImage).toHaveAttribute('src', `api/tag/icon/${iconId}/data`)
  // 16–24 px was the reporter's own range; the chip draws at the bottom of it.
  const box = await chipImage.boundingBox()
  expect(box, 'the icon has a box').not.toBeNull()
  expect(box!.width).toBeGreaterThanOrEqual(16)
  expect(box!.width).toBeLessThanOrEqual(24)

  // ---------------------------------------------------------------------------------------
  // 4. The toggle hides every icon, and putting it back restores them
  // ---------------------------------------------------------------------------------------
  await gotoRouteReady(ownerPage, '/#/settings/account', ROUTE_ROOT.settingsAccount)
  const toggle = ownerPage.locator('#account-tag-icons')
  await expect(toggle).toBeVisible()
  await toggle.click()

  await gotoRaw(ownerPage, '/#/document')
  await ownerPage.reload()
  await expectRouteReady(ownerPage, '/#/document', ROUTE_ROOT.documentList)
  const hiddenChip = ownerPage.locator('.teedy-tag', { hasText: tagName }).first()
  await expect(hiddenChip).toBeVisible()
  // The tag itself is untouched — only its icon is gone, and the chip is exactly what an
  // icon-less tag's chip has always been.
  await expect(hiddenChip.locator('.tag-icon')).toHaveCount(0)
  await expect(hiddenChip).toHaveText(tagName)

  await gotoRouteReady(ownerPage, '/#/settings/account', ROUTE_ROOT.settingsAccount)
  await ownerPage.locator('#account-tag-icons').click()
  await gotoRaw(ownerPage, '/#/document')
  await ownerPage.reload()
  await expectRouteReady(ownerPage, '/#/document', ROUTE_ROOT.documentList)
  await expect(
    ownerPage.locator('.teedy-tag', { hasText: tagName }).first().locator('img.tag-icon'),
  ).toBeVisible()

  // ---------------------------------------------------------------------------------------
  // 5. Deleting the icon leaves the tag with NO icon, never a broken image
  // ---------------------------------------------------------------------------------------
  await openIconSet(page)
  const row = page.locator('.icon-set-row', { hasText: iconName })
  await expect(row).toBeVisible()
  await row.locator('.icon-set-delete-btn').click()
  await confirmDanger(page)
  await expect(page.getByText(/Icon deleted/)).toBeVisible()

  const afterDelete = await ownerPage.request.get(`/api/tag/${tagId}`)
  const afterDeleteBody = await afterDelete.json()
  expect(
    'icon' in afterDeleteBody,
    'the tag reports no icon at all once the icon it used was deleted',
  ).toBe(false)

  await gotoRaw(ownerPage, '/#/document')
  await ownerPage.reload()
  await expectRouteReady(ownerPage, '/#/document', ROUTE_ROOT.documentList)
  const fallbackChip = ownerPage.locator('.teedy-tag', { hasText: tagName }).first()
  await expect(fallbackChip).toBeVisible()
  await expect(fallbackChip.locator('.tag-icon')).toHaveCount(0)
  await expect(fallbackChip).toHaveText(tagName)
})
