import { test, expect, type Page } from './fixtures'
import { resolve } from 'node:path'
import { unique, login, openFileList } from './helpers'

const here = new URL('.', import.meta.url).pathname
const sampleFile = resolve(here, 'fixtures/sample.txt')

// #55 reassign-on-delete, end to end through the real UI:
//   1. Admin creates a departing user (SettingsUsers).
//   2. That user logs in (fresh context), creates a document, and attaches a file.
//   3. Admin deletes the departing user via the delete dialog, PICKING admin as the
//      reassignment target.
//   4. The document now appears under admin (the reassignment target) and its file
//      opens/downloads — proving the content survived the delete and admin can decrypt
//      it (decryption still resolves through the departing user's retained key).
//
// REALNESS: the file-open assertion downloads the reassigned file's bytes and checks
// they are non-empty and match the uploaded content — if reassignment had trashed the
// file (physical delete) or failed to grant admin access, this download would 404/403
// or return empty, failing the test.
// #180: the mirror case — a user who owns nothing is deleted straight from the dialog. No owner
// picker is rendered and the confirm button is live without one, so an admin cleaning up an unused
// account is not forced to nominate a recipient for documents that do not exist.
test('deleting a user who owns nothing needs no reassignment target', async ({ page }) => {
  const username = unique('emptyuser').replace(/[^a-z0-9]/gi, '').toLowerCase()

  await page.goto('/#/settings/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const userDialog = page.getByRole('dialog', { name: 'Add user' })
  await userDialog.locator('#add-user-name').fill(username)
  await userDialog.locator('#add-user-email').fill(`${username}@example.com`)
  await userDialog.locator('#add-user-pass').fill('Password1e2e')
  await userDialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()

  const userRow = page.getByRole('row', { name: new RegExp(username) })
  await expect(userRow).toBeVisible()
  // Opening the dialog re-reads /user/list; wait for it so the assertion below is made on the
  // settled shape rather than on pre-refresh state.
  const listRefresh = page.waitForResponse((r) => r.url().includes('/api/user/list'))
  await userRow.getByRole('button', { name: 'Delete' }).click()

  const deleteDialog = page.getByRole('dialog', { name: 'Delete user' })
  await expect(deleteDialog).toBeVisible()
  await listRefresh
  // The account owns no documents and no tags: the reassignment Select is not rendered at all.
  await expect(deleteDialog.locator('#reassign-target')).toHaveCount(0)

  await deleteDialog.getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByText('User deleted')).toBeVisible()
  await expect(page.getByRole('row', { name: new RegExp(username) })).toHaveCount(0)
})

test('deleting a user reassigns their document to a chosen target, whose file still opens', async ({
  page,
  browser,
}) => {
  const username = unique('reassignee').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const email = `${username}@example.com`
  const password = 'Password1e2e'
  const docTitle = unique('reassign-doc')

  let departingContext: Awaited<ReturnType<typeof browser.newContext>> | null = null
  let docId: string | null = null

  try {
    // --- 1. Admin creates the departing user ---
    await page.goto('/#/settings/users')
    await page.getByRole('button', { name: 'Add user' }).click()
    const userDialog = page.getByRole('dialog', { name: 'Add user' })
    await userDialog.locator('#add-user-name').fill(username)
    await userDialog.locator('#add-user-email').fill(email)
    await userDialog.locator('#add-user-pass').fill(password)
    await userDialog.getByRole('button', { name: 'Create' }).click()
    await expect(page.getByText('User created')).toBeVisible()

    // --- 2. The departing user (fresh context) creates a document with a file ---
    departingContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const departingPage: Page = await departingContext.newPage()
    await login(departingPage, username, password)

    await departingPage.goto('/#/document/add')
    await departingPage.locator('#edit-title').fill(docTitle)
    await departingPage.getByRole('button', { name: 'Save' }).click()
    await expect(departingPage).toHaveURL(/#\/document\/view\//)
    docId = departingPage.url().split('/document/view/')[1].split(/[/?#]/)[0]

    // Attach a file to the document (advanced FileUpload on the document view).
    await departingPage.locator('.p-fileupload-advanced input[type="file"]').setInputFiles(sampleFile)
    await expect(departingPage.getByText('Files uploaded').first()).toBeVisible()
    await openFileList(departingPage)
    await expect(departingPage.locator('.file-list-section .file-name-text', { hasText: 'sample.txt' })).toBeVisible()
    await departingContext.close()
    departingContext = null

    // --- 3. Admin deletes the departing user, reassigning to admin ---
    await page.goto('/#/settings/users')
    const userRow = page.getByRole('row', { name: new RegExp(username) })
    await expect(userRow).toBeVisible()
    await userRow.getByRole('button', { name: 'Delete' }).click()

    const deleteDialog = page.getByRole('dialog', { name: 'Delete user' })
    await expect(deleteDialog).toBeVisible()
    // Pick admin as the reassignment target in the Select.
    await deleteDialog.locator('#reassign-target').click()
    await page.getByRole('option', { name: 'admin', exact: true }).click()
    await deleteDialog.getByRole('button', { name: 'Delete' }).click()
    await expect(page.getByText('User deleted')).toBeVisible()

    // --- 4. The document now belongs to admin and its file still opens ---
    // Admin can open the reassigned document (ownership + READ/WRITE ACL granted).
    await page.goto(`/#/document/view/${docId}`)
    await expect(page.getByRole('heading', { name: docTitle })).toBeVisible()

    // The reassigned file downloads with non-empty, intact content (decryption still
    // resolves through the departing user's retained key).
    const response = await page.request.get(`/api/file/list?id=${docId}`)
    expect(response.ok()).toBeTruthy()
    const fileList = (await response.json()) as { files: Array<{ id: string; name: string }> }
    expect(fileList.files.length).toBe(1)
    const fileId = fileList.files[0].id

    const dataResponse = await page.request.get(`/api/file/${fileId}/data`)
    expect(dataResponse.ok()).toBeTruthy()
    const body = await dataResponse.body()
    expect(body.length).toBeGreaterThan(0)
    expect(body.toString('utf8')).toContain('Teedy e2e fixture text file.')
  } finally {
    if (departingContext) await departingContext.close().catch(() => {})
    // Cleanup the reassigned document (now owned by admin).
    if (docId) {
      await page.goto(`/#/document/view/${docId}`)
      const del = page.getByRole('button', { name: 'Delete', exact: true })
      if (await del.isVisible().catch(() => false)) {
        await del.click()
        const dialog = page.getByRole('alertdialog')
        if (await dialog.isVisible().catch(() => false)) {
          await dialog.getByRole('button', { name: 'Yes' }).click()
        }
      }
    }
  }
})
