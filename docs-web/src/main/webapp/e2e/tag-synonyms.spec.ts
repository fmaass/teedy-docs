import { test, expect, type Page } from './fixtures'
import type { Locator } from '@playwright/test'
import {
  unique,
  uniqueTag,
  login,
  deleteDocApi,
  deleteTagByNameApi,
  deleteUserApi,
  ROUTE_ROOT,
  gotoRouteReady,
  gotoRaw,
  expectRouteReady,
} from './helpers'

// #280 — a tag can carry synonyms, and a synonym is a second name FOR THAT TAG: it finds the
// tag's documents in search, it offers the tag in a tag input, and it never crosses a permission
// boundary. This spec walks the reporter's own flow end to end and then checks the boundary:
//
//   1. give a tag a synonym on its edit page,
//   2. type the SYNONYM in the document editor's tag field and get the TAG offered, labelled
//      with the reason,
//   3. take that option and save — the document carries the CANONICAL tag,
//   4. search `tag:<synonym>` and find the document,
//   5. a second account, which cannot read that tag, resolves the same word to nothing.
//
// Everything the spec needs it creates itself: its own tag names, its own document, its own
// second user. It never asserts against tags or documents it did not make.

async function createTag(page: Page, name: string): Promise<void> {
  await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
  await page.getByPlaceholder('Tag name').fill(name)
  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.locator('.tag-tree').getByText(name, { exact: true })).toBeVisible()
}

/** Open a tag's edit page from the management tree. */
async function openTagEdit(page: Page, name: string): Promise<void> {
  await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
  await page.locator('.tag-tree').getByText(name, { exact: true }).click()
  await expect(page).toHaveURL(/#\/tag\//)
  await expect(page.locator('#tag-synonym')).toBeVisible()
}

async function addSynonym(page: Page, synonym: string): Promise<void> {
  await page.locator('#tag-synonym').fill(synonym)
  await page.locator('.synonym-row').getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.locator('.synonym-chip', { hasText: synonym })).toBeVisible()
}

/**
 * A full app reload. Hash-route navigation is a same-document change, so the SPA's cached tag
 * list (staleTime 60s) would answer from memory — and a synonym that has just been saved has to
 * reach the tag INPUTS, not only the form that wrote it.
 */
async function freshGoto(p: Page, url: string, routeRoot: string): Promise<void> {
  await gotoRaw(p, url)
  await p.reload()
  await expectRouteReady(p, url, routeRoot)
}

// --- The tag picker, hardened the way its three sibling specs already are -----------------
//
// TEEDY-151: this step — click the option the typed SYNONYM offers — timed out on the mobile
// projection in roughly one scheduled run in five, with no product defect behind it. The
// picker's overlay is portaled to <body>, so the option a click aims at does not live inside
// the field that opened it, and the filtered list is WIDER than the 393 px mobile viewport
// (`.p-multiselect-option` is `white-space: nowrap`, and this spec's option carries both names:
// "<tag> (via <synonym>)"). Filling the filter therefore re-anchors the panel to the viewport's
// left edge (`inset-inline-start: 40px` -> `0px`, and in one failing run from ABOVE the field to
// below it) and widens it past the viewport — measured on the same image: 310.6 px -> 438.6 px,
// document scrollWidth 439 against a 393 px client width — which expands the layout viewport
// under the visual one (window.innerWidth 393 -> 439, innerHeight 727 -> 813 locally; 393 -> 451
// -> 458 in CI). The failing click's hit test then resolved onto the multiselect TRIGGER's own
// label, ~100 px above the option, on every one of its twenty retries.
//
// Playwright's actionability retry turns all of that into a bare timeout, which is why the
// mechanism only ever showed up in an error tail. The three guards below are the ones the
// siblings that do NOT flake already carry:
//
//   * the overlay is the search scope (tags.spec.ts) — the option and the filter box are read
//     through `.p-multiselect-overlay`, so nothing else in the document can answer for them,
//   * the option's own centre is hit-tested before the click (bulk.spec.ts) — an interception
//     is NAMED rather than left to a 10 s actionability timeout,
//   * the picker is opened only when it is not already open (tag-create-panel.spec.ts) —
//     clicking the field TOGGLES the overlay, and PrimeVue defers the close by a macrotask.

/**
 * Open the document form's tag picker, but only if its overlay is not already up: clicking the
 * field TOGGLES it, and a second unconditional click reads as "still open" for long enough to
 * type into, then tears the overlay down under the assertions (tag-create-panel.spec.ts:66-74).
 * The overlay is portaled to <body>, not nested in the field, and it is what the caller reads
 * the filter box and the options through.
 */
async function openTagPicker(page: Page): Promise<Locator> {
  const overlay = page.locator('.p-multiselect-overlay')
  if (!(await overlay.isVisible())) {
    await page.locator('#edit-tags').click()
  }
  await expect(overlay).toBeVisible()
  return overlay
}

/**
 * Click an option in the tag picker, hit-testing its centre first and NAMING whatever would
 * receive the click instead (bulk.spec.ts:38-60). A beat while the overlay finishes moving is
 * retried away by the poll; a genuine overlap never resolves and reports the offending element
 * — the trigger, a toast, the drawer — instead of a bare "locator.click: Timeout 10000ms".
 *
 * The poll also refuses to click while the LAYOUT VIEWPORT is still changing size, which is the
 * state every observed failure clicked into: the widened panel pushed window.innerWidth/
 * innerHeight from 448x829 to 451x835 and from 451x835 to 458x848 in the two CI traces, and from
 * 456x842 to 456x844 in the local reproduction — each time between the start of the click and its
 * first hit test. Two equal consecutive samples mean the page has stopped resizing under it.
 */
async function clickOptionUnobstructed(option: Locator, what: string): Promise<void> {
  await expect(option).toBeVisible()
  await option.scrollIntoViewIfNeeded()
  let previous = ''
  await expect
    .poll(
      async () => {
        const state = await option.evaluate((el) => {
          const box = el.getBoundingClientRect()
          const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
          const viewport = `${window.innerWidth}x${window.innerHeight}`
          const known = [
            '.p-multiselect-overlay',
            '.tag-multiselect',
            '.p-multiselect',
            '.p-toast',
            '.p-drawer',
            '.p-dialog',
          ]
          if (!hit) return { viewport, receives: 'nothing (the option is outside the viewport)' }
          if (el.contains(hit)) return { viewport, receives: 'self' }
          const owner = known.find((selector) => hit.closest(selector))
          return {
            viewport,
            receives: owner ?? `${hit.tagName.toLowerCase()}[class="${hit.getAttribute('class') ?? ''}"]`,
          }
        })
        const settled = state.viewport === previous
        const before = previous
        previous = state.viewport
        return settled
          ? state.receives
          : `the layout viewport is still resizing (${before || 'first sample'} -> ${state.viewport})`
      },
      { message: `what receives a click at the centre of the ${what} option` },
    )
    .toBe('self')
  // The poll above IS the interception check, so the built-in one is skipped rather than left to
  // fail the click for 10 s. Playwright's own hit test reads `elementFromPoint` at a point it has
  // already mapped into the VISUAL viewport, while the option's rect is in the LAYOUT one — and
  // once the widened panel has pushed those apart (456 CSS px of layout inside 393 px of visual)
  // the two disagree by that ratio: the probe lands ~110 px high, on the trigger's own label, and
  // stays there for every retry. The pointer itself is dispatched correctly, and what it selected
  // is proven downstream, where the saved document has to carry the CANONICAL tag as a chip —
  // the same shape as the force-clicks in saved-filters.spec.ts and bulk.spec.ts.
  await option.click({ force: true })
}

test('a synonym offers and finds its canonical tag, and never crosses a permission boundary', async ({
  page,
  browser,
  cleanup,
}) => {
  const tagName = uniqueTag('syn-tag')
  const synonym = uniqueTag('syn-alt')
  const docTitle = unique('syn-doc')
  const username = unique('synuser').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const email = `${username}@example.com`
  const password = 'Password1e2e'

  // --- Admin: a tag, and a synonym on it ---
  await createTag(page, tagName)
  cleanup.defer('delete the tag', () => deleteTagByNameApi(page.request, tagName))
  await openTagEdit(page, tagName)
  await addSynonym(page, synonym)
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Tag updated')).toBeVisible()

  // It is STORED, not merely on screen: a reload re-reads the tag from the server.
  await page.reload()
  await expect(page.locator('.synonym-chip', { hasText: synonym })).toBeVisible()

  // --- Admin: the document editor's tag field offers the TAG for the typed SYNONYM ---
  await freshGoto(page, '/#/document/add', ROUTE_ROOT.documentEdit)
  await page.locator('#edit-title').fill(docTitle)
  const overlay = await openTagPicker(page)
  await overlay.locator('input.tp-filter-input').fill(synonym)
  // The option is the CANONICAL tag, and it says why it is being offered. Read through the
  // overlay, so it is THIS picker's option rather than any other listbox in the document.
  const viaOption = overlay.getByRole('option', { name: `${tagName} (via ${synonym})` })
  await expect(viaOption).toBeVisible()
  await clickOptionUnobstructed(viaOption, 'synonym-matched tag')
  await page.keyboard.press('Escape')

  // What was assigned is the canonical tag — the chip carries the tag's own name.
  await expect(page.locator('#edit-tags').getByText(tagName, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
  const docId = page.url().split('/document/view/')[1].split(/[/?#]/)[0]
  cleanup.defer('purge the tagged document', () => deleteDocApi(page.request, docId))

  // --- Admin: searching the synonym finds the document the canonical tag is on ---
  await freshGoto(page, '/#/document', ROUTE_ROOT.documentList)
  const search = page.getByPlaceholder('Search')
  await search.fill(`tag:${synonym}`)
  await expect(page.getByText(docTitle, { exact: true })).toBeVisible()
  // ...and so does the canonical name, which the synonym has not replaced.
  await search.fill(`tag:${tagName}`)
  await expect(page.getByText(docTitle, { exact: true })).toBeVisible()
  await search.fill('')

  // --- A second account, with no access to that tag ---
  await gotoRouteReady(page, '/#/settings/users', ROUTE_ROOT.settingsUsers)
  await page.getByRole('button', { name: 'Add user' }).click()
  const userDialog = page.getByRole('dialog', { name: 'Add user' })
  await userDialog.locator('#add-user-name').fill(username)
  await userDialog.locator('#add-user-email').fill(email)
  await userDialog.locator('#add-user-pass').fill(password)
  await userDialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()
  cleanup.defer('delete the second user', () => deleteUserApi(page.request, username))

  const userCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  cleanup.defer('close the second user context', () => userCtx.close())
  const userPage = await userCtx.newPage()
  await login(userPage, username, password)

  // The word resolves to NOTHING for them: a synonym must not be a way to reach a tag — or a
  // document — the account cannot see. (An unresolvable tag term returns no documents at all,
  // which is what makes the absence of the title meaningful rather than merely unfiltered.)
  await freshGoto(userPage, '/#/document', ROUTE_ROOT.documentList)
  await userPage.getByPlaceholder('Search').fill(`tag:${synonym}`)
  await expect(userPage.getByText(docTitle, { exact: true })).toHaveCount(0)
  await expect(userPage.getByText(tagName, { exact: true })).toHaveCount(0)
})

test('a synonym that is already another tag name is refused at save, naming the conflict', async ({
  page,
  cleanup,
}) => {
  const targetTag = uniqueTag('syncol-a')
  const otherTag = uniqueTag('syncol-b')

  await createTag(page, targetTag)
  cleanup.defer('delete the edited tag', () => deleteTagByNameApi(page.request, targetTag))
  await createTag(page, otherTag)
  cleanup.defer('delete the conflicting tag', () => deleteTagByNameApi(page.request, otherTag))

  await openTagEdit(page, targetTag)
  await page.locator('#tag-synonym').fill(otherTag)

  // The form warns BEFORE the save — the reporter asked to see the word is taken while typing.
  await expect(page.locator('.synonym-notice.warn')).toContainText(otherTag)

  // The save is still the authority, and it refuses by NAME rather than with a generic failure.
  await page.locator('.synonym-row').getByRole('button', { name: 'Add', exact: true }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Failed to update tag')).toBeVisible()
  await expect(page.locator('.p-toast')).toContainText(otherTag)

  // Nothing was stored: a reload comes back with no chips at all.
  await page.reload()
  await expect(page.locator('.synonym-chip')).toHaveCount(0)
})
