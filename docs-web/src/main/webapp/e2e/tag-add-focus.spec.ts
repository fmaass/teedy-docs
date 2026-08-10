import { test, expect, type APIRequestContext } from './fixtures'
import {
  unique,
  uniqueTag,
  isMobileViewport,
  deleteDocApi,
  deleteTagApi,
  gotoDocumentList,
} from './helpers'

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

test('right-click tag menu adds a tag by keyboard once its Select is opened (#171/#234)', async ({ page, request, cleanup }) => {
  test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent')
  const name = tagName()
  const title = unique('tqm-focus-doc')
  const tagId = await apiCreateTag(request, name)
  cleanup.defer('delete the quick-menu tag', () => deleteTagApi(request, tagId))
  const docId = await apiCreateDocument(request, title)
  cleanup.defer('purge the quick-menu document', () => deleteDocApi(request, docId))

  expect(await apiDocTagIds(request, docId)).not.toContain(tagId)

  await gotoDocumentList(page)
  const row = page.getByRole('row', {
    name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
  await expect(row).toBeVisible()

  await row.click({ button: 'right' })
  await expect(page.locator('.p-popover')).toBeVisible()

  // The tag Select no longer auto-opens on the right-click menu (#234 follow-up): the menu
  // presents as a single panel. Open the Select with a click and put the caret in its filter,
  // then add the tag by keyboard alone from there.
  await page.locator('.tqm-select').click()
  const filter = page.locator('.p-select-overlay input.p-select-filter')
  await expect(filter).toBeVisible()
  await filter.click()
  await expect(filter, 'the tag filter takes the caret once the Select is opened').toBeFocused()

  await keyboardAddTag(page, name)
  await expect
    .poll(() => apiDocTagIds(request, docId), { message: 'tag added via keyboard quick menu' })
    .toContain(tagId)
})

// #274 — the tag filter box gets a clear (×), the parity with the main search bar the
// reporter asked for. It shows only once something is typed, empties the box in one click,
// and leaves the caret in the field so the next search can be typed straight away.
test('the right-click tag filter offers a clear (×) that empties it and keeps the caret (#274)', async ({
  page,
  request,
  cleanup,
}) => {
  test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent')
  const name = tagName()
  const title = unique('tqm-clear-doc')
  const tagId = await apiCreateTag(request, name)
  cleanup.defer('delete the clear-filter tag', () => deleteTagApi(request, tagId))
  const docId = await apiCreateDocument(request, title)
  cleanup.defer('purge the clear-filter document', () => deleteDocApi(request, docId))

  await gotoDocumentList(page)
  const row = page.getByRole('row', {
    name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
  await expect(row).toBeVisible()

  await row.click({ button: 'right' })
  await expect(page.locator('.p-popover')).toBeVisible()

  await page.locator('.tqm-select').click()
  const filter = page.locator('.p-select-overlay input.p-select-filter')
  await expect(filter).toBeVisible()

  // Empty box → just the magnifier, no clear affordance.
  const clear = page.locator('.p-select-overlay .tqm-filter-clear')
  await expect(clear).toHaveCount(0)

  await filter.click()
  await filter.fill(name.slice(0, 3))
  await expect(filter).toHaveValue(name.slice(0, 3))
  await expect(clear, 'a clear button appears once the filter has text').toBeVisible()

  await clear.click()
  await expect(filter, 'the clear empties the filter').toHaveValue('')
  await expect(filter, 'the caret stays in the field for the next search').toBeFocused()
  await expect(clear, 'the magnifier is back once the box is empty').toHaveCount(0)
})

// PADDING (both #213 tests): the document list only scrolls once it is longer than the
// viewport, and an API-created fixture document rarely makes it so. Both tests REPLAY a
// scroll, so they pad the page first — and they do it inside the SAME evaluate as the
// scroll, because a Vue re-render in between drops a foreign child of `.app-content`
// (observed: padding one step earlier left the control test with nothing to scroll).
// Each test asserts the page really moved, so an unpadded page fails loudly instead of
// passing vacuously.

// #213 — the quick menu must survive a scroll the user FINISHED before right-clicking.
//
// MECHANISM: setting `scrollTop` moves the page immediately but does NOT dispatch the
// `scroll` event there; the browser queues it and fires it at the start of the next
// rendering update. PrimeVue's Popover binds a dismiss-on-scroll listener on its anchor's
// scrollable ancestors (`.app-content` here) the moment it mounts — which, for a popover
// opened in the same task as the scroll, happens on the very next microtask checkpoint,
// i.e. BEFORE that queued event is delivered. The already-finished scroll then lands in the
// fresh listener and the menu closes itself. In the wild this needs a loaded machine (the
// scroll delivery has to slip past the open); scrolling and right-clicking inside ONE task
// produces the same ordering on any machine, which is what makes this deterministic
// without a CPU pin. The fix arms the popover one rendering step later (utils/nextFrame),
// so the queued event is delivered first and only later scrolls can reach it.
test('a scroll finished BEFORE the right-click does not dismiss the quick menu (#213)', async ({
  page,
  request,
  cleanup,
}) => {
  test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent')
  // At least one assignable tag, or no Select is rendered and the menu is a different shape.
  const name = tagName()
  const title = unique('tqm-stale-scroll-doc')
  const tagId = await apiCreateTag(request, name)
  cleanup.defer('delete the stale-scroll tag', () => deleteTagApi(request, tagId))
  const docId = await apiCreateDocument(request, title)
  cleanup.defer('purge the stale-scroll document', () => deleteDocApi(request, docId))

  await gotoDocumentList(page)
  const row = page.getByRole('row', {
    name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
  await expect(row).toBeVisible()

  const moved = await page.evaluate((docTitle: string) => {
    const scroller = document.querySelector('.app-content') as HTMLElement
    if (scroller.scrollHeight <= scroller.clientHeight) {
      const spacer = document.createElement('div')
      spacer.style.height = '2000px'
      scroller.appendChild(spacer)
    }
    const target = Array.from(document.querySelectorAll('tbody tr')).find((tr) =>
      tr.textContent?.includes(docTitle),
    ) as HTMLElement
    const flags = window as unknown as { __tqmScrollDelivered?: boolean }
    flags.__tqmScrollDelivered = false
    scroller.addEventListener('scroll', () => (flags.__tqmScrollDelivered = true), { once: true })

    const before = scroller.scrollTop
    // Toward whichever end has room: the page may already sit at one end of its range.
    scroller.scrollTop = before > 0 ? before - 40 : before + 40
    const didMove = scroller.scrollTop !== before
    // SAME TASK: no await between the scroll and the right-click, so the browser has had no
    // rendering step in which to deliver the scroll event.
    const box = target.getBoundingClientRect()
    target.dispatchEvent(
      new MouseEvent('contextmenu', {
        bubbles: true,
        cancelable: true,
        clientX: Math.round(box.left + box.width / 2),
        clientY: Math.round(box.top + box.height / 2),
      }),
    )

    return didMove
  }, title)

  // REALNESS: "the menu is still open" is only meaningful if the page really scrolled AND
  // the browser really delivered that scroll — otherwise there was no dismissal to survive.
  expect(moved, 'the page actually scrolled before the right-click').toBe(true)
  await expect
    .poll(
      () =>
        page.evaluate(
          () =>
            (window as unknown as { __tqmScrollDelivered?: boolean }).__tqmScrollDelivered === true,
        ),
      { message: 'the browser delivered the queued scroll event' },
    )
    .toBe(true)

  // The verdict is read off a POSITIVE end state rather than waited out on a timer: a menu
  // that survived is live and interactive — its tag Select opens on a click and mounts an
  // overlay — where a dismissed one never gets there. Asserting only "a .p-popover exists"
  // would not be enough: a dismissed popover is still in the DOM while it plays its leave
  // transition. (The menu no longer auto-opens the Select on show — #234 follow-up.)
  await page.locator('.tqm-select').click()
  await expect(
    page.locator('.p-select-overlay'),
    'the surviving menu opened its tag Select on click',
  ).toBeVisible()
  await expect(
    page.locator('.p-popover'),
    'the quick menu survived a scroll that finished before it opened',
  ).toHaveCount(1)
})

// The other half of the contract: suppressing the stale scroll must not have suppressed the
// dismissal itself. A popover anchored to a row has to go away when that row scrolls out
// from under it, so a scroll made AFTER the menu is up still closes it.
test('a scroll made while the quick menu is open still dismisses it (#213)', async ({
  page,
  request,
  cleanup,
}) => {
  test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent')
  const title = unique('tqm-live-scroll-doc')
  const docId = await apiCreateDocument(request, title)
  cleanup.defer('purge the live-scroll document', () => deleteDocApi(request, docId))

  await gotoDocumentList(page)
  const row = page.getByRole('row', {
    name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
  await expect(row).toBeVisible()

  await row.click({ button: 'right' })
  await expect(page.locator('.p-popover')).toBeVisible()

  const scrolled = await page.evaluate(() => {
    const scroller = document.querySelector('.app-content') as HTMLElement
    if (scroller.scrollHeight <= scroller.clientHeight) {
      const spacer = document.createElement('div')
      spacer.style.height = '2000px'
      scroller.appendChild(spacer)
    }
    const before = scroller.scrollTop
    // Toward whichever end has room: opening the menu scrolled the row into view, which
    // can leave the page parked at the far end of a short scroll range.
    scroller.scrollTop = before > 0 ? before - 40 : before + 40
    return {
      moved: scroller.scrollTop !== before,
      range: scroller.scrollHeight - scroller.clientHeight,
    }
  })
  expect(
    scrolled.moved,
    `the page actually scrolled under the open menu (scrollable range ${scrolled.range}px)`,
  ).toBe(true)
  await expect(page.locator('.p-popover'), 'the live scroll dismissed the quick menu').toHaveCount(0)
})

// The same exposure as the right-click test above, reached through the slide-over instead of
// the context menu: both drive expectFilterFocused, so an overlay that self-dismisses before
// its filter input mounts reds this one identically.
test('slide-over tag-add focuses the filter and adds a tag by keyboard alone (#171)', async ({ page, request, cleanup }) => {
  const name = tagName()
  const title = unique('slide-focus-doc')
  const tagId = await apiCreateTag(request, name)
  cleanup.defer('delete the slide-over tag', () => deleteTagApi(request, tagId))
  const docId = await apiCreateDocument(request, title)
  cleanup.defer('purge the slide-over document', () => deleteDocApi(request, docId))

  expect(await apiDocTagIds(request, docId)).not.toContain(tagId)

  await gotoDocumentList(page)
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
