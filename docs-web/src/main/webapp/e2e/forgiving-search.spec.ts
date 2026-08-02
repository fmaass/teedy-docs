import { test, expect } from './fixtures'
import { createDocument, deleteCurrentDocument } from './helpers'

// #53 forgiving search + client-side quick filter.
//
// 1. A bare PARTIAL term finds a document whose title is a longer compound token
//    (no explicit wildcard, no reindex) — the core forgiving-search acceptance.
// 2. The client-side "filter loaded results" box narrows the visible list instantly,
//    without a server round-trip.
// 3. #232: the same forgiving expansion applies when the trailing token carries an
//    in-word hyphen (an identifier like `fscompoundbh-1712345678`).

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

test('a partial hyphenated identifier finds the document in a multi-token query', async ({ page }) => {
  // #232. The trailing token of the query is a PARTIAL hyphenated identifier. A '-' is only
  // Lucene's exclusion operator at the start of a clause, so an in-word hyphen must leave the
  // query on the forgiving route and the trailing fragment must still be prefix-expanded.
  const stamp = Date.now()
  const marker = `invoice${stamp}`
  const identifier = `fscompoundbh-${stamp}90210`
  const title = `${marker} ${identifier}`

  const doc = await createDocument(page, title)

  await page.goto('/#/document')
  const search = page.getByPlaceholder('Search')

  // Drop the last three characters of the identifier: a whole-term match is impossible, only
  // prefix expansion of the post-hyphen fragment can find it.
  await search.fill(`${marker} ${identifier.slice(0, -3)}`)
  await expect(page.getByText(title, { exact: true })).toBeVisible()

  // Cleanup.
  await search.fill('')
  await page.goto(`/#/document/view/${doc.id}`)
  await deleteCurrentDocument(page)
})
