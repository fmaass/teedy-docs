import { test, expect } from './fixtures'
import {
  unique,
  uniqueTag,
  confirmDanger,
  toggleTagFilter,
  expectTagNodeState,
  openNav,
  deleteTagByNameApi,
  ROUTE_ROOT,
  gotoDocumentList,
  gotoRouteReady,
} from './helpers'

// Tag management (create/edit/delete on the /tag page) and the left-panel tag
// filter — including the tri-state include/exclude toggle and the URL round-trip
// (the P6/F1 fix: navigating away and back must preserve tags + exclude).

test.describe('tag management', () => {
  test('creates, edits, and deletes a tag', async ({ page }) => {
    const name = uniqueTag('e2e-tag')
    const renamed = `${name}-r`

    await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
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

  test('tree filter reveals a tag nested inside a collapsed parent', async ({ page, cleanup }) => {
    // #279: with many hierarchical tags, finding an existing one meant expanding
    // collapsed parents one by one. The tree filter must surface a nested match
    // WITHOUT the user pre-expanding its parent — so this test deliberately leaves
    // the parent collapsed (fresh page state) before filtering.
    const parent = uniqueTag('tfp')
    const child = uniqueTag('tfc')

    await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
    await page.getByPlaceholder('Tag name').fill(parent)
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    await expect(page.locator('.tag-tree').getByText(parent, { exact: true })).toBeVisible()
    // Cleanup runs FIFO, so the child is registered first — it must go before its parent.
    cleanup.defer('delete the nested child tag', () => deleteTagByNameApi(page.request, child))
    cleanup.defer('delete the parent tag', () => deleteTagByNameApi(page.request, parent))

    // Seed the child UNDER the parent via the create card's parent Select.
    await page.getByPlaceholder('Tag name').fill(child)
    await page.getByText('Parent tag (optional)', { exact: true }).click()
    await page.getByRole('option', { name: parent, exact: true }).click()
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    // Creation success signal: onSuccess clears the name input (the transient toast
    // can stack across the two back-to-back creates, so it is not asserted).
    await expect(page.getByPlaceholder('Tag name')).toHaveValue('')

    // Server-side precondition: the child exists and is nested. The UI assertion
    // below is that the child is NOT rendered — which a silently failed create
    // would fake — so its existence is proven against the API first.
    const listRes = await page.request.get('/api/tag/list')
    expect(listRes.ok()).toBe(true)
    const tags = (await listRes.json()).tags as Array<{ id: string; name: string; parent?: string }>
    const createdChild = tags.find((t) => t.name === child)
    expect(createdChild?.parent, 'child must be created nested under the parent').toBeTruthy()

    // The parent renders collapsed (nothing expanded it), so the nested child is
    // not in the tree — the exact pain the filter must solve.
    await expect(page.locator('.tag-tree').getByText(parent, { exact: true })).toBeVisible()
    await expect(page.locator('.tag-tree').getByText(child, { exact: true })).toHaveCount(0)

    // Filter by the child's name: the match must become VISIBLE, i.e. the tree
    // both keeps the ancestor chain and expands it.
    const filterInput = page.locator('.tag-tree').getByPlaceholder('Filter tags')
    await filterInput.fill(child)
    await expect(page.locator('.tag-tree').getByText(child, { exact: true })).toBeVisible()
    await expect(page.locator('.tag-tree').getByText(parent, { exact: true })).toBeVisible()

    // Clearing the filter restores the pre-filter state: the full tree is back and
    // the child is hidden inside the re-collapsed parent again.
    await filterInput.fill('')
    await expect(page.locator('.tag-tree').getByText(parent, { exact: true })).toBeVisible()
    await expect(page.locator('.tag-tree').getByText(child, { exact: true })).toHaveCount(0)
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
    await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
    await page.getByPlaceholder('Tag name').fill(name)
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    // The success signal is the new node in the tree — the transient "Tag created"
    // toast can stack across successive creates (this describe creates two tags
    // back-to-back), so we do not assert on it here.
    await expect(page.locator('.tag-tree').getByText(name, { exact: true })).toBeVisible()
  }

  test('URL round-trips BOTH an included tag and an excluded tag (P6/F1 regression)', async ({ page, cleanup }) => {
    // The regression this guards: an in-URL `tags=` was dropped while other filter
    // dimensions (exclude/mode/search) survived. To catch that specifically we need
    // a state carrying BOTH `tags=` AND `exclude=` at once, so seed TWO tags — one
    // to include, one to exclude — and a document carrying both so they render with
    // counts in the panel.
    const includeTag = uniqueTag('flt-inc')
    const excludeTag = uniqueTag('flt-exc')
    await createTag(page, includeTag)
    cleanup.defer('delete the included tag', () => deleteTagByNameApi(page.request, includeTag))
    await createTag(page, excludeTag)
    cleanup.defer('delete the excluded tag', () => deleteTagByNameApi(page.request, excludeTag))

    const docTitle = unique('flt-doc')
    await gotoRouteReady(page, '/#/document/add', ROUTE_ROOT.documentEdit)
    await page.locator('#edit-title').fill(docTitle)
    await page.locator('#edit-tags').click()
    await page.getByRole('option', { name: includeTag }).click()
    await page.getByRole('option', { name: excludeTag }).click()
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page).toHaveURL(/#\/document\/view\//)

    await gotoDocumentList(page)

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
    await gotoRouteReady(page, '/#/settings/account', ROUTE_ROOT.settingsAccount)
    await expect(page).toHaveURL(/#\/settings\/account/)
    await gotoRouteReady(page, combinedUrl.substring(combinedUrl.indexOf('#')), ROUTE_ROOT.documentList)
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
  })

  test('tri-state include -> exclude -> clear on a single tag', async ({ page, cleanup }) => {
    const tagName = uniqueTag('tri')
    await createTag(page, tagName)
    cleanup.defer('delete the tri-state tag', () => deleteTagByNameApi(page.request, tagName))

    const docTitle = unique('tri-doc')
    await gotoRouteReady(page, '/#/document/add', ROUTE_ROOT.documentEdit)
    await page.locator('#edit-title').fill(docTitle)
    await page.locator('#edit-tags').click()
    await page.getByRole('option', { name: tagName }).click()
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page).toHaveURL(/#\/document\/view\//)

    await gotoDocumentList(page)

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
  })

  test('toggles between Tree and Facets view modes', async ({ page }) => {
    await gotoDocumentList(page)
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
    await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
    await page.getByPlaceholder('Tag name').fill(name)
    await page.getByRole('button', { name: 'Create', exact: true }).click()
    // The success signal is the new node in the tree — the transient "Tag created"
    // toast can stack across successive creates, so we do not assert on it here.
    await expect(page.locator('.tag-tree').getByText(name, { exact: true })).toBeVisible()
  }

  test('document-edit tag MultiSelect: filter box winnows options and a selection renders as a colored chip', async ({ page, cleanup }) => {
    // Two distinctly-named tags so the filter has something to include AND exclude.
    // uniqueTag (not unique) keeps both inside TagResource's 36-character cap by
    // construction — it throws on a prefix that cannot fit instead of 400ing at seed time.
    const keepTag = uniqueTag('cfk')
    const dropTag = uniqueTag('cfd')
    await createTag(page, keepTag)
    cleanup.defer('delete the kept tag', () => deleteTagByNameApi(page.request, keepTag))
    await createTag(page, dropTag)
    cleanup.defer('delete the filtered-out tag', () => deleteTagByNameApi(page.request, dropTag))

    await gotoRouteReady(page, '/#/document/add', ROUTE_ROOT.documentEdit)
    await expect(page.getByRole('heading', { name: 'New document' })).toBeVisible()

    // Open the MultiSelect overlay.
    await page.locator('#edit-tags').click()
    const overlay = page.locator('.p-multiselect-overlay')
    await expect(overlay).toBeVisible()

    // #182: opening the picker now lands the caret in the filter box, harmonizing the
    // edit form with the quick menu and slide-over, which got this in #171. Until the
    // shared TagPicker the edit form had no autoFilterFocus, so typing after opening
    // went nowhere until you clicked the filter as well.
    await expect(overlay.locator('.p-multiselect-filter')).toBeFocused()

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
  })

  test('tag-edit parent Select has a working filter box', async ({ page, cleanup }) => {
    // Need at least two candidate parents so filtering is observable.
    const parentKeep = uniqueTag('cpk')
    const parentDrop = uniqueTag('cpd')
    const child = uniqueTag('cchild')
    await createTag(page, parentKeep)
    cleanup.defer('delete the kept parent tag', () => deleteTagByNameApi(page.request, parentKeep))
    await createTag(page, parentDrop)
    cleanup.defer('delete the filtered-out parent tag', () => deleteTagByNameApi(page.request, parentDrop))
    await createTag(page, child)
    cleanup.defer('delete the child tag', () => deleteTagByNameApi(page.request, child))

    // Open the child's edit page and its parent Select.
    await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
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
  })
})
