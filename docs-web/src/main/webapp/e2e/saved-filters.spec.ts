import { test, expect, type Page } from '@playwright/test'
import { unique, confirmDanger, toggleTagFilter } from './helpers'

// #42: per-user saved filters. A user builds a filter (an included tag + free-text
// search), SAVES it by name, CLEARS the filter, RE-APPLIES it from the search-bar
// dropdown, and DELETES it. The load-bearing assertion is that the re-applied
// filter is LIVE (the filtered result set holds after the list refresh), not just
// that the URL changed — applying pushes the stored query through the existing
// initFromUrl() hydration path.
//
// DETERMINISM: two documents are created — one carrying the tag AND matching the
// search term, one matching neither. After re-applying, the spec waits for the
// POST-refresh list state (the non-matching row detached AND the matching row
// present) before asserting — the pre-refresh render shows both, so the barrier
// guarantees no assertion races the filtered response. No conditional branching.

function rowFor(page: Page, title: string) {
  return page.getByRole('row', {
    name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
}

async function createTag(page: Page, name: string) {
  await page.goto('/#/tag')
  await page.getByPlaceholder('Tag name').fill(name)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.getByText('Tag created')).toBeVisible()
  await expect(page.locator('.tag-tree').getByText(name, { exact: true })).toBeVisible()
}

async function createDocWithTag(page: Page, title: string, tag: string) {
  await page.goto('/#/document/add')
  await page.locator('#edit-title').fill(title)
  await page.locator('#edit-tags').click()
  await page.getByRole('option', { name: tag }).click()
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
}

async function createPlainDoc(page: Page, title: string) {
  await page.goto('/#/document/add')
  await page.locator('#edit-title').fill(title)
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
}

test('save a tag+text filter, clear, re-apply from the dropdown, delete (#42)', async ({ page }) => {
  const tag = unique('sf-tag')
  const term = unique('sfterm')
  const matchTitle = `${term}-match`
  const otherTitle = unique('sf-other')
  const filterName = unique('sf-filter')

  await createTag(page, tag)
  // The matching document carries the tag AND the search term in its title.
  await createDocWithTag(page, matchTitle, tag)
  // The other document matches neither the tag nor the term.
  await createPlainDoc(page, otherTitle)

  await page.goto('/#/document')
  const matchRow = rowFor(page, matchTitle)
  const otherRow = rowFor(page, otherTitle)
  await expect(matchRow).toBeVisible()
  await expect(otherRow).toBeVisible()

  // Build the filter: include the tag from the tag tree (desktop side panel OR the
  // mobile Drawer — toggleTagFilter opens the Drawer on mobile) + type the search
  // term. On mobile, selecting the tag closes the Drawer, so re-derive the panel to
  // read back aria-pressed.
  await toggleTagFilter(page, new RegExp(tag))
  await expect(page).toHaveURL(/[?&]tags=/)

  await page.getByPlaceholder('Search', { exact: true }).fill(term)
  await expect(page).toHaveURL(/[?&]search=/)

  // Save the current filter by name via the search-bar affordance + dialog.
  await page.getByRole('button', { name: 'Save filter' }).click()
  await page.locator('#saved-filter-name').fill(filterName)
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Filter saved')).toBeVisible()

  // Clear the filter: back to the unfiltered list (both rows visible again).
  await page.getByRole('button', { name: 'Clear' }).click()
  await expect(page).not.toHaveURL(/[?&]tags=/)
  await expect(matchRow).toBeVisible()
  await expect(otherRow).toBeVisible()

  // Re-apply from the saved-filters dropdown.
  await page.getByRole('button', { name: 'Saved filters' }).click()
  // exact: the delete control's accessible name now also contains the filter name
  // (Delete saved filter "<name>"), so match the apply button by its exact name.
  await page.getByRole('button', { name: filterName, exact: true }).click()

  // The URL carries the stored filter again.
  await expect(page).toHaveURL(/[?&]tags=/)
  await expect(page).toHaveURL(/[?&]search=/)

  // POST-refresh barrier: only once the FILTERED response has rendered is the
  // non-matching row detached AND the matching row present. Assert the live result
  // set — this fails if applying the saved filter did not actually drive the query.
  await expect(otherRow).toBeHidden()
  await expect(matchRow).toBeVisible()

  // Delete the saved filter from the dropdown via the danger confirm. The delete
  // control's accessible name identifies its filter, so this targets THIS test's
  // filter even when other saved filters (residue from a prior run) coexist.
  await page.getByRole('button', { name: 'Saved filters' }).click()
  // The dropdown Popover keeps micro-repositioning after open (PrimeVue recomputes
  // its position via observers), so Playwright's stability check on the delete button
  // never settles and the actionability wait times out. Assert the button is present
  // and visible (its unique per-filter accessible name — the accessibility fix — makes
  // this unambiguous), then dispatch the click directly. The delete effect is still
  // fully verified below (the confirm dialog + "Filter deleted" toast + filter-gone).
  const deleteButton = page.getByRole('button', {
    name: `Delete saved filter "${filterName}"`,
    exact: true,
  })
  await expect(deleteButton).toBeVisible()
  await deleteButton.click({ force: true })
  await confirmDanger(page)
  await expect(page.getByText('Filter deleted')).toBeVisible()

  // Re-opening the dropdown, THIS test's filter is gone (assert on the owned filter,
  // not the global empty state — other saved filters may legitimately remain).
  await page.getByRole('button', { name: 'Saved filters' }).click()
  await expect(page.getByRole('button', { name: filterName, exact: true })).toHaveCount(0)
})
