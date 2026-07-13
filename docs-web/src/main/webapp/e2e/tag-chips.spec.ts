import { test, expect, type APIRequestContext } from './fixtures'
import { unique, tagTreePanel } from './helpers'

// #34: tag chips on a document view are clickable filter actions. Clicking a tag
// chip in the document header applies a positive filter for that tag and lands on
// the filtered documents list (#/document?tags=<id>) — the same navigate-to-list
// behavior the sidebar filter has, reached from the reading context.
//
// Fixture: TWO documents — one carrying the tag, one deliberately without it.
// After the chip click, the filtered list must show the tagged document and must
// NOT show the untagged one. The untagged row can only disappear once the list has
// re-queried with the tag filter applied, so its absence is the signal that the
// filter took effect on the data, not merely that the URL changed. The sidebar
// filter chip for the tag must also read aria-pressed=true.

async function apiCreateTag(request: APIRequestContext, name: string): Promise<string> {
  const res = await request.put('/api/tag', { form: { name, color: '#2aabd2' } })
  expect(res.ok(), `create tag ${name}`).toBeTruthy()
  return (await res.json()).id as string
}

async function apiCreateDocument(
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
  expect(res.ok(), `create document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

test('clicking a tag chip in the document view filters the list by that tag (#34)', async ({ page, request }) => {
  const tagName = unique('chip-tag')
  const taggedTitle = unique('chip-tagged')
  const untaggedTitle = unique('chip-untagged')

  let tagId: string | undefined
  let taggedId: string | undefined
  let untaggedId: string | undefined

  try {
    tagId = await apiCreateTag(request, tagName)
    // One document carries the tag; the other deliberately does not, so the
    // filtered list is distinguishable from the unfiltered one.
    taggedId = await apiCreateDocument(request, taggedTitle, [tagId])
    untaggedId = await apiCreateDocument(request, untaggedTitle, [])

    // Open the tagged document's full view. Its header renders the tag as a chip.
    await page.goto(`/#/document/view/${taggedId}`)
    await expect(page.getByRole('heading', { name: taggedTitle })).toBeVisible()

    // The header tag chip is a real filter button (aria-label "Filter by tag …").
    const chip = page
      .locator('.doc-header-tags')
      .getByRole('button', { name: new RegExp(tagName) })
    await expect(chip).toBeVisible()

    // Click it → navigate to the filtered documents list.
    await chip.click()
    await expect(page).toHaveURL(/#\/document\?/)
    await expect(page).toHaveURL(new RegExp(`[?&]tags=${tagId}`))

    // The sidebar filter chip for this tag reads as active (aria-pressed). The tag
    // tree lives in the desktop side panel OR the mobile Drawer — tagTreePanel()
    // opens the Drawer on mobile and resolves to the live container either way.
    const tree = await tagTreePanel(page)
    await expect(
      tree.getByRole('button', { name: new RegExp(tagName) }),
    ).toHaveAttribute('aria-pressed', 'true')

    // The filtered list shows the tagged document and no longer shows the
    // untagged one — the untagged row only disappears after the list re-queries
    // with the tag filter, so its absence shows the filter reached the data,
    // not merely the URL.
    await expect(page.getByText(taggedTitle, { exact: true })).toBeVisible()
    await expect(page.getByText(untaggedTitle, { exact: true })).toHaveCount(0)
  } finally {
    if (taggedId) await request.delete(`/api/document/${taggedId}`).catch(() => {})
    if (untaggedId) await request.delete(`/api/document/${untaggedId}`).catch(() => {})
    if (tagId) await request.delete(`/api/tag/${tagId}`).catch(() => {})
  }
})
