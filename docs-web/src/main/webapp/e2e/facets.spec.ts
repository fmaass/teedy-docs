import { test, expect, type APIRequestContext } from '@playwright/test'
import { unique } from './helpers'

// #12: the facet (co-occurrence) tree bounds dense children — a node with more
// than 20 eligible children shows the top 19 by co-occurrence plus one terminal,
// NON-interactive "…and K more" overflow node (MAX_FACET_CHILDREN = 20 total).
//
// Seeding: 22 tags all attached to ONE document → every tag co-occurs with the
// other 21. Each facet root therefore has 21 eligible children > 20, capping at
// 19 real + 1 overflow with hiddenCount = 21 − 19 = 2.
//
// REALNESS: the child counts are asserted inside the EXPANDED root's children
// group (a collapsed tree renders nothing and proves nothing); the overflow node
// must carry no button role and clicking it must not change filter state (URL
// stays clean, no node becomes aria-pressed).

const TAG_COUNT = 22
const EXPECTED_REAL_CHILDREN = 19
const EXPECTED_HIDDEN = TAG_COUNT - 1 - EXPECTED_REAL_CHILDREN // 21 eligible − 19 shown = 2

async function apiCreateTag(request: APIRequestContext, name: string): Promise<string> {
  const res = await request.put('/api/tag', { form: { name, color: '#3399cc' } })
  expect(res.ok(), `create tag ${name}`).toBeTruthy()
  return (await res.json()).id as string
}

// PUT /api/document with REPEATED tags params (Playwright's `form` option takes
// only scalars, so the urlencoded body is built explicitly).
async function apiCreateDocumentWithTags(
  request: APIRequestContext,
  title: string,
  tagIds: string[],
): Promise<string> {
  const body = new URLSearchParams([
    ['title', title],
    ['language', 'eng'],
    ...tagIds.map((id): [string, string] => ['tags', id]),
  ])
  const res = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: body.toString(),
  })
  expect(res.ok(), 'create dense document').toBeTruthy()
  return (await res.json()).id as string
}

test('a dense facet root caps children at 19 + a non-interactive overflow node (#12)', async ({ page, request }) => {
  const prefix = unique('fo')
  const tagNames = Array.from({ length: TAG_COUNT }, (_, i) => `${prefix}-${String(i).padStart(2, '0')}`)
  const tagIds: string[] = []
  let docId: string | undefined

  try {
    // Seed: 22 mutually co-occurring tags on one document.
    for (const name of tagNames) tagIds.push(await apiCreateTag(request, name))
    docId = await apiCreateDocumentWithTags(request, unique('fo-doc'), tagIds)

    await page.goto('/#/document')

    // Switch the sidebar tag panel to Facets mode (SelectButton option).
    await page.getByRole('button', { name: 'Facets', exact: true }).click()

    // Locate OUR root tree item (the node content carries the exact tag name) and
    // expand it via the PrimeVue toggler. Scoping by the unique per-run tag name
    // keeps the spec independent of other specs' leftovers in a full-suite run.
    const rootNode = page
      .locator('.tag-tree-node')
      .filter({ hasText: new RegExp(`^\\s*${tagNames[0]}`) })
      .first()
    await expect(rootNode).toBeVisible()
    const rootItem = rootNode.locator('xpath=ancestor::li[@role="treeitem"][1]')
    await rootItem.locator('[data-pc-section="nodetogglebutton"]').first().click()
    await expect(rootItem).toHaveAttribute('aria-expanded', 'true')

    // The expanded root's children group: exactly 19 interactive children + the
    // overflow node (20 tree items total, MAX_FACET_CHILDREN).
    const group = rootItem.locator('[role="group"]')
    await expect(group.locator('li[role="treeitem"]')).toHaveCount(EXPECTED_REAL_CHILDREN + 1)
    await expect(group.locator('.tag-tree-node[role="button"]')).toHaveCount(EXPECTED_REAL_CHILDREN)

    // The overflow node: present once, truthful count, NOT a button.
    const overflow = group.locator('.tag-overflow')
    await expect(overflow).toHaveCount(1)
    await expect(overflow).toContainText(`and ${EXPECTED_HIDDEN} more`)
    await expect(group.locator('.tag-overflow[role="button"]')).toHaveCount(0)

    // Clicking the overflow node must NOT change filter state: the URL gains no
    // tags param and no node becomes selected (aria-pressed).
    const urlBefore = page.url()
    await overflow.click()
    await expect(page.locator('[aria-pressed="true"].tag-tree-node')).toHaveCount(0)
    expect(page.url()).toBe(urlBefore)
    expect(page.url()).not.toContain('tags=')
  } finally {
    // Cleanup seeds: the document first, then the tags.
    if (docId) await request.delete(`/api/document/${docId}`).catch(() => {})
    for (const id of tagIds) await request.delete(`/api/tag/${id}`).catch(() => {})
  }
})
