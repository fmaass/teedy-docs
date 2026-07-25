import { test, expect } from './fixtures'
import type { Locator } from '@playwright/test'
import { unique, createDocument, confirmDanger, deleteDocApi, deleteTagByNameApi } from './helpers'

// Bulk operations over a multi-selection: add a tag, set a language, delete.
// Teedy has no bulk endpoint — each action fans out over single-doc endpoints and
// reports a per-document success/failure summary (see utils/bulkOps.ts).

/**
 * #199/1 — REGRESSION GUARD: nothing may cover the bulk Apply button when it is clicked.
 *
 * The reported failure was a pointer interception at the mobile viewport, named against
 * the picker inside `.bulk-select` and the `.bulk-bar` toolbar. Playwright's own
 * actionability retry turns that into a bare 10 s timeout, so the mechanism only ever
 * showed up in an error tail. This hit-tests the button's centre and NAMES whatever
 * receives the click instead. A beat while an overlay unmounts is retried away by the
 * poll; a genuine layout overlap never resolves and reports the offending element.
 *
 * Asserted at BOTH viewports: the interception was mobile-only, but the invariant ("the
 * Apply button is the topmost element at its own centre") holds everywhere.
 */
async function clickApplyUnobstructed(popover: Locator): Promise<void> {
  const apply = popover.getByRole('button', { name: 'Apply' })
  await expect(apply).toBeVisible()
  await apply.scrollIntoViewIfNeeded()
  await expect
    .poll(
      () =>
        apply.evaluate((el) => {
          const box = el.getBoundingClientRect()
          const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
          if (!hit) return 'nothing (the Apply button is outside the viewport)'
          if (el.contains(hit)) return 'self'
          const known = [
            '.p-multiselect-overlay',
            '.p-select-overlay',
            '.tag-multiselect',
            '.bulk-select',
            '.bulk-bar',
            '.p-toast',
          ]
          const owner = known.find((selector) => hit.closest(selector))
          return owner ?? `${hit.tagName.toLowerCase()}[class="${hit.getAttribute('class') ?? ''}"]`
        }),
      { message: 'what receives a click at the centre of the bulk Apply button' },
    )
    .toBe('self')
  await apply.click()
}

test('bulk add tag, set language, then delete a multi-selection', async ({ page, cleanup }) => {
  // Seed a tag and two documents so we have a selection to act on.
  const tagName = unique('bulk-tag')
  await page.goto('/#/tag')
  await page.getByPlaceholder('Tag name').fill(tagName)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.getByText('Tag created')).toBeVisible()
  // This spec had NO teardown at all: it cleaned up only on the happy path, at the end of
  // the body, and its bulk delete merely TRASHES. Both documents and (on any failure) the
  // tag survived every run. The defers below purge them whatever the body does — each is a
  // no-op when the body's own cleanup already ran.
  cleanup.defer('delete the seeded tag', () => deleteTagByNameApi(page.request, tagName))

  const titleA = unique('bulk-a')
  const titleB = unique('bulk-b')
  const docA = await createDocument(page, titleA)
  cleanup.defer('purge the first seeded document', () => deleteDocApi(page.request, docA.id))
  const docB = await createDocument(page, titleB)
  cleanup.defer('purge the second seeded document', () => deleteDocApi(page.request, docB.id))

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

  // Open the bulk TAG popover, pick a tag from the shared TagPicker, and Apply (#182).
  // Split from bulkPick below because that helper drives a `Select`, which the tag
  // action no longer uses: the tag picker is a MultiSelect (that is what renders the
  // coloured chips), and a MultiSelect keeps its panel OPEN after an option click
  // where a Select closes it. The still-open panel would sit over the Apply button, so
  // this path closes it explicitly first. The retriable open loop is the same one the
  // Select path needs on the narrow mobile viewport.
  async function bulkPickTag(optionName: string | RegExp) {
    const popover = page.locator('.bulk-popover')
    await expect(async () => {
      if (!(await popover.isVisible().catch(() => false))) {
        await bar.getByRole('button', { name: 'Add tag' }).click({ timeout: 2000 })
        await expect(popover).toBeVisible({ timeout: 1500 })
      }
      // The popover opens the picker's overlay itself (the keyboard path); a click is
      // only the fallback for a beat where that did not land.
      if (!(await page.getByRole('listbox').isVisible().catch(() => false))) {
        await popover.locator('.tag-multiselect').click({ force: true, timeout: 2000 })
      }
      await expect(page.getByRole('listbox')).toBeVisible({ timeout: 1500 })
    }).toPass({ timeout: 20000 })
    await page.getByRole('option', { name: optionName }).click()
    // MultiSelect stops Escape from propagating while its overlay is open, so this
    // closes the option panel WITHOUT also closing the surrounding Popover.
    await page.keyboard.press('Escape')
    await expect(page.getByRole('listbox')).toBeHidden()
    await expect(popover).toBeVisible()
    await clickApplyUnobstructed(popover)
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
    await clickApplyUnobstructed(popover)
  }

  // Bulk add tag: open the popover, pick the tag, apply. Success surfaces the
  // "Bulk action complete" summary toast, then the selection clears (bar hides).
  await selectBoth()
  await bulkPickTag(tagName)
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

// #182: the bulk tag picker was the only one of the four tag-add surfaces still on a
// plain, uncoloured, keyboard-unreachable Select. It now renders the same TagPicker as
// the edit form, so this covers what that switch bought: the ARIA of a real combobox,
// #171's keyboard acceptance (the filter input IS document.activeElement once the
// popover opens, with no second click), a coloured chip for the pick, and — the part
// batching depends on — exactly ONE tag per Apply.
test('the bulk tag picker is filterable, keyboard-operable and applies exactly one tag', async ({ page, cleanup }) => {
  // Short prefixes on purpose: unique() appends ~26 chars (epoch + pid + counter) and
  // TagResource caps a tag name at 36, so anything over ~10 chars here 400s on create
  // once the per-worker counter reaches three digits.
  const tagName = unique('bkt')
  await page.goto('/#/tag')
  await page.getByPlaceholder('Tag name').fill(tagName)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.locator('.tag-tree').getByText(tagName, { exact: true })).toBeVisible()
  cleanup.defer('delete the seeded tag', () => deleteTagByNameApi(page.request, tagName))

  // A second tag the filter must winnow away, proving the filter box actually filters.
  const otherTag = unique('bko')
  await page.getByPlaceholder('Tag name').fill(otherTag)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.locator('.tag-tree').getByText(otherTag, { exact: true })).toBeVisible()
  cleanup.defer('delete the filtered-out tag', () => deleteTagByNameApi(page.request, otherTag))

  const title = unique('bulk-kbd-doc')
  const doc = await createDocument(page, title)
  cleanup.defer('purge the seeded document', () => deleteDocApi(page.request, doc.id))

  await page.goto('/#/document')
  const row = page.getByRole('row', { name: new RegExp(title) })
  await expect(row).toBeVisible()
  await row.getByRole('checkbox').check()

  const bar = page.locator('.bulk-bar')
  const popover = page.locator('.bulk-popover')
  await expect(bar).toBeVisible()

  // Open the tag action. The ONLY click in the keyboard path is the toolbar trigger:
  // the popover opens the picker's overlay itself and autoFilterFocus lands the caret
  // in the filter, so the very next keystroke types into it.
  const filterInput = page.locator('.p-multiselect-filter')
  await expect(async () => {
    if (!(await popover.isVisible().catch(() => false))) {
      await bar.getByRole('button', { name: 'Add tag' }).click({ timeout: 2000 })
      await expect(popover).toBeVisible({ timeout: 1500 })
    }
    await expect(filterInput).toBeFocused({ timeout: 2000 })
  }).toPass({ timeout: 20000 })

  // ARIA: a combobox owning a listbox, reported expanded while the panel is open.
  const combobox = popover.locator('[role="combobox"]')
  await expect(combobox).toHaveAttribute('aria-haspopup', 'listbox')
  await expect(combobox).toHaveAttribute('aria-expanded', 'true')
  await expect(page.getByRole('listbox')).toBeVisible()

  // Keyboard only from here: type to winnow, arrow to the survivor, Enter to pick.
  await page.keyboard.type(tagName)
  await expect(page.getByRole('option', { name: tagName })).toBeVisible()
  await expect(page.getByRole('option', { name: otherTag })).toHaveCount(0)
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')

  // The pick renders as a COLOURED chip — the old Select showed a plain grey label.
  const chip = popover.locator('.teedy-tag', { hasText: tagName })
  await expect(chip).toBeVisible()
  const background = await chip.evaluate((el) => getComputedStyle(el).backgroundColor)
  expect(background).toMatch(/^rgba?\(/)
  expect(background).not.toBe('rgba(0, 0, 0, 0)')

  // One tag per Apply: with a tag held, every OTHER option is disabled, so a second
  // tag cannot enter the selection and Apply can only ever carry one id.
  await expect(page.getByRole('option', { name: tagName })).toHaveAttribute('aria-selected', 'true')
  await filterInput.fill(otherTag)
  await expect(page.getByRole('option', { name: otherTag })).toHaveAttribute('aria-disabled', 'true')

  // Escape closes only the option panel (MultiSelect stops it propagating), leaving
  // the popover and its Apply button in place.
  await page.keyboard.press('Escape')
  await expect(page.getByRole('listbox')).toBeHidden()
  await expect(popover).toBeVisible()
  await clickApplyUnobstructed(popover)

  await expect(page.getByText('Bulk action complete').first()).toBeVisible()
  await expect(bar).toBeHidden()

  // PROVE the mutation: exactly the picked tag landed on the document, and only it.
  await page.goto(`/#/document/view/${doc.id}`)
  await page.reload()
  const headerTags = page.locator('.doc-header-tags')
  await expect(headerTags.getByText(tagName, { exact: true })).toBeVisible()
  await expect(headerTags.getByText(otherTag, { exact: true })).toHaveCount(0)
})
