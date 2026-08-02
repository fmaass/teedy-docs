import { test, expect } from './fixtures'
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
