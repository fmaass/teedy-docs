import { test, expect } from '@playwright/test'
import { unique, createDocument, confirmDanger } from './helpers'

// Bulk operations over a multi-selection: add a tag, set a language, delete.
// Teedy has no bulk endpoint — each action fans out over single-doc endpoints and
// reports a per-document success/failure summary (see utils/bulkOps.ts).

test('bulk add tag, set language, then delete a multi-selection', async ({ page }) => {
  // Seed a tag and two documents so we have a selection to act on.
  const tagName = unique('bulk-tag')
  await page.goto('/#/tag')
  await page.getByPlaceholder('Tag name').fill(tagName)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.getByText('Tag created')).toBeVisible()

  const titleA = unique('bulk-a')
  const titleB = unique('bulk-b')
  const docA = await createDocument(page, titleA)
  await createDocument(page, titleB)

  await page.goto('/#/document')
  const rowA = page.getByRole('row', { name: new RegExp(titleA) })
  const rowB = page.getByRole('row', { name: new RegExp(titleB) })
  await expect(rowA).toBeVisible()
  await expect(rowB).toBeVisible()

  const bar = page.locator('.bulk-bar')

  // Select both rows and wait for the bulk bar to appear with the count. A bulk op
  // clears the selection (and hides the bar) then refetches the list, so before
  // each op we (re)select from scratch and assert the bar is present.
  async function selectBoth() {
    await page.getByRole('row', { name: new RegExp(titleA) }).getByRole('checkbox').check()
    await page.getByRole('row', { name: new RegExp(titleB) }).getByRole('checkbox').check()
    await expect(bar).toBeVisible()
    await expect(bar.getByText('2 selected')).toBeVisible()
  }

  // Open a bulk action's PrimeVue Popover, pick an option from its Select, and Apply.
  // The whole open→select→apply chain is wrapped in ONE `toPass` because on the narrow
  // mobile viewport each teleported/repositioning overlay is individually flaky: the
  // trigger's `toggle(event)` sometimes does not open the Popover on the first click,
  // and the Select's overlay can be un-wired for a beat after the Popover opens. Making
  // the whole sequence retriable recovers from a dropped popover-open OR a dropped
  // select-open without the test failing. `force` bypasses the stability wait on the
  // perpetually-micro-nudging overlays; the authoritative success signal ("Bulk action
  // complete" toast + bar hidden) is still asserted AFTER, so this never masks a real
  // failure to apply.
  async function bulkPick(triggerName: string, optionName: string | RegExp) {
    const popover = page.locator('.bulk-popover')
    await expect(async () => {
      if (!(await popover.isVisible().catch(() => false))) {
        await bar.getByRole('button', { name: triggerName }).click({ timeout: 2000 })
        await expect(popover).toBeVisible({ timeout: 1500 })
      }
      const combo = popover.getByRole('combobox')
      await combo.click({ force: true, timeout: 2000 })
      await expect(page.getByRole('listbox')).toBeVisible({ timeout: 1500 })
    }).toPass({ timeout: 20000 })
    await page.getByRole('option', { name: optionName }).click()
    await popover.getByRole('button', { name: 'Apply' }).click()
  }

  // Bulk add tag: open the popover, pick the tag, apply. Success surfaces the
  // "Bulk action complete" summary toast, then the selection clears (bar hides).
  await selectBoth()
  await bulkPick('Add tag', tagName)
  await expect(page.getByText('Bulk action complete').first()).toBeVisible()
  await expect(bar).toBeHidden()

  // PROVE the mutation, not the toast: open one affected document and assert the
  // tag is actually present on it (the header renders a TagBadge per tag). A hard
  // reload forces a fresh GET /api/document/<id> — the SPA otherwise serves the
  // tag-less detail cached when the doc was created (the bulk op invalidates the
  // list + facet caches, not each document-detail query).
  await page.goto(`/#/document/view/${docA.id}`)
  await page.reload()
  await expect(page.locator('.doc-header-tags').getByText(tagName, { exact: true })).toBeVisible()

  // Bulk set language.
  await page.goto('/#/document')
  await selectBoth()
  await bulkPick('Set language', 'Français')
  await expect(page.getByText('Bulk action complete').first()).toBeVisible()
  await expect(bar).toBeHidden()

  // PROVE it: open the affected document (hard reload for a fresh detail read) and
  // assert its language badge now reads the French label (the header .lang-badge
  // renders languageLabel('fra')).
  await page.goto(`/#/document/view/${docA.id}`)
  await page.reload()
  await expect(page.locator('.lang-badge')).toHaveText('Français')

  // Bulk delete.
  await page.goto('/#/document')
  await selectBoth()
  await bar.getByRole('button', { name: 'Delete' }).click()
  await confirmDanger(page)

  // Both documents leave the list.
  await expect(page.getByText(titleA, { exact: true })).toHaveCount(0)
  await expect(page.getByText(titleB, { exact: true })).toHaveCount(0)

  // Cleanup the seeded tag.
  await page.goto('/#/tag')
  await page.locator('.tag-tree').getByText(tagName, { exact: true }).click()
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})
