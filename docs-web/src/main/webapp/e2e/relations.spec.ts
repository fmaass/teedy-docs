import { test, expect, type Page } from './fixtures'
import { unique, createDocument, confirmDanger, deleteDocApi, ROUTE_ROOT, gotoRouteReady } from './helpers'

// Document relations end to end via the "Related documents" section on the
// document Content tab (DocumentViewContent):
//   1. Create documents A and B.
//   2. From A's view, search B in the relation AutoComplete and add it — A links to B.
//   3. Assert the relation renders on BOTH views after a fresh reload: A shows B under
//      "Links to" with a remove control; B shows A under "Linked from" WITHOUT a remove
//      control (the incoming side is read-only — it must be removed from its source).
//   4. Remove the relation from A; after reload it is gone from BOTH views — exercising
//      the last-relation removal (relations_reset) path.
// Every title is timestamped and both documents are removed in teardown so reruns never
// collide with leftovers.

test('add a relation A→B, see it on both views, then remove the last relation', async ({ page, cleanup }) => {
  const titleA = unique('rel-A')
  const titleB = unique('rel-B')
  const idA = (await createDocument(page, titleA)).id
  cleanup.defer('purge document A', () => deleteDocApi(page.request, idA))
  const idB = (await createDocument(page, titleB)).id
  cleanup.defer('purge document B', () => deleteDocApi(page.request, idB))

  // --- Add the relation A → B from A's Content tab ---
  await gotoRouteReady(page, `/#/document/view/${idA}/content`, ROUTE_ROOT.documentContent)
  await expect(page.getByRole('heading', { name: 'Related documents' })).toBeVisible()
  const addRow = page.locator('.relation-add')
  await addRow.locator('input').first().fill(titleB)
  await page.getByRole('option', { name: new RegExp(titleB) }).click()
  // Scope the toast assertion to the alert role: the add and the later removal each
  // fire an identical "Relations updated" toast, and a fast run can stack them. Wait
  // for THIS toast to appear THEN expire before the removal step so the post-removal
  // assertion below matches only the new toast, never the residual stacked one.
  const relationsToast = page.getByRole('alert').filter({ hasText: 'Relations updated' })
  await addRow.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(relationsToast).toBeVisible()
  await expect(relationsToast).toBeHidden({ timeout: 3_000 })

  // --- In-app propagation (NO reload): follow the new outgoing link straight to B ---
  // This guards the cross-document cache invalidation: B's detail query must not serve
  // a stale (pre-mutation) relations list on in-app navigation. A full page.goto reload
  // would mask that defect by resetting the SPA query cache.
  await page
    .locator('.relation-group', { hasText: 'Links to' })
    .locator('.relation-row', { hasText: titleB })
    .locator('a.relation-link')
    .click()
  await expect(page).toHaveURL(new RegExp(idB))
  const linkedFromInApp = page.locator('.relation-group', { hasText: 'Linked from' })
  await expect(linkedFromInApp).toBeVisible()
  await expect(linkedFromInApp.locator('.relation-row', { hasText: titleA })).toBeVisible()

  // --- Assert on A's view after a fresh reload: B under "Links to", removable ---
  await gotoRouteReady(page, `/#/document/view/${idA}/content`, ROUTE_ROOT.documentContent)
  const linksToGroup = page.locator('.relation-group', { hasText: 'Links to' })
  await expect(linksToGroup).toBeVisible()
  const outgoingRow = linksToGroup.locator('.relation-row', { hasText: titleB })
  await expect(outgoingRow).toBeVisible()
  await expect(outgoingRow.getByRole('button', { name: 'Remove relation' })).toBeVisible()

  // --- Assert on B's view after a fresh reload: A under "Linked from", NO remove control ---
  await gotoRouteReady(page, `/#/document/view/${idB}/content`, ROUTE_ROOT.documentContent)
  const linkedFromGroup = page.locator('.relation-group', { hasText: 'Linked from' })
  await expect(linkedFromGroup).toBeVisible()
  const incomingRow = linkedFromGroup.locator('.relation-row', { hasText: titleA })
  await expect(incomingRow).toBeVisible()
  // The incoming relation is read-only: no remove control on B's side.
  await expect(incomingRow.getByRole('button', { name: 'Remove relation' })).toHaveCount(0)

  // --- Remove the relation from A (the last one) ---
  await gotoRouteReady(page, `/#/document/view/${idA}/content`, ROUTE_ROOT.documentContent)
  await page
    .locator('.relation-group', { hasText: 'Links to' })
    .locator('.relation-row', { hasText: titleB })
    .getByRole('button', { name: 'Remove relation' })
    .click()
  await confirmDanger(page)
  await expect(relationsToast).toBeVisible()

  // --- After a fresh reload it is gone from BOTH views ---
  await gotoRouteReady(page, `/#/document/view/${idA}/content`, ROUTE_ROOT.documentContent)
  await expect(page.getByRole('heading', { name: 'Related documents' })).toBeVisible()
  await expect(page.locator('.relation-group', { hasText: 'Links to' })).toHaveCount(0)

  await gotoRouteReady(page, `/#/document/view/${idB}/content`, ROUTE_ROOT.documentContent)
  await expect(page.getByRole('heading', { name: 'Related documents' })).toBeVisible()
  await expect(page.locator('.relation-group', { hasText: 'Linked from' })).toHaveCount(0)
})

// #191 — reversing a relation's direction from either group. The endpoint takes the pair in its
// CURRENT orientation, so the two groups exercise OPPOSITE argument orders; running both here is
// what proves the outgoing and incoming buttons are not wired the same way round. The observable
// contract is that the two groups exchange membership on BOTH documents, and that ownership of the
// link (the remove control) moves with it.
test('swap a relation direction from both groups — the groups exchange membership', async ({ page, cleanup }) => {
  const titleA = unique('swap-A')
  const titleB = unique('swap-B')
  const idA = (await createDocument(page, titleA)).id
  cleanup.defer('purge document A', () => deleteDocApi(page.request, idA))
  const idB = (await createDocument(page, titleB)).id
  cleanup.defer('purge document B', () => deleteDocApi(page.request, idB))

  const relationsToast = page.getByRole('alert').filter({ hasText: 'Relations updated' })
  const swapToast = page.getByRole('alert').filter({ hasText: 'Relation direction reversed' })

  // --- Seed A → B from A's Content tab ---
  await gotoRouteReady(page, `/#/document/view/${idA}/content`, ROUTE_ROOT.documentContent)
  await expect(page.getByRole('heading', { name: 'Related documents' })).toBeVisible()
  const addRow = page.locator('.relation-add')
  await addRow.locator('input').first().fill(titleB)
  await page.getByRole('option', { name: new RegExp(titleB) }).click()
  await addRow.getByRole('button', { name: 'Add', exact: true }).click()
  // Let this toast expire before the swap so the swap's own toast is unambiguous.
  await expect(relationsToast).toBeVisible()
  await expect(relationsToast).toBeHidden({ timeout: 3_000 })

  // --- Swap from the OUTGOING group: B leaves "Links to" and appears under "Linked from" ---
  await page
    .locator('.relation-group', { hasText: 'Links to' })
    .locator('.relation-row', { hasText: titleB })
    .getByRole('button', { name: 'Reverse direction' })
    .click()
  await expect(swapToast).toBeVisible()

  // In-app propagation (NO reload): A's own view must re-render from the invalidated query.
  await expect(page.locator('.relation-group', { hasText: 'Links to' })).toHaveCount(0)
  const linkedFromA = page.locator('.relation-group', { hasText: 'Linked from' })
  await expect(linkedFromA.locator('.relation-row', { hasText: titleB })).toBeVisible()
  await expect(swapToast).toBeHidden({ timeout: 3_000 })

  // --- B now OWNS the link: it appears under "Links to" there, with a remove control ---
  await gotoRouteReady(page, `/#/document/view/${idB}/content`, ROUTE_ROOT.documentContent)
  const linksToB = page.locator('.relation-group', { hasText: 'Links to' })
  await expect(linksToB).toBeVisible()
  await expect(linksToB.locator('.relation-row', { hasText: titleA })).toBeVisible()
  await expect(
    linksToB.locator('.relation-row', { hasText: titleA }).getByRole('button', { name: 'Remove relation' }),
  ).toBeVisible()
  await expect(page.locator('.relation-group', { hasText: 'Linked from' })).toHaveCount(0)

  // --- Swap BACK from the INCOMING group on A: the reverse argument order must work too ---
  await gotoRouteReady(page, `/#/document/view/${idA}/content`, ROUTE_ROOT.documentContent)
  await page
    .locator('.relation-group', { hasText: 'Linked from' })
    .locator('.relation-row', { hasText: titleB })
    .getByRole('button', { name: 'Reverse direction' })
    .click()
  await expect(swapToast).toBeVisible()
  await expect(page.locator('.relation-group', { hasText: 'Linked from' })).toHaveCount(0)
  await expect(
    page.locator('.relation-group', { hasText: 'Links to' }).locator('.relation-row', { hasText: titleB }),
  ).toBeVisible()

  // --- And it stuck server-side: B is back to the read-only incoming side after a reload ---
  await gotoRouteReady(page, `/#/document/view/${idB}/content`, ROUTE_ROOT.documentContent)
  await expect(page.getByRole('heading', { name: 'Related documents' })).toBeVisible()
  await expect(page.locator('.relation-group', { hasText: 'Links to' })).toHaveCount(0)
  const incomingB = page.locator('.relation-group', { hasText: 'Linked from' }).locator('.relation-row', { hasText: titleA })
  await expect(incomingB).toBeVisible()
  await expect(incomingB.getByRole('button', { name: 'Remove relation' })).toHaveCount(0)
})

// #296 (part 2) — ordering both linked-documents lists by the LINKED document's own creation date.
// That date travels ON the relation payload (RelationDao joins the other document's
// DOC_CREATEDATE_D), so the control re-orders without one request per link. The fixture makes the
// two candidate orders DISAGREE: the document created FIRST is titled to sort LAST alphabetically,
// and the server returns relations ordered by title — so the asserted order can come only from the
// dates, never from the title comparator or from leaving the server order alone.
test('order the linked documents by the linked document’s own creation date', async ({ page, cleanup }) => {
  const titleOlder = unique('zz-relsort-older')
  const titleNewer = unique('aa-relsort-newer')
  const idOlder = (await createDocument(page, titleOlder)).id
  cleanup.defer('purge the older linked document', () => deleteDocApi(page.request, idOlder))
  const idNewer = (await createDocument(page, titleNewer)).id
  cleanup.defer('purge the newer linked document', () => deleteDocApi(page.request, idNewer))
  const idSource = (await createDocument(page, unique('relsort-source'))).id
  cleanup.defer('purge the source document', () => deleteDocApi(page.request, idSource))

  // --- Link the source document to both, oldest first ---
  await gotoRouteReady(page, `/#/document/view/${idSource}/content`, ROUTE_ROOT.documentContent)
  await expect(page.getByRole('heading', { name: 'Related documents' })).toBeVisible()
  const addRow = page.locator('.relation-add')
  const relationsToast = page.getByRole('alert').filter({ hasText: 'Relations updated' })
  for (const title of [titleOlder, titleNewer]) {
    await addRow.locator('input').first().fill(title)
    await page.getByRole('option', { name: new RegExp(title) }).click()
    await addRow.getByRole('button', { name: 'Add', exact: true }).click()
    // Let each toast expire before the next add so the two identical toasts never stack.
    await expect(relationsToast).toBeVisible()
    await expect(relationsToast).toBeHidden({ timeout: 3_000 })
  }

  const linkTitles = page.locator('.relation-group', { hasText: 'Links to' }).locator('a.relation-link')
  // Untouched server order: by title, so the NEWER document (titled "aa-…") is first.
  await expect(linkTitles).toHaveText([new RegExp(titleNewer), new RegExp(titleOlder)])

  // Creation date, oldest first: the exact reverse.
  await page.locator('.relation-sort-select').click()
  await page.getByRole('option', { name: 'Created (oldest first)' }).click()
  await expect(linkTitles).toHaveText([new RegExp(titleOlder), new RegExp(titleNewer)])
})

// #309 — the "add an outgoing relation" row itself, reported against 3.8.8. Two complaints:
//
//  1. The field is too narrow to show a full document title. Root cause: the AutoComplete carried
//     no `fluid`, and PrimeVue stretches `.p-autocomplete-input` to its wrapper only for a
//     component that renders a dropdown BUTTON (@primeuix/styles/autocomplete). This one has no
//     `dropdown`, so the input kept the intrinsic width of a bare `<input>` while
//     `.relation-add-autocomplete { flex: 1 }` grew the wrapper around it — the same mechanism as
//     the ACL search boxes in acl-search-width.spec.ts (#301).
//  2. The field silently accepts the FULL search syntax: `completeRelationSearch` calls
//     `GET /document/list`, i.e. `DocumentSearchCriteriaUtil.parseSearchQuery` — the same parser
//     the main search bar documents in its help popover. Nothing on the row said so.
//
// Both are invisible to the functional tests above (a clipped field still accepts a pick) and to
// the visual gate (no baseline captures the document Content tab), so the width is MEASURED here
// and the help affordance is opened from the keyboard.

interface FieldFit {
  value: string
  // The value's rendered width, measured with a canvas primed from the input's OWN computed
  // font — not a character count, which no proportional font honours.
  textWidth: number
  // The input's CONTENT box: clientWidth is the padding box, so the horizontal padding comes off.
  contentWidth: number
  // scrollWidth exceeds clientWidth exactly when the text is scrolled out of view, i.e. clipped.
  scrollWidth: number
  clientWidth: number
  inputWidth: number
  wrapperWidth: number
  rowWidth: number
  viewportWidth: number
}

async function measureRelationField(page: Page): Promise<FieldFit> {
  return page.evaluate(() => {
    const wrapper = document.querySelector<HTMLElement>('.relation-add .p-autocomplete')!
    const input = wrapper.querySelector<HTMLInputElement>('input')!
    const row = document.querySelector<HTMLElement>('.relation-add')!
    const style = getComputedStyle(input)
    const ctx = document.createElement('canvas').getContext('2d')!
    ctx.font = `${style.fontStyle} ${style.fontVariant} ${style.fontWeight} ${style.fontSize} / ${style.lineHeight} ${style.fontFamily}`
    const round = (n: number) => Math.round(n * 10) / 10
    return {
      value: input.value,
      textWidth: round(ctx.measureText(input.value).width),
      contentWidth: round(
        input.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight),
      ),
      scrollWidth: input.scrollWidth,
      clientWidth: input.clientWidth,
      inputWidth: round(input.getBoundingClientRect().width),
      wrapperWidth: round(wrapper.getBoundingClientRect().width),
      rowWidth: round(row.getBoundingClientRect().width),
      viewportWidth: window.innerWidth,
    }
  })
}

test('the relation search field fills its row and shows a long title whole (#309)', async ({
  page,
  isMobile,
  cleanup,
}) => {
  // 60+ characters, under the backend's 100-char title cap (DocumentResource validateLength
  // title 1..100) — long enough that the pre-fix ~233px input could never display it.
  const longTitle = unique('A-Long-Related-Document-Title-That-Overruns-The-Relation-Field')
  expect(longTitle.length, 'the probe title is at least 60 characters').toBeGreaterThanOrEqual(60)
  expect(longTitle.length, 'title within the backend 100-char cap').toBeLessThanOrEqual(100)

  const idTarget = (await createDocument(page, longTitle)).id
  cleanup.defer('purge the long-titled relation target', () => deleteDocApi(page.request, idTarget))
  const idSource = (await createDocument(page, unique('relwidth-source'))).id
  cleanup.defer('purge the relation source document', () => deleteDocApi(page.request, idSource))

  await gotoRouteReady(page, `/#/document/view/${idSource}/content`, ROUTE_ROOT.documentContent)
  const addRow = page.locator('.relation-add')
  await expect(addRow).toBeVisible()

  // Pick the long-titled document, so the field holds a real value rather than a placeholder.
  // Typed whole rather than as a prefix: the query goes to the document search parser, which
  // tokenises on the hyphens, so a cut mid-token would be a search-behaviour experiment inside a
  // layout test. The measurement below is taken on the SELECTED value either way.
  await addRow.locator('input').first().fill(longTitle)
  await page.getByRole('option', { name: new RegExp(longTitle) }).click()
  await expect(addRow.locator('input').first()).toHaveValue(longTitle)

  const fit = await measureRelationField(page)

  // THE defect, asserted at BOTH viewports: the input must fill its AutoComplete wrapper. Before
  // `fluid` it was a constant intrinsic width no matter how wide the row grew, so widening the
  // container could never reach the field the title is painted into.
  expect(
    fit.inputWidth,
    `the relation input fills its wrapper (input ${fit.inputWidth}px in a ${fit.wrapperWidth}px ` +
      `wrapper, row ${fit.rowWidth}px at a ${fit.viewportWidth}px viewport)`,
  ).toBeGreaterThanOrEqual(fit.wrapperWidth - 2)

  if (isMobile) {
    // A 60-character title cannot fit a 393px phone whatever the CSS does, so "not clipped" is
    // not a claim this viewport can make. Filling the row — asserted above — is the whole of the
    // available fix here, and asserting more would be asserting the viewport.
    return
  }

  expect(
    fit.contentWidth,
    `the picked title "${fit.value}" renders ${fit.textWidth}px wide in the input's own font but ` +
      `the field offers only ${fit.contentWidth}px of content box`,
  ).toBeGreaterThanOrEqual(fit.textWidth)
  expect(
    fit.scrollWidth,
    `the field scrolls (${fit.scrollWidth}px of content in a ${fit.clientWidth}px box), so part of ` +
      'the title is out of view',
  ).toBeLessThanOrEqual(fit.clientWidth)
})

test('the relation search field tells the user it takes search operators (#309)', async ({
  page,
  cleanup,
}) => {
  const idSource = (await createDocument(page, unique('relhelp-source'))).id
  cleanup.defer('purge the relation help source document', () => deleteDocApi(page.request, idSource))

  await gotoRouteReady(page, `/#/document/view/${idSource}/content`, ROUTE_ROOT.documentContent)
  const addRow = page.locator('.relation-add')
  await expect(addRow).toBeVisible()

  // Same accessible name as the search bar's help button — the copy is one shared component.
  const help = addRow.getByRole('button', { name: 'Search help' })
  await expect(help).toBeVisible()

  // Keyboard-reachable: focused and opened with Enter, never a pointer-only affordance.
  await help.focus()
  await expect(help).toBeFocused()
  await help.press('Enter')

  // The popover is teleported to the body, so it is addressed globally by its own class.
  const panel = page.locator('.p-popover.relation-add-help')
  await expect(panel).toBeVisible()
  await expect(panel.getByRole('heading', { name: 'Search help' })).toBeVisible()
  // The operator that motivated the report, plus one more, proving the real operator table is
  // rendered and not a one-line hint.
  await expect(panel.getByText('tag:invoice', { exact: true })).toBeVisible()
  await expect(panel.getByText('by:alice', { exact: true })).toBeVisible()
})
