import { test, expect, type Locator, type Page } from './fixtures'
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

// #285 slice 1 — a user may edit THEIR OWN comment, and everyone else can tell that they did.
//
// The premise is built entirely by the spec: a second account, a document owned by admin, a READ
// grant to that account, and one comment from each side. Two accounts are what make the two halves
// of the feature observable at all — that the "edited" marker is visible to OTHER readers, and that
// the edit affordance is absent on a comment you did not write.
//
// Comments render oldest-first, so admin's is always the first item and the reader's the second.
// The items are addressed by that index rather than by their text because an OPEN editor holds the
// draft in a textarea VALUE, which is not text content — a text filter stops matching the moment
// the editor opens. `expectComment` re-asserts who wrote each item and what it says before every
// step, so the index can never silently address the wrong comment.

// Force a fresh app load so the second user observes the CURRENT server state rather than the
// comment list their session already cached (see tag-acl.spec.ts for the same idiom).
async function freshGoto(p: Page, url: string, routeRoot: string): Promise<void> {
  await gotoRaw(p, url)
  await p.reload()
  await expectRouteReady(p, url, routeRoot)
}

async function expectComment(item: Locator, author: string, content: string): Promise<void> {
  await expect(item.locator('.comment-author')).toHaveText(author)
  await expect(item.locator('.comment-content')).toHaveText(content)
}

async function postComment(p: Page, text: string): Promise<void> {
  await p.getByLabel('Add a comment').fill(text)
  await p.getByRole('button', { name: 'Post comment' }).click()
  await expect(p.locator('.comment-content', { hasText: text })).toBeVisible()
}

test('the author edits their own comment; other readers see the edited marker but no edit control', async ({
  page,
  browser,
  cleanup,
}) => {
  const username = unique('cmtedit').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const password = 'Password1e2e'
  // One run token, four texts, none of them a substring of another.
  const token = Date.now()
  const adminOriginal = `admin comment ${token} teh typo`
  const adminCorrected = `admin comment ${token} corrected wording`
  const readerOriginal = `reader comment ${token} first`
  const readerCorrected = `reader comment ${token} second`

  const readerCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  cleanup.defer('close the second user context', () => readerCtx.close())
  const readerPage = await readerCtx.newPage()

  // --- Admin: create the second user ---
  await gotoRouteReady(page, '/#/settings/users', ROUTE_ROOT.settingsUsers)
  await page.getByRole('button', { name: 'Add user' }).click()
  const userDialog = page.getByRole('dialog', { name: 'Add user' })
  await userDialog.locator('#add-user-name').fill(username)
  await userDialog.locator('#add-user-email').fill(`${username}@example.com`)
  await userDialog.locator('#add-user-pass').fill(password)
  await userDialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()
  cleanup.defer('delete the second user', () => deleteUserApi(page.request, username))

  // --- Admin: create the document and grant the second user READ on it ---
  const { id } = await createDocument(page, unique('cmtedit-doc'))
  cleanup.defer('purge the comment document', () => deleteDocApi(page.request, id))
  await gotoRouteReady(page, `/#/document/view/${id}/permissions`, ROUTE_ROOT.documentPermissions)
  const addForm = page.locator('.add-acl-form', { hasText: 'Add permission' })
  await addForm.locator('input').first().fill(username)
  await page.getByRole('option', { name: new RegExp(username) }).click()
  await addForm.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText('Permission added')).toBeVisible()

  // --- Admin: post a comment with a typo in it ---
  const commentsUrl = `/#/document/view/${id}/comments`
  await gotoRouteReady(page, commentsUrl, ROUTE_ROOT.documentComments)
  await postComment(page, adminOriginal)
  const adminComment = page.locator('.comment-item').first()
  await expectComment(adminComment, 'admin', adminOriginal)
  // Nothing is marked as edited yet — the marker's absence is the baseline the rest measures against.
  await expect(adminComment.locator('.comment-edited')).toHaveCount(0)

  // --- Reader: sees admin's comment, cannot edit it, and can post one of their own ---
  await login(readerPage, username, password)
  await freshGoto(readerPage, commentsUrl, ROUTE_ROOT.documentComments)
  const adminCommentForReader = readerPage.locator('.comment-item').first()
  await expectComment(adminCommentForReader, 'admin', adminOriginal)
  await expect(adminCommentForReader.getByRole('button', { name: 'Edit comment' })).toHaveCount(0)

  await postComment(readerPage, readerOriginal)
  const readerComment = readerPage.locator('.comment-item').nth(1)
  await expectComment(readerComment, username, readerOriginal)
  // Their OWN comment does offer the control — so the absence above is about authorship, not about
  // the control being missing from the page altogether.
  await expect(readerComment.getByRole('button', { name: 'Edit comment' })).toBeVisible()

  // --- Admin: edit their own comment ---
  await freshGoto(page, commentsUrl, ROUTE_ROOT.documentComments)
  const adminCommentAgain = page.locator('.comment-item').first()
  await expectComment(adminCommentAgain, 'admin', adminOriginal)
  await adminCommentAgain.getByRole('button', { name: 'Edit comment' }).click()
  const editor = adminCommentAgain.locator('.comment-edit-input')
  await expect(editor).toBeVisible()
  await expect(editor).toHaveValue(adminOriginal)
  await editor.fill(adminCorrected)
  await adminCommentAgain.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByText('Comment updated')).toBeVisible()

  await expectComment(adminCommentAgain, 'admin', adminCorrected)
  await expect(page.locator('.comment-content', { hasText: adminOriginal })).toHaveCount(0)
  // The marker, with the edit time available on hover.
  await expect(adminCommentAgain.locator('.comment-edited')).toHaveText('edited')
  await expect(adminCommentAgain.locator('.comment-edited')).toHaveAttribute('title', /Edited on .+/)

  // --- Reader: sees the new wording AND the marker on a comment they still cannot edit ---
  await freshGoto(readerPage, commentsUrl, ROUTE_ROOT.documentComments)
  const editedForReader = readerPage.locator('.comment-item').first()
  await expectComment(editedForReader, 'admin', adminCorrected)
  await expect(editedForReader.locator('.comment-edited')).toHaveText('edited')
  await expect(editedForReader.getByRole('button', { name: 'Edit comment' })).toHaveCount(0)

  // --- Reader: editing is not an admin privilege — they can edit their own comment too ---
  const readerCommentAgain = readerPage.locator('.comment-item').nth(1)
  await expectComment(readerCommentAgain, username, readerOriginal)
  await readerCommentAgain.getByRole('button', { name: 'Edit comment' }).click()
  await readerCommentAgain.locator('.comment-edit-input').fill(readerCorrected)
  await readerCommentAgain.getByRole('button', { name: 'Save' }).click()
  await expect(readerPage.getByText('Comment updated')).toBeVisible()
  await expectComment(readerCommentAgain, username, readerCorrected)
  await expect(readerCommentAgain.locator('.comment-edited')).toHaveText('edited')

  // --- Cancelling an edit discards the draft, locally and on the server ---
  await readerCommentAgain.getByRole('button', { name: 'Edit comment' }).click()
  await readerCommentAgain.locator('.comment-edit-input').fill('discarded draft')
  await readerCommentAgain.getByRole('button', { name: 'Cancel' }).click()
  // The editor closes and the RENDERED comment is the saved text, not the abandoned draft.
  await expect(readerCommentAgain.locator('.comment-edit-input')).toHaveCount(0)
  await expectComment(readerCommentAgain, username, readerCorrected)
  // And a reload proves the draft never reached the server.
  await freshGoto(readerPage, commentsUrl, ROUTE_ROOT.documentComments)
  await expectComment(readerPage.locator('.comment-item').nth(1), username, readerCorrected)
  await expect(readerPage.locator('.comment-content', { hasText: 'discarded draft' })).toHaveCount(0)
})
