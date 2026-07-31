import { test, expect, type Page } from './fixtures'
import { unique, uniqueTag, confirmDanger, toggleTagFilter } from './helpers'

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

test('save a tag+text filter, clear, re-apply from the dropdown, delete (#42, #81 de-flaked)', async ({ page }) => {
  const tag = uniqueTag('sf-tag')
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
  // Same popover instability as the delete button below (#81): the dropdown Popover
  // keeps micro-repositioning after open (PrimeVue recomputes its position via
  // observers), so the apply button "is not stable" and Playwright's actionability
  // wait times out / the element detaches mid-retry. Assert it is present + visible
  // (its exact per-filter accessible name is unambiguous), then dispatch the click
  // directly. The apply effect is fully verified below (URL carries the stored filter
  // + the POST-refresh filtered result set).
  const applyButton = page.getByRole('button', { name: filterName, exact: true })
  await expect(applyButton).toBeVisible()
  await applyButton.click({ force: true })

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

// #193: the saved-filter list gained a name search, a sort-direction toggle and a
// rename affordance, and saving under an existing name now offers a confirmed
// OVERWRITE instead of dead-ending on the duplicate guard. Every assertion below is
// scoped to THIS test's two filters (residue from other specs may legitimately
// coexist in the dropdown), and the overwrite is proven by APPLYING the replaced
// filter and reading the driven query back off the URL — not by the toast alone.

// A toast is asserted AND waited out before the next click: on the 393px viewport the
// toast stack overlays the top of the page, and a hit-target check on a control beneath
// it fails. Every toast here has life:3000, so it self-dismisses well inside the budget.
async function expectToastAndSettle(page: Page, text: string) {
  const toast = page.getByText(text).first()
  await expect(toast).toBeVisible()
  await expect(toast).toBeHidden({ timeout: 15000 })
}

async function saveCurrentFilterAs(page: Page, term: string, name: string) {
  await page.getByPlaceholder('Search', { exact: true }).fill(term)
  await expect(page).toHaveURL(/[?&]search=/)
  await page.getByRole('button', { name: 'Save filter' }).click()
  await page.locator('#saved-filter-name').fill(name)
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expectToastAndSettle(page, 'Filter saved')
}

async function openSavedFiltersDropdown(page: Page) {
  await page.getByRole('button', { name: 'Saved filters' }).click()
  await expect(page.locator('#saved-filter-search')).toBeVisible()
}

async function listedFilterNames(page: Page): Promise<string[]> {
  return (await page.locator('.saved-filters-apply').allTextContents()).map((s) => s.trim())
}

// The dropdown Popover keeps micro-repositioning after open (PrimeVue recomputes its
// position via observers), so Playwright's stability wait never settles on a control
// inside it — the same #81 workaround the first test documents: assert visible, then
// dispatch the click.
async function clickInPopover(page: Page, name: string) {
  const control = page.getByRole('button', { name, exact: true })
  await expect(control).toBeVisible()
  await control.click({ force: true })
}

test('sort toggle, name search, rename and confirmed overwrite (#193)', async ({ page }) => {
  const prefix = unique('sf2')
  const alpha = `${prefix}-alpha`
  const omega = `${prefix}-omega`
  const renamed = `${prefix}-renamed`

  await page.goto('/#/document')
  await saveCurrentFilterAs(page, `${prefix}-one`, alpha)
  await saveCurrentFilterAs(page, `${prefix}-two`, omega)

  // --- Sort direction ---
  await openSavedFiltersDropdown(page)
  let names = await listedFilterNames(page)
  expect(names).toContain(alpha)
  expect(names).toContain(omega)
  expect(names.indexOf(alpha)).toBeLessThan(names.indexOf(omega))

  await clickInPopover(page, 'Sort Z to A')
  names = await listedFilterNames(page)
  expect(names.indexOf(omega)).toBeLessThan(names.indexOf(alpha))

  await clickInPopover(page, 'Sort A to Z')
  names = await listedFilterNames(page)
  expect(names.indexOf(alpha)).toBeLessThan(names.indexOf(omega))

  // --- Name search (case-insensitive) ---
  await page.locator('#saved-filter-search').fill(omega.toUpperCase())
  await expect(page.getByRole('button', { name: omega, exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: alpha, exact: true })).toHaveCount(0)

  await page.locator('#saved-filter-search').fill(`${prefix}-nothing-matches`)
  await expect(page.getByText('No filters match')).toBeVisible()

  await page.locator('#saved-filter-search').fill('')
  await expect(page.getByRole('button', { name: alpha, exact: true })).toBeVisible()

  // --- Rename ---
  await clickInPopover(page, `Rename saved filter "${alpha}"`)
  await expect(page.locator('#saved-filter-rename-name')).toHaveValue(alpha)
  await page.locator('#saved-filter-rename-name').fill(renamed)
  await page.getByRole('button', { name: 'Rename', exact: true }).click()
  await expectToastAndSettle(page, 'Filter renamed')

  await openSavedFiltersDropdown(page)
  await expect(page.getByRole('button', { name: renamed, exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: alpha, exact: true })).toHaveCount(0)
  // Applying it proves the rename carried the STORED query over verbatim (and closes
  // the dropdown, as applyFilter hides it).
  await clickInPopover(page, renamed)
  await expect(page).toHaveURL(new RegExp(`[?&]search=${prefix}-one`))

  // --- Overwrite: saving under an existing name replaces that filter's query ---
  await page.getByPlaceholder('Search', { exact: true }).fill(`${prefix}-replaced`)
  await expect(page).toHaveURL(new RegExp(`[?&]search=${prefix}-replaced`))
  await page.getByRole('button', { name: 'Save filter' }).click()
  await page.locator('#saved-filter-name').fill(renamed)
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await confirmDanger(page)
  await expectToastAndSettle(page, 'Filter replaced')

  // Applying it drives the REPLACED query, not the original one.
  await page.getByRole('button', { name: 'Clear' }).click()
  await expect(page).not.toHaveURL(/[?&]search=/)
  await openSavedFiltersDropdown(page)
  await clickInPopover(page, renamed)
  await expect(page).toHaveURL(new RegExp(`[?&]search=${prefix}-replaced`))

  // Cleanup: both filters are removed so the dropdown does not accumulate residue.
  for (const name of [renamed, omega]) {
    await openSavedFiltersDropdown(page)
    await clickInPopover(page, `Delete saved filter "${name}"`)
    await confirmDanger(page)
    await expectToastAndSettle(page, 'Filter deleted')
  }
  // The toolbar only renders when at least one filter remains, so re-open plainly:
  // other specs' filters may or may not coexist here.
  await page.getByRole('button', { name: 'Saved filters' }).click()
  await expect(page.getByRole('button', { name: renamed, exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: omega, exact: true })).toHaveCount(0)
})
