import { test, expect } from './fixtures'
import { unique, confirmDanger, toggleTagFilter, expectTagNodeState, openNav } from './helpers'

// Tag management (create/edit/delete on the /tag page) and the left-panel tag
// filter — including the tri-state include/exclude toggle and the URL round-trip
// (the P6/F1 fix: navigating away and back must preserve tags + exclude).

test.describe('tag management', () => {
  test('creates, edits, and deletes a tag', async ({ page }) => {
    const name = unique('e2e-tag')
    const renamed = `${name}-r`

    await page.goto('/#/tag')
    await expect(page.getByRole('heading', { name: 'Tags' })).toBeVisible()

    // Create: the create card's InputText carries the tag-name placeholder.
    await page.getByPlaceholder('Tag name').fill(name)
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(page.getByText('Tag created')).toBeVisible()

    // The new tag appears in the tree; open its edit page by clicking it.
    const node = page.locator('.tag-tree').getByText(name, { exact: true })
    await expect(node).toBeVisible()
    await node.click()

    // Edit page: rename and save.
    await expect(page).toHaveURL(/#\/tag\//)
    const nameInput = page.locator('#tag-name')
    await expect(nameInput).toHaveValue(name)
    await nameInput.fill(renamed)
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('Tag updated')).toBeVisible()

    // Delete: the danger button opens the confirm dialog; accepting routes back
    // to the tag list and the tag disappears.
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    await confirmDanger(page)
    await expect(page).toHaveURL(/#\/tag$/)
    await expect(page.locator('.tag-tree').getByText(renamed, { exact: true })).toHaveCount(0)
  })
})

test.describe('tag filter panel', () => {
  // The left panel only renders in the documents context on a wide viewport
  // (isMobile gates on max-width:1024px). The default Desktop Chrome viewport is
  // 1280px wide, so the desktop aside is present.

  // Small helper to read a query param out of a hash-router URL
  // (#/document?tags=…&exclude=…): parse the part after the first '?'.
  function hashQuery(url: string): URLSearchParams {
    const q = url.slice(url.indexOf('?') + 1)
    return new URLSearchParams(q)
  }

  async function createTag(page: import('@playwright/test').Page, name: string) {
    await page.goto('/#/tag')
    await page.getByPlaceholder('Tag name').fill(name)
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    // The success signal is the new node in the tree — the transient "Tag created"
    // toast can stack across successive creates (this describe creates two tags
    // back-to-back), so we do not assert on it here.
    await expect(page.locator('.tag-tree').getByText(name, { exact: true })).toBeVisible()
  }

  async function deleteTag(page: import('@playwright/test').Page, name: string) {
    await page.goto('/#/tag')
    await page.locator('.tag-tree').getByText(name, { exact: true }).click()
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    await confirmDanger(page)
    await expect(page).toHaveURL(/#\/tag$/)
  }

  test('URL round-trips BOTH an included tag and an excluded tag (P6/F1 regression)', async ({ page }) => {
    // The regression this guards: an in-URL `tags=` was dropped while other filter
    // dimensions (exclude/mode/search) survived. To catch that specifically we need
    // a state carrying BOTH `tags=` AND `exclude=` at once, so seed TWO tags — one
    // to include, one to exclude — and a document carrying both so they render with
    // counts in the panel.
    const includeTag = unique('flt-inc')
    const excludeTag = unique('flt-exc')
    await createTag(page, includeTag)
    await createTag(page, excludeTag)

    const docTitle = unique('flt-doc')
    await page.goto('/#/document/add')
    await page.locator('#edit-title').fill(docTitle)
    await page.locator('#edit-tags').click()
    await page.getByRole('option', { name: includeTag }).click()
    await page.getByRole('option', { name: excludeTag }).click()
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page).toHaveURL(/#\/document\/view\//)

    await page.goto('/#/document')

    // Drive the tag tree via the viewport-aware helpers: on desktop the panel stays
    // open; on mobile each select CLOSES the Drawer, so toggleTagFilter re-opens it
    // per click and expectTagNodeState re-opens it to read a node's state. The filter
    // STATE MACHINE (URL + node aria/class) is asserted identically at both sizes.

    // INCLUDE the first tag (one click -> selected).
    await toggleTagFilter(page, new RegExp(includeTag))
    await expectTagNodeState(page, new RegExp(includeTag), { pressed: 'true' })
    // EXCLUDE the second tag (two clicks: select then toggle to excluded).
    await toggleTagFilter(page, new RegExp(excludeTag))
    await expectTagNodeState(page, new RegExp(excludeTag), { pressed: 'true' })
    await toggleTagFilter(page, new RegExp(excludeTag))
    await expectTagNodeState(page, new RegExp(excludeTag), { excluded: true })

    // The URL must now carry BOTH dimensions.
    await expect(page).toHaveURL(/[?&]tags=/)
    await expect(page).toHaveURL(/[?&]exclude=/)
    const combinedUrl = page.url()
    const params = hashQuery(combinedUrl)
    const includedId = params.get('tags')
    const excludedId = params.get('exclude')
    expect(includedId, 'URL must carry tags=').toBeTruthy()
    expect(excludedId, 'URL must carry exclude=').toBeTruthy()
    expect(includedId).not.toEqual(excludedId)

    // --- Round-trip: navigate AWAY, then back to the combined URL (deep-link /
    // back-button). BOTH the include selection AND the exclusion must re-hydrate.
    // This fails if EITHER dimension is dropped — the exact regression guarded. ---
    await page.goto('/#/settings/account')
    await expect(page).toHaveURL(/#\/settings\/account/)
    await page.goto(combinedUrl.substring(combinedUrl.indexOf('#')))
    await expect(page).toHaveURL(/#\/document/)

    // Included tag: back to a selected (aria-pressed) chip in the panel.
    await expectTagNodeState(page, new RegExp(includeTag), { pressed: 'true' })
    // Excluded tag: back to the struck-through excluded state.
    await expectTagNodeState(page, new RegExp(excludeTag), { excluded: true })

    // And the URL the store re-serialized after hydration still carries BOTH ids
    // (a dropped `tags=` would leave only exclude= here).
    await expect(page).toHaveURL(/[?&]tags=/)
    await expect(page).toHaveURL(/[?&]exclude=/)
    const afterParams = hashQuery(page.url())
    expect(afterParams.get('tags')).toEqual(includedId)
    expect(afterParams.get('exclude')).toEqual(excludedId)

    // Cleanup.
    await deleteTag(page, includeTag)
    await deleteTag(page, excludeTag)
  })

  test('tri-state include -> exclude -> clear on a single tag', async ({ page }) => {
    const tagName = unique('tri')
    await createTag(page, tagName)

    const docTitle = unique('tri-doc')
    await page.goto('/#/document/add')
    await page.locator('#edit-title').fill(docTitle)
    await page.locator('#edit-tags').click()
    await page.getByRole('option', { name: tagName }).click()
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page).toHaveURL(/#\/document\/view\//)

    await page.goto('/#/document')

    // Tri-state via the viewport-aware helper (each click re-opens the Drawer on
    // mobile). The URL is the primary, viewport-agnostic assertion; the node's
    // aria/class is read back via expectTagNodeState (which re-opens the Drawer on mobile and polls).

    // INCLUDE -> tags= in URL, aria-pressed.
    await toggleTagFilter(page, new RegExp(tagName))
    await expect(page).toHaveURL(/[?&]tags=/)
    await expectTagNodeState(page, new RegExp(tagName), { pressed: 'true' })

    // EXCLUDE -> tags= drops, exclude= appears, struck through.
    await toggleTagFilter(page, new RegExp(tagName))
    await expect(page).toHaveURL(/[?&]exclude=/)
    await expect(page).not.toHaveURL(/[?&]tags=/)
    await expectTagNodeState(page, new RegExp(tagName), { excluded: true })

    // CLEAR -> both drop.
    await toggleTagFilter(page, new RegExp(tagName))
    await expect(page).not.toHaveURL(/[?&]exclude=/)
    await expect(page).not.toHaveURL(/[?&]tags=/)
    await expectTagNodeState(page, new RegExp(tagName), { pressed: 'false' })

    await deleteTag(page, tagName)
  })

  test('toggles between Tree and Facets view modes', async ({ page }) => {
    await page.goto('/#/document')
    // The view-mode SelectButton lives in the tag panel — desktop side panel OR the
    // mobile Drawer (openNav opens it on mobile). The Tree/Facets toggle does NOT
    // close the Drawer (only a tag SELECT does), so both clicks run in one open pass.
    const panel = await openNav(page)

    const treeBtn = panel.getByRole('button', { name: 'Tree' })
    const facetsBtn = panel.getByRole('button', { name: 'Facets' })
    await expect(treeBtn).toBeVisible()
    await expect(facetsBtn).toBeVisible()

    await facetsBtn.click()
    await expect(facetsBtn).toHaveAttribute('aria-pressed', 'true')

    await treeBtn.click()
    await expect(treeBtn).toHaveAttribute('aria-pressed', 'true')
  })
})

// --- Behavior C (filterable tag pickers with colored chips, #14/#23) ---------
// The document-edit tag MultiSelect gained a filter box + a colored-chip #chip
// slot (TagBadge), and the tag-edit parent Select gained a filter box. Both were
// unusable past a few dozen tags before, and selected doc tags rendered as plain
// uncolored labels.
//
// REALNESS: the filter is asserted to actually WINNOW the option list (a matching
// option stays, a non-matching one is removed) — a decorative-but-dead filter box
// would leave both visible and fail. The colored chip is asserted to be the
// TagBadge span carrying the tag's real background color (not a plain label) —
// reverting the #chip slot would drop .teedy-tag and fail.
test.describe('tag pickers (behavior C)', () => {
  async function createTag(page: import('@playwright/test').Page, name: string) {
    await page.goto('/#/tag')
    await page.getByPlaceholder('Tag name').fill(name)
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    // The success signal is the new node in the tree — the transient "Tag created"
    // toast can stack across successive creates, so we do not assert on it here.
    await expect(page.locator('.tag-tree').getByText(name, { exact: true })).toBeVisible()
  }

  async function deleteTag(page: import('@playwright/test').Page, name: string) {
    await page.goto('/#/tag')
    await page.locator('.tag-tree').getByText(name, { exact: true }).click()
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    await confirmDanger(page)
    await expect(page).toHaveURL(/#\/tag$/)
  }

  test('document-edit tag MultiSelect: filter box winnows options and a selection renders as a colored chip', async ({ page }) => {
    // Two distinctly-named tags so the filter has something to include AND exclude.
    const keepTag = unique('cfilterkeep')
    const dropTag = unique('cfilterdrop')
    await createTag(page, keepTag)
    await createTag(page, dropTag)

    try {
      await page.goto('/#/document/add')
      await expect(page.getByRole('heading', { name: 'New document' })).toBeVisible()

      // Open the MultiSelect overlay.
      await page.locator('#edit-tags').click()
      const overlay = page.locator('.p-multiselect-overlay')
      await expect(overlay).toBeVisible()

      // The filter box exists (the #14/#23 addition).
      const filterInput = overlay.locator('input.p-multiselect-filter, .p-multiselect-filter input, input[role=searchbox]').first()
      await expect(filterInput).toBeVisible()

      // Type a fragment unique to keepTag: the matching option stays, the other is
      // removed — proving the filter actually filters (not a dead box).
      await filterInput.fill(keepTag)
      await expect(page.getByRole('option', { name: keepTag })).toBeVisible()
      await expect(page.getByRole('option', { name: dropTag })).toHaveCount(0)

      // Select the surviving option, then close the overlay.
      await page.getByRole('option', { name: keepTag }).click()
      await page.keyboard.press('Escape')

      // The selected tag renders as a COLORED TagBadge chip (span.teedy-tag with an
      // inline background-color), not a plain label.
      const chip = page.locator('.tag-multiselect .teedy-tag', { hasText: keepTag })
      await expect(chip).toBeVisible()
      const bg = await chip.evaluate((el) => getComputedStyle(el).backgroundColor)
      // A real colored chip has a non-transparent, non-default background.
      expect(bg).toMatch(/^rgba?\(/)
      expect(bg).not.toBe('rgba(0, 0, 0, 0)')
    } finally {
      await deleteTag(page, keepTag)
      await deleteTag(page, dropTag)
    }
  })

  test('tag-edit parent Select has a working filter box', async ({ page }) => {
    // Need at least two candidate parents so filtering is observable.
    const parentKeep = unique('cparentkeep')
    const parentDrop = unique('cparentdrop')
    const child = unique('cchild')
    await createTag(page, parentKeep)
    await createTag(page, parentDrop)
    await createTag(page, child)

    try {
      // Open the child's edit page and its parent Select.
      await page.goto('/#/tag')
      await page.locator('.tag-tree').getByText(child, { exact: true }).click()
      await expect(page).toHaveURL(/#\/tag\//)

      await page.locator('#tag-parent').click()
      const overlay = page.locator('.p-select-overlay')
      await expect(overlay).toBeVisible()

      const filterInput = overlay.locator('input.p-select-filter, .p-select-filter input, input[role=searchbox]').first()
      await expect(filterInput).toBeVisible()

      // Filter to parentKeep: it stays, parentDrop is removed.
      await filterInput.fill(parentKeep)
      await expect(page.getByRole('option', { name: parentKeep })).toBeVisible()
      await expect(page.getByRole('option', { name: parentDrop })).toHaveCount(0)
      await page.keyboard.press('Escape')
    } finally {
      await deleteTag(page, parentKeep)
      await deleteTag(page, parentDrop)
      await deleteTag(page, child)
    }
  })
})
