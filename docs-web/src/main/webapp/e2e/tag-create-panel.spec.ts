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
} from './helpers'

// #288 — "add a button to add new tags and automatically assign to this document after
// creation" (the reporter), built as the split view they asked for: the document edit form on
// the left, a create-tag panel on the right.
//
// The premise here is built from nothing, on purpose. The create row appears only when the
// typed text matches NO tag the account can see, so a spec running against the shared admin
// account would be asserting against whatever tags the rest of the suite happened to leave
// behind. Both users below are created by this spec, log in for the first time here, and start
// with an empty tag list — so "matches nothing" is a property of the fixture, not a hope.
//
// What it proves end to end:
//   1. Typing a name no tag matches offers to create it; a name that DOES match does not.
//   2. The panel opens pre-filled, and Save creates the tag with the colour chosen IN the panel.
//   3. The tag lands in the form's SELECTION — the document is not written until the user's
//      own Save — and is on the document afterwards.
//   4. It is a real tag on the tag management page, not a chip local to that form.
//   5. Permissions granted IN the panel are effective: a second account, which cannot see the
//      document's owner's other tags, sees this one.

const PASSWORD = 'Password1e2e'

/** The panel's seed colour, which the chosen colour must differ from for the test to mean anything. */
const DEFAULT_TAG_COLOR = '#2aabd2'

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

/**
 * Type into the document form's tag search box, opening the overlay first only if it is not
 * already open. Clicking the field TOGGLES the overlay, and PrimeVue defers the close by a
 * macrotask — so a second unconditional click reads as "still open" for long enough to fill
 * the box, and then tears it down under the assertions.
 */
async function typeInTagSearch(page: Page, text: string): Promise<void> {
  const search = page.locator('input.tp-filter-input')
  if (!(await search.isVisible())) {
    await page.locator('#edit-tags').click()
    await expect(search).toBeVisible()
  }
  await search.fill(text)
}

/**
 * `rgb(r, g, b)` — which is what a browser reports for an inline hex background — as `#rrggbb`,
 * so the colour the panel PREVIEWS can be compared with the colour the server stored.
 */
function rgbToHex(rgb: string): string {
  const parts = rgb.match(/\d+/g)
  expect(parts, `"${rgb}" is an rgb() colour`).not.toBeNull()
  return (
    '#' +
    parts!
      .slice(0, 3)
      .map((n) => Number(n).toString(16).padStart(2, '0'))
      .join('')
  )
}

test('a tag can be created and assigned from the document edit view (#288)', async ({
  page,
  browser,
  cleanup,
}) => {
  // Two logins, two contexts and a document save: comfortably more than the default budget.
  test.setTimeout(120_000)

  const owner = accountName('tcpown')
  const viewer = accountName('tcpview')
  const tagName = uniqueTag('tcp')
  const otherTagName = uniqueTag('tcpother')
  const docTitle = unique('tcp-doc')

  // --- Admin seeds the two accounts ---
  await createUser(page, owner)
  // Teardown runs in REGISTRATION order, and that order is load-bearing here: deleting the
  // account reassigns whatever it still owns to admin, so every API teardown below runs from
  // the ADMIN context and is registered AFTER these two. A teardown through the owner's own
  // context would have to outlive a context this test closes.
  cleanup.defer('delete the owner account', () => deleteUserApi(page.request, owner))
  await createUser(page, viewer)
  cleanup.defer('delete the viewer account', () => deleteUserApi(page.request, viewer))

  const ownerCtx = await freshContext(browser)
  cleanup.defer('close the owner context', () => ownerCtx.close())
  const ownerPage = await ownerCtx.newPage()
  await login(ownerPage, owner, PASSWORD)

  // --- The owner starts with an empty tag list: nothing can match anything typed ---
  const listBefore = await ownerPage.request.get('/api/tag/list')
  expect(listBefore.ok(), 'read the owner tag list').toBeTruthy()
  expect(
    (await listBefore.json()).tags,
    'the fresh account owns no tags, so the create row is reached deterministically',
  ).toEqual([])

  // One existing tag, so the NEGATIVE half of the create row's rule can be asserted against a
  // real match rather than an empty list.
  const otherRes = await ownerPage.request.put('/api/tag', {
    form: { name: otherTagName, color: '#996633' },
  })
  expect(otherRes.ok(), `create the control tag ${otherTagName}`).toBeTruthy()
  const otherTagId = (await otherRes.json()).id as string
  cleanup.defer('delete the control tag', () => deleteTagApi(page.request, otherTagId))

  // --- The owner writes a document and needs a tag that does not exist yet ---
  // Through a full RELOAD, not a hash navigation: the control tag was created over the API
  // after this SPA session had already read (and cached for 60s) an empty tag list, so the
  // picker would otherwise be searching a list the server has since moved past.
  await gotoRaw(ownerPage, '/#/document/add')
  await ownerPage.reload()
  await expectRouteReady(ownerPage, '/#/document/add', ROUTE_ROOT.documentEdit)
  await ownerPage.locator('#edit-title').fill(docTitle)

  // A search that MATCHES offers no create row: creating is what you do when nothing matched.
  await typeInTagSearch(ownerPage, otherTagName)
  await expect(ownerPage.getByRole('option', { name: otherTagName })).toBeVisible()
  await expect(ownerPage.locator('.tp-create-row')).toHaveCount(0)

  // A search that matches nothing does.
  await typeInTagSearch(ownerPage, tagName)
  const createRow = ownerPage.locator('.tp-create-row')
  await expect(createRow).toBeVisible()
  await expect(createRow).toContainText(tagName)

  await createRow.click()

  // --- The panel: pre-filled, and it says what saving will do ---
  const panel = ownerPage.locator('.tag-create-panel')
  await expect(panel).toBeVisible()
  await expect(panel.locator('#tag-create-name')).toHaveValue(tagName)
  await expect(panel.locator('.tag-create-lead')).toContainText(docTitle)
  await expect(panel.locator('.tag-create-perm-hint')).toBeVisible()
  // The overlay it was opened from is out of the way, and stays that way (#234).
  await expect(ownerPage.locator('.p-multiselect-overlay')).toHaveCount(0)

  // --- Choose a colour in the panel ---
  const preview = panel.locator('.color-preview')
  const seededColor = rgbToHex(await preview.evaluate((el) => getComputedStyle(el).backgroundColor))
  expect(seededColor, 'the panel seeds the same colour the tag management page does').toBe(
    DEFAULT_TAG_COLOR,
  )
  // The picker's trigger is brought into view BEFORE its overlay opens. The overlay binds a
  // scroll listener and hides on any scroll of a scrollable ancestor (the drawer body is one),
  // so a scroll performed after it opened tears it down mid-click.
  const colorTrigger = panel.locator('.p-colorpicker-preview')
  await colorTrigger.scrollIntoViewIfNeeded()
  await colorTrigger.click()
  const hue = ownerPage.locator('.p-colorpicker-hue')
  await expect(hue).toBeVisible()
  // SETTLED geometry only: the overlay plays a `scaleY(0.8)` enter transition, and a click
  // aimed at a box that is still animating is aimed at the wrong place (and Playwright
  // rightly refuses it as unstable).
  await ownerPage.waitForFunction(() => {
    const el = document.querySelector('.p-colorpicker-panel')
    return !!el && getComputedStyle(el).transform === 'none'
  })
  // A press near the bottom of the hue bar picks a hue far from the seeded cyan; the exact
  // value is the picker's business, so what is asserted is that it CHANGED and that the tag
  // ends up carrying exactly what the panel previewed.
  const hueBox = (await hue.boundingBox())!
  await hue.click({ position: { x: hueBox.width / 2, y: hueBox.height * 0.85 } })
  // Dismiss the picker's overlay with a click outside it, so it stops covering the
  // permissions row the next step types into.
  await panel.locator('.tag-create-lead').click()
  await expect(ownerPage.locator('.p-colorpicker-panel')).toHaveCount(0)
  await expect
    .poll(async () => rgbToHex(await preview.evaluate((el) => getComputedStyle(el).backgroundColor)))
    .not.toBe(DEFAULT_TAG_COLOR)
  const chosenColor = rgbToHex(
    await preview.evaluate((el) => getComputedStyle(el).backgroundColor),
  )

  // --- Grant the viewer READ on the tag, from inside the panel ---
  const aclAdd = panel.locator('.acl-add')
  await aclAdd.locator('input').first().fill(viewer)
  await ownerPage.getByRole('option', { name: new RegExp(viewer) }).click()
  await aclAdd.getByRole('button', { name: 'Add', exact: true }).click()
  // Nothing has been sent anywhere yet — the tag does not exist — so the grant simply appears
  // in the list, with no "Permission added" toast and no confirmation.
  await expect(panel.locator('.acl-row', { hasText: viewer })).toBeVisible()

  // --- Save the tag ---
  await panel.locator('.tag-create-actions').getByRole('button', { name: 'Save' }).click()
  await expect(panel).toHaveCount(0)

  // The tag is now a selected chip on the form...
  const tagsField = ownerPage.locator('#edit-tags')
  await expect(tagsField).toContainText(tagName)
  // ...and the DOCUMENT is still unsaved: the panel must never write it on the user's behalf.
  await expect(ownerPage).toHaveURL(/#\/document\/add$/)

  // The tag itself exists, with the colour chosen in the panel.
  const tagList = await ownerPage.request.get('/api/tag/list')
  expect(tagList.ok(), 'read the owner tag list after the create').toBeTruthy()
  const created = ((await tagList.json()).tags as Array<{ id: string; name: string; color: string }>).find(
    (t) => t.name === tagName,
  )
  expect(created, `the tag "${tagName}" exists after the panel saved`).toBeTruthy()
  cleanup.defer('delete the created tag', () => deleteTagApi(page.request, created!.id))
  expect(created!.color.toLowerCase(), 'the tag carries the colour the panel previewed').toBe(
    chosenColor,
  )

  // --- Now the user saves the document, and the tag goes with it ---
  await ownerPage.getByRole('button', { name: 'Save' }).click()
  await expect(ownerPage).toHaveURL(/#\/document\/view\//)
  const docId = ownerPage.url().split('/document/view/')[1].split(/[/?#]/)[0]
  cleanup.defer('purge the document', () => deleteDocApi(page.request, docId))

  const docRes = await ownerPage.request.get(`/api/document/${docId}`)
  expect(docRes.ok(), 'read the saved document').toBeTruthy()
  expect(
    ((await docRes.json()).tags as Array<{ id: string }>).map((t) => t.id),
    'the created tag is on the saved document',
  ).toContain(created!.id)

  // --- It is a real tag on the tag management page, not a form-local chip ---
  await gotoRouteReady(ownerPage, '/#/tag', ROUTE_ROOT.tagList)
  await expect(ownerPage.locator('.tag-tree').getByText(tagName, { exact: true })).toBeVisible()

  // --- The permission set IN the panel is effective for a second account ---
  const viewerCtx = await freshContext(browser)
  cleanup.defer('close the viewer context', () => viewerCtx.close())
  const viewerPage = await viewerCtx.newPage()
  await login(viewerPage, viewer, PASSWORD)
  await gotoRaw(viewerPage, '/#/tag')
  await viewerPage.reload()
  await expectRouteReady(viewerPage, '/#/tag', ROUTE_ROOT.tagList)
  await expect(
    viewerPage.locator('.tag-tree').getByText(tagName, { exact: true }),
    'the READ grant made in the panel reached the server with the tag',
  ).toBeVisible()
  // The control tag, which was never granted, stays invisible — so the assertion above is
  // about the grant and not about a viewer who can see everything.
  await expect(viewerPage.locator('.tag-tree').getByText(otherTagName, { exact: true })).toHaveCount(
    0,
  )
})

test('cancelling the panel creates nothing and keeps the typed search (#288)', async ({
  page,
  browser,
  cleanup,
}) => {
  test.setTimeout(90_000)

  const owner = accountName('tcpcan')
  const tagName = uniqueTag('tcpcan')

  await createUser(page, owner)
  cleanup.defer('delete the cancelling account', () => deleteUserApi(page.request, owner))

  const ownerCtx = await freshContext(browser)
  cleanup.defer('close the cancelling context', () => ownerCtx.close())
  const ownerPage = await ownerCtx.newPage()
  await login(ownerPage, owner, PASSWORD)

  await gotoRouteReady(ownerPage, '/#/document/add', ROUTE_ROOT.documentEdit)
  await typeInTagSearch(ownerPage, tagName)
  await ownerPage.locator('.tp-create-row').click()

  const panel = ownerPage.locator('.tag-create-panel')
  await expect(panel).toBeVisible()
  await panel.locator('#tag-create-name').fill(tagName + 'edited')
  await panel.locator('.tag-create-actions').getByRole('button', { name: 'Cancel' }).click()
  await expect(panel).toHaveCount(0)

  // Nothing was created under either name.
  const tags = (await (await ownerPage.request.get('/api/tag/list')).json()).tags as Array<{
    name: string
  }>
  expect(tags, 'a cancelled panel leaves no tag behind').toEqual([])

  // Nothing was selected on the form either, and the search the panel was opened from is
  // still there — a cancel must not throw the user's typing away.
  await expect(ownerPage.locator('#edit-tags .p-multiselect-label')).not.toContainText(tagName)
  await ownerPage.locator('#edit-tags').click()
  await expect(ownerPage.locator('input.tp-filter-input')).toHaveValue(tagName)
})
