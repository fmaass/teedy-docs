import { test, expect } from '@playwright/test'
import { unique, createDocument, confirmDanger } from './helpers'

// Document permissions (ACL) end to end via DocumentViewPermissions:
//   1. Admin creates a second user (SettingsUsers).
//   2. On a fresh document's Permissions tab, admin searches for that user, grants
//      READ ("Can view"), and the grant appears in the direct-permissions list.
//   3. Admin revokes the grant; it disappears from the list.
// The user + document are timestamped and removed in teardown so reruns never collide.

test('grant READ on a document to a second user, see it listed, then revoke it', async ({ page }) => {
  const username = unique('acluser').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const email = `${username}@example.com`

  // Resources are created INSIDE try so the finally cleans up whatever exists even on
  // a mid-test failure.
  let id: string | null = null
  try {
    // --- Create the second user ---
    await page.goto('/#/settings/users')
    await page.getByRole('button', { name: 'Add user' }).click()
    const userDialog = page.getByRole('dialog', { name: 'Add user' })
    await userDialog.locator('#add-user-name').fill(username)
    await userDialog.locator('#add-user-email').fill(email)
    // Password policy: >=1 uppercase, >=1 lowercase, >=1 digit.
    await userDialog.locator('#add-user-pass').fill('Password1e2e')
    await userDialog.getByRole('button', { name: 'Create' }).click()
    await expect(page.getByText('User created')).toBeVisible()
    await expect(page.getByRole('row', { name: new RegExp(username) })).toBeVisible()

    // --- Create a document and grant the user READ on it ---
    id = (await createDocument(page, unique('acl-doc'))).id
    await page.goto(`/#/document/view/${id}/permissions`)
    await expect(page.getByRole('heading', { name: 'Direct permissions' })).toBeVisible()
    // A fresh document already lists the owner's own READ/WRITE ACLs; the second user
    // is NOT among them yet.
    await expect(page.locator('.acl-row', { hasText: username })).toHaveCount(0)

    // The add-permission form: search the user in the AutoComplete, pick the option,
    // leave the perm Select at its default ("Can view" = READ), Add.
    const addForm = page.locator('.add-acl-form', { hasText: 'Add permission' })
    await addForm.locator('input').first().fill(username)
    await page.getByRole('option', { name: new RegExp(username) }).click()
    await addForm.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(page.getByText('Permission added')).toBeVisible()

    // The grant shows in the direct-permissions list with the READ badge ("Can view").
    const aclRow = page.locator('.acl-row', { hasText: username })
    await expect(aclRow).toBeVisible()
    await expect(aclRow.getByText('Can view')).toBeVisible()

    // --- Revoke it ---
    await aclRow.getByRole('button', { name: 'Remove permission' }).click()
    await confirmDanger(page)
    await expect(page.getByText('Permission removed')).toBeVisible()
    await expect(page.locator('.acl-row', { hasText: username })).toHaveCount(0)
  } finally {
    // Cleanup the document (if it got created).
    if (id) {
      await page.goto(`/#/document/view/${id}`)
      const del = page.getByRole('button', { name: 'Delete', exact: true })
      if (await del.isVisible().catch(() => false)) {
        await del.click()
        await confirmDanger(page)
      }
    }
    // Cleanup the second user.
    await page.goto('/#/settings/users')
    const userRow = page.getByRole('row', { name: new RegExp(username) })
    if (await userRow.isVisible().catch(() => false)) {
      await userRow.getByRole('button', { name: 'Delete', exact: true }).click()
      await confirmDanger(page)
    }
  }
})
