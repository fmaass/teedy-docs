import { test, expect, type APIRequestContext } from './fixtures'
import { unique, uniqueTag, isMobileViewport, deleteDocApi, deleteTagApi } from './helpers'

async function apiCreateDocument(request: APIRequestContext, title: string): Promise<string> {
  const res = await request.put('/api/document', { form: { title, language: 'eng' } })
  expect(res.ok(), `create document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

async function apiCreateTag(request: APIRequestContext, name: string): Promise<string> {
  const res = await request.put('/api/tag', { form: { name, color: '#3399cc' } })
  expect(res.ok(), `create tag ${name}`).toBeTruthy()
  return (await res.json()).id as string
}

async function apiDocTagIds(request: APIRequestContext, docId: string): Promise<string[]> {
  const res = await request.get(`/api/document/${docId}`)
  expect(res.ok(), `read document ${docId}`).toBeTruthy()
  return ((await res.json()).tags ?? []).map((tg: { id: string }) => tg.id)
}

// Alphanumeric single token so the filter resolves to exactly one option, which the
// keyboard add (ArrowDown, Enter) then commits — a partial or multi-match name would
// let it commit the wrong tag.
function tagName(): string {
  return uniqueTag('focustag').replace(/[^a-z0-9]/gi, '').toLowerCase()
}

async function expectFilterFocused(page: import('@playwright/test').Page): Promise<void> {
  const filter = page.locator('.p-select-overlay input.p-select-filter')
  await expect(filter, 'tag filter input is focused on surface open (no click)').toBeFocused()
  const activeIsFilter = await page.evaluate(() =>
    (document.activeElement?.className ?? '').includes('p-select-filter'),
  )
  expect(activeIsFilter, 'document.activeElement is the tag filter input').toBe(true)
}

async function keyboardAddTag(page: import('@playwright/test').Page, name: string): Promise<void> {
  await page.keyboard.type(name)
  // SETTLE BEFORE COMMITTING: the typed filter re-renders the option list, and the
  // highlight ArrowDown/Enter commits is computed against whatever list is mounted at that
  // instant. Asserting on the TARGET option alone is satisfied by the still-unfiltered
  // list (it is count-1 there too), so the barrier is the whole list collapsing to the
  // single survivor — only the post-filter render can satisfy that.
  const options = page.locator('.p-select-overlay .p-select-option')
  await expect(options, 'the filtered option list has settled to one survivor').toHaveCount(1)
  await expect(options.first()).toContainText(name)
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
}

test('right-click tag menu focuses the filter and adds a tag by keyboard alone (#171)', async ({ page, request, cleanup }) => {
  test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent')
  const name = tagName()
  const title = unique('tqm-focus-doc')
  const tagId = await apiCreateTag(request, name)
  cleanup.defer('delete the quick-menu tag', () => deleteTagApi(request, tagId))
  const docId = await apiCreateDocument(request, title)
  cleanup.defer('purge the quick-menu document', () => deleteDocApi(request, docId))

  expect(await apiDocTagIds(request, docId)).not.toContain(tagId)

  await page.goto('/#/document')
  const row = page.getByRole('row', {
    name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
  await expect(row).toBeVisible()

  await row.click({ button: 'right' })
  await expect(page.locator('.p-popover')).toBeVisible()

  await expectFilterFocused(page)

  await keyboardAddTag(page, name)
  await expect
    .poll(() => apiDocTagIds(request, docId), { message: 'tag added via keyboard-only quick menu' })
    .toContain(tagId)
})

test('slide-over tag-add focuses the filter and adds a tag by keyboard alone (#171)', async ({ page, request, cleanup }) => {
  const name = tagName()
  const title = unique('slide-focus-doc')
  const tagId = await apiCreateTag(request, name)
  cleanup.defer('delete the slide-over tag', () => deleteTagApi(request, tagId))
  const docId = await apiCreateDocument(request, title)
  cleanup.defer('purge the slide-over document', () => deleteDocApi(request, docId))

  expect(await apiDocTagIds(request, docId)).not.toContain(tagId)

  await page.goto('/#/document')
  await page.getByRole('cell', { name: title }).first().click()
  const slideOver = page.getByRole('dialog')
  await expect(slideOver).toBeVisible()
  await expect(slideOver.locator('.slide-over-title')).toHaveText(title)

  await slideOver.locator('.tag-add-btn').click()

  await expectFilterFocused(page)

  await keyboardAddTag(page, name)
  await expect
    .poll(() => apiDocTagIds(request, docId), { message: 'tag added via keyboard-only slide-over' })
    .toContain(tagId)
})
