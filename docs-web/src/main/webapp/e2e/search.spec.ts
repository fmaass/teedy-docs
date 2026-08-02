import { test, expect } from './fixtures'
import {
  uniqueTag,
  createDocument,
  confirmDanger,
  gotoRouteReady,
  ROUTE_ROOT,
  gotoDocumentList,
} from './helpers'

// Full-text search: a created document is found by a title term and by a tag:
// operator, and the search-help popover lists the supported operators.

test('full-text and tag: operator search find a document; help popover lists operators', async ({ page }) => {
  const tagName = uniqueTag('srch-tag')
  await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
  await page.getByPlaceholder('Tag name').fill(tagName)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.getByText('Tag created')).toBeVisible()

  // A document with a distinctive title token, tagged with the seeded tag.
  const token = `zephyr${Date.now()}`
  const title = `Search ${token}`
  // In-app hash navigation away from /#/tag: wait for the add form's own root, not just
  // for `location` to change, before filling the form.
  await gotoRouteReady(page, '/#/document/add', ROUTE_ROOT.documentEdit)
  await page.locator('#edit-title').fill(title)
  await page.locator('#edit-tags').click()
  await page.getByRole('option', { name: tagName }).click()
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)

  await gotoDocumentList(page)
  const search = page.getByPlaceholder('Search')

  // Full-text: the unique token returns exactly our document.
  await search.fill(token)
  await expect(page.getByText(title, { exact: true })).toBeVisible()

  // tag: operator: filter by the seeded tag name, our document is still present.
  await search.fill(`tag:${tagName}`)
  await expect(page.getByText(title, { exact: true })).toBeVisible()

  // A term matching nothing yields no rows for our document.
  await search.fill(`nonexistent${token}xyz`)
  await expect(page.getByText(title, { exact: true })).toHaveCount(0)

  await search.fill('')

  // Search help popover: the '?' button opens a panel listing operators.
  await page.getByRole('button', { name: 'Search help' }).click()
  const help = page.locator('.search-help')
  await expect(help).toBeVisible()
  await expect(help.getByText('tag:invoice')).toBeVisible()
  await expect(help.getByText('mime:application/pdf')).toBeVisible()

  // Cleanup: delete the document and the tag.
  await page.keyboard.press('Escape')
  await search.fill(token)
  await page.getByText(title, { exact: true }).click()
  await page.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)

  await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
  await page.locator('.tag-tree').getByText(tagName, { exact: true }).click()
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})
