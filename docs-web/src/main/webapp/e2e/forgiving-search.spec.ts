import { test, expect } from '@playwright/test'
import { createDocument, deleteCurrentDocument } from './helpers'

// #53 forgiving search + client-side quick filter.
//
// 1. A bare PARTIAL term finds a document whose title is a longer compound token
//    (no explicit wildcard, no reindex) — the core forgiving-search acceptance.
// 2. The client-side "filter loaded results" box narrows the visible list instantly,
//    without a server round-trip.

test('a bare partial term finds a longer compound; the quick-filter box narrows the list', async ({ page }) => {
  // A German-style compound title plus a run-unique token so the search is deterministic.
  const token = `uebung${Date.now()}`
  const compoundTitle = `${token}Ausbildervertrag`
  const otherTitle = `${token}Randnotiz`

  const compound = await createDocument(page, compoundTitle)
  await createDocument(page, otherTitle)

  await page.goto('/#/document')
  const search = page.getByPlaceholder('Search')

  // Forgiving search: a bare PARTIAL of the compound (not the whole token, no wildcard)
  // finds the compound-titled document. The stock parser would return nothing here.
  const partial = `${token}Ausbild`
  await search.fill(partial)
  await expect(page.getByText(compoundTitle, { exact: true })).toBeVisible()

  // Now search by the shared run token so BOTH documents load, then use the purely
  // client-side quick filter to narrow the VISIBLE rows to the compound one.
  await search.fill(token)
  await expect(page.getByText(compoundTitle, { exact: true })).toBeVisible()
  await expect(page.getByText(otherTitle, { exact: true })).toBeVisible()

  const quickFilter = page.getByPlaceholder('Filter loaded results…')
  await expect(quickFilter).toBeVisible()
  await quickFilter.fill('Randnotiz')
  // The other document remains; the compound one is filtered out of view.
  await expect(page.getByText(otherTitle, { exact: true })).toBeVisible()
  await expect(page.getByText(compoundTitle, { exact: true })).toHaveCount(0)

  // Clearing the quick filter restores both loaded rows (no server refetch needed).
  await quickFilter.fill('')
  await expect(page.getByText(compoundTitle, { exact: true })).toBeVisible()
  await expect(page.getByText(otherTitle, { exact: true })).toBeVisible()

  // Cleanup both documents.
  await search.fill('')
  await page.goto(`/#/document/view/${compound.id}`)
  await deleteCurrentDocument(page)
  await page.goto('/#/document')
  await search.fill(otherTitle)
  await page.getByText(otherTitle, { exact: true }).click()
  await page.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
  await deleteCurrentDocument(page)
})
