import { test, expect } from './fixtures'
import { unique, createDocument, confirmDanger, login, deleteDocApi, deleteUserApi } from './helpers'

test('deleting from the slide-over removes the document and closes the pane (#172)', async ({ page }) => {
  const title = unique('slideover-del')
  await createDocument(page, title)

  await page.goto('/#/document')
  const titleCell = page.getByText(title, { exact: true })
  await expect(titleCell).toBeVisible()
  await titleCell.click()

  // Waiting on the Delete button also absorbs the single-click debounce that opens the pane.
  const delBtn = page.locator('.slide-delete-btn')
  await expect(delBtn).toBeVisible()
  await delBtn.click()
  await confirmDanger(page)

  await expect(page.getByText(title, { exact: true })).toHaveCount(0)
  await expect(page.locator('.slide-delete-btn')).toHaveCount(0)
})

test('a READ-only user sees no Delete button in the slide-over (#172)', async ({ page, browser, cleanup }) => {
  const username = unique('rodel').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const password = 'ReadOnly123'
  const email = `${username}@example.com`

  await page.goto('/#/settings/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const userDialog = page.getByRole('dialog', { name: 'Add user' })
  await userDialog.locator('#add-user-name').fill(username)
  await userDialog.locator('#add-user-email').fill(email)
  await userDialog.locator('#add-user-pass').fill(password)
  await userDialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()
  cleanup.defer('delete the read-only user', () => deleteUserApi(page.request, username))

  const created = await createDocument(page, unique('ro-doc'))
  const id = created.id
  // The document is admin-owned (created through the admin page context), so the
  // owner-scoped permanent delete must run from that same context.
  cleanup.defer('purge the shared document', () => deleteDocApi(page.request, id))
  await page.goto(`/#/document/view/${id}/permissions`)
  await expect(page.getByRole('heading', { name: 'Direct permissions' })).toBeVisible()
  const addForm = page.locator('.add-acl-form', { hasText: 'Add permission' })
  await addForm.locator('input').first().fill(username)
  await page.getByRole('option', { name: new RegExp(username) }).click()
  await addForm.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText('Permission added')).toBeVisible()

  const userContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  cleanup.defer('close the read-only user context', () => userContext.close())
  const userPage = await userContext.newPage()
  await login(userPage, username, password)

  const cell = userPage.getByText(created.title, { exact: true })
  await expect(cell).toBeVisible()
  await cell.click()

  // The Delete button is gated on `writable`, which a READ-only grant leaves false;
  // the Open action confirms the pane itself did open.
  await expect(userPage.getByRole('button', { name: 'Open', exact: true })).toBeVisible()
  await expect(userPage.locator('.slide-delete-btn')).toHaveCount(0)
})
