import { test, expect } from '@playwright/test'
import { unique, confirmDanger } from './helpers'

// Admin CRUD smoke for the settings screens: create one of each entity, see it
// listed, delete it. Each test is self-contained and cleans up after itself.

test('groups: create, list, delete', async ({ page }) => {
  const name = unique('grp').replace(/[^a-z0-9]/gi, '').toLowerCase()
  await page.goto('/#/settings/groups')
  await page.getByRole('button', { name: 'Add group' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#add-group-name').fill(name)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('Group created')).toBeVisible()

  const row = page.getByRole('row', { name: new RegExp(name) })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Delete' }).click()
  await confirmDanger(page)
  await expect(page.getByRole('row', { name: new RegExp(name) })).toHaveCount(0)
})

test('custom metadata: create, list, delete', async ({ page }) => {
  const name = unique('meta')
  await page.goto('/#/settings/metadata')
  await page.getByRole('button', { name: 'Add field' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#metadata-name').fill(name)
  await dialog.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText('Field added')).toBeVisible()

  const row = page.getByRole('row', { name: new RegExp(name) })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Delete metadata field' }).click()
  await confirmDanger(page)
  await expect(page.getByRole('row', { name: new RegExp(name) })).toHaveCount(0)
})

test('webhooks: create, list, delete', async ({ page }) => {
  const url = `https://example.com/hook-${Date.now()}`
  await page.goto('/#/settings/webhooks')
  await page.getByRole('button', { name: 'Add webhook' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#webhook-url').fill(url)
  await dialog.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText('Webhook added')).toBeVisible()

  const row = page.getByRole('row', { name: new RegExp(url.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Delete webhook' }).click()
  await confirmDanger(page)
  await expect(page.getByRole('row', { name: new RegExp(url.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })).toHaveCount(0)
})

test('api keys: create, list, delete', async ({ page }) => {
  const name = unique('key')
  await page.goto('/#/settings/api-keys')
  await page.getByRole('button', { name: 'Create key' }).click()
  const createDialog = page.getByRole('dialog', { name: 'Create API key' })
  await createDialog.locator('#key-name').fill(name)
  await createDialog.getByRole('button', { name: 'Create' }).click()

  // The one-time key-display dialog appears; dismiss it via Done.
  const keyDialog = page.getByRole('dialog', { name: /API key created/i })
  await expect(keyDialog).toBeVisible()
  await keyDialog.getByRole('button', { name: 'Done' }).click()

  const row = page.getByRole('row', { name: new RegExp(name) })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Delete API key' }).click()
  await confirmDanger(page)
  await expect(page.getByRole('row', { name: new RegExp(name) })).toHaveCount(0)
})

test('tag rules: create, list, delete', async ({ page }) => {
  // A rule needs an existing tag; seed one, create the rule against it, clean up.
  const tagName = unique('rule-tag')
  await page.goto('/#/tag')
  await page.getByPlaceholder('Tag name').fill(tagName)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.getByText('Tag created')).toBeVisible()

  const pattern = `invoice-${Date.now()}`
  await page.goto('/#/settings/tag-rules')
  await page.getByRole('button', { name: 'Add rule' }).click()
  const dialog = page.getByRole('dialog', { name: 'New rule' })
  // Tag Select.
  await dialog.locator('#rule-tag').click()
  await page.getByRole('option', { name: tagName }).click()
  await dialog.locator('#rule-pattern').fill(pattern)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('Rule saved')).toBeVisible()

  const row = page.getByRole('row', { name: new RegExp(pattern) })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Delete rule' }).click()
  await confirmDanger(page)
  await expect(page.getByRole('row', { name: new RegExp(pattern) })).toHaveCount(0)

  // Cleanup the seeded tag.
  await page.goto('/#/tag')
  await page.locator('.tag-tree').getByText(tagName, { exact: true }).click()
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})
