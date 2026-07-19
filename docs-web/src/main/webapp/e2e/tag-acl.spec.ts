import { test, expect, type Page } from './fixtures'
import { unique, confirmDanger, deleteUser, login } from './helpers'

// #88: the tag editor gained a Permissions section (AclEditor on the generic /acl
// endpoints). These specs prove the two user-visible guarantees:
//   1. Granting READ on a tag to a second user reveals the tag AND every document
//      carrying it (the tag -> document inheritance), surfaced at grant time by a
//      doc-count disclosure; revoking hides both again.
//   2. The tag owner's mandatory base ACLs render WITHOUT a remove button (immutable) —
//      the client half of the last-WRITE lockout: the owner can never be revoked from the
//      UI (the server enforces the other half; see TestAclResource#testTagLastWriteLockout).

async function createTag(page: Page, name: string): Promise<void> {
  await page.goto('/#/tag')
  await page.getByPlaceholder('Tag name').fill(name)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.locator('.tag-tree').getByText(name, { exact: true })).toBeVisible()
}

// Open a tag's edit page from the tree and wait for the Permissions section to render.
async function openTagPermissions(page: Page, name: string): Promise<void> {
  await page.goto('/#/tag')
  await page.locator('.tag-tree').getByText(name, { exact: true }).click()
  await expect(page).toHaveURL(/#\/tag\//)
  await expect(page.getByRole('heading', { name: 'Permissions' })).toBeVisible()
}

// Force a fresh app load: hash-route navigation is a same-document change, so the SPA's
// cached queries (tag list, staleTime 60s) would not refetch. A full reload re-inits the
// query client, so the second user observes the true post-grant/post-revoke visibility.
async function freshGoto(p: Page, url: string): Promise<void> {
  await p.goto(url)
  await p.reload()
}

async function deleteTagIfPresent(page: Page, name: string): Promise<void> {
  await page.goto('/#/tag')
  const node = page.locator('.tag-tree').getByText(name, { exact: true })
  if (await node.isVisible().catch(() => false)) {
    await node.click()
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    await confirmDanger(page)
    await expect(page).toHaveURL(/#\/tag$/)
  }
}

test('grant READ on a tag to a second user reveals its documents; revoke hides them', async ({ page, browser }) => {
  const username = unique('tagacl').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const email = `${username}@example.com`
  const password = 'Password1e2e'
  const tagName = unique('taclt')
  const docTitle = unique('tacl-doc')

  let docId: string | null = null
  const userCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const userPage = await userCtx.newPage()

  try {
    // --- Admin: create the second user ---
    await page.goto('/#/settings/users')
    await page.getByRole('button', { name: 'Add user' }).click()
    const userDialog = page.getByRole('dialog', { name: 'Add user' })
    await userDialog.locator('#add-user-name').fill(username)
    await userDialog.locator('#add-user-email').fill(email)
    await userDialog.locator('#add-user-pass').fill(password)
    await userDialog.getByRole('button', { name: 'Create' }).click()
    await expect(page.getByText('User created')).toBeVisible()

    // --- Admin: create a tag and a document that carries it ---
    await createTag(page, tagName)
    await page.goto('/#/document/add')
    await page.locator('#edit-title').fill(docTitle)
    await page.locator('#edit-tags').click()
    await page.getByRole('option', { name: tagName }).click()
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page).toHaveURL(/#\/document\/view\//)
    docId = page.url().split('/document/view/')[1].split(/[/?#]/)[0]

    // --- Second user: logs in, sees NEITHER the tag nor the document ---
    await login(userPage, username, password)
    await freshGoto(userPage, '/#/tag')
    await expect(userPage.locator('.tag-tree').getByText(tagName, { exact: true })).toHaveCount(0)
    await freshGoto(userPage, '/#/document')
    await expect(userPage.getByText(new RegExp(docTitle))).toHaveCount(0)

    // --- Admin: grant the second user READ on the tag; the doc-count disclosure fires ---
    await openTagPermissions(page, tagName)
    const addForm = page.locator('.acl-add')
    await addForm.locator('input').first().fill(username)
    await page.getByRole('option', { name: new RegExp(username) }).click()
    // Perm Select defaults to READ ("Can view"); Add.
    await addForm.getByRole('button', { name: 'Add', exact: true }).click()

    // The grant is gated by the disclosure confirmation, which names the current doc count (1).
    const disclosure = page.getByRole('alertdialog')
    await expect(disclosure).toBeVisible()
    await expect(disclosure).toContainText('1 document')
    await disclosure.getByRole('button', { name: 'Yes' }).click()
    await expect(page.getByText('Permission added')).toBeVisible()

    const aclRow = page.locator('.acl-row', { hasText: username })
    await expect(aclRow).toBeVisible()
    await expect(aclRow.getByText('Can view')).toBeVisible()

    // --- Second user: NOW sees the tag AND the document ---
    await freshGoto(userPage, '/#/tag')
    await expect(userPage.locator('.tag-tree').getByText(tagName, { exact: true })).toBeVisible()
    await freshGoto(userPage, '/#/document')
    await expect(userPage.getByText(new RegExp(docTitle))).toBeVisible()

    // --- Admin: revoke the grant ---
    await aclRow.getByRole('button', { name: 'Remove permission' }).click()
    await confirmDanger(page)
    await expect(page.getByText('Permission removed')).toBeVisible()
    await expect(page.locator('.acl-row', { hasText: username })).toHaveCount(0)

    // --- Second user: tag AND document disappear again ---
    await freshGoto(userPage, '/#/tag')
    await expect(userPage.locator('.tag-tree').getByText(tagName, { exact: true })).toHaveCount(0)
    await freshGoto(userPage, '/#/document')
    await expect(userPage.getByText(new RegExp(docTitle))).toHaveCount(0)
  } finally {
    await userCtx.close()
    if (docId) {
      await page.goto(`/#/document/view/${docId}`)
      const del = page.getByRole('button', { name: 'Delete', exact: true })
      if (await del.isVisible().catch(() => false)) {
        await del.click()
        await confirmDanger(page)
      }
    }
    await deleteTagIfPresent(page, tagName)
    await page.goto('/#/settings/users')
    const userRow = page.getByRole('row', { name: new RegExp(username) })
    if (await userRow.isVisible().catch(() => false)) {
      await deleteUser(page, username)
    }
  }
})

test('the tag owner base permissions are immutable (no remove button)', async ({ page }) => {
  const tagName = unique('taclown')
  try {
    await createTag(page, tagName)
    await openTagPermissions(page, tagName)

    // The owner (admin) holds base READ + WRITE — both rows present, NEITHER removable.
    const ownerRows = page.locator('.acl-row', { hasText: 'admin' })
    await expect(ownerRows).toHaveCount(2)
    await expect(ownerRows.getByRole('button', { name: 'Remove permission' })).toHaveCount(0)
    // The immutable lock marker stands in for the missing remove button on each owner row.
    await expect(page.locator('.acl-row .acl-immutable')).toHaveCount(2)
    // Immutability is per-row, not a global read-only: the add form is still present.
    await expect(page.locator('.acl-add')).toBeVisible()
  } finally {
    await deleteTagIfPresent(page, tagName)
  }
})
