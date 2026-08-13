import {
  test,
  expect,
  type APIRequestContext,
  type BrowserContext,
  type Locator,
  type Page,
} from './fixtures'
import {
  unique,
  uniqueTag,
  deleteDocApi,
  deleteTagApi,
  gotoRouteReady,
  isMobileViewport,
  ROUTE_ROOT,
  gotoDocumentList,
} from './helpers'

// Runs authenticated. Creates a document via the real Add-document form. On save,
// Teedy routes to the full document view (DocumentEdit -> document-view). We then
// return to the list, verify the new document appears there, and open it.
test('creates a document, sees it in the list, and opens it', async ({ page }) => {
  const title = `E2E doc ${Date.now()}`

  await gotoRouteReady(page, '/#/document/add', ROUTE_ROOT.documentEdit)
  await expect(page.getByRole('heading', { name: 'New document' })).toBeVisible()

  await page.locator('#edit-title').fill(title)
  await page.getByRole('button', { name: 'Save' }).click()

  // Save routes to the full document view, whose header shows the new title.
  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()

  // Return to the list — the new document appears in the table.
  await gotoDocumentList(page)
  const titleCell = page.getByText(title, { exact: true })
  await expect(titleCell).toBeVisible()

  // Open it: clicking the row opens the slide-over panel showing the title, whose
  // "Open" button routes back to the full document view.
  await titleCell.click()
  await page.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
})

// --- Behavior D (document-list UX, #11/#24/#25) ------------------------------
// Three document-list papercuts: (1) double-clicking a row navigates straight to
// the full document view; (2) a document with >3 tags collapses the extra tags
// into a focusable "+N" control whose popover (teleported to <body>, so it is not
// clipped by the DataTable's overflow) reveals the remaining tags; (3) admin/
// settings table pages render at the wider content width.
//
// REALNESS: the dblclick test asserts the URL actually became /document/view/<id>
// (single-click only opens a slide-over, so this fails if dblclick isn't wired to
// navigate). The +N test seeds a doc with 5 tags and asserts exactly a "+2" control
// whose popover reveals the 4th and 5th tag names (removing TagOverflow or the
// slice(0,3) cap fails it). The wide-width test asserts the .settings-content--wide
// class the wideSettings route flag toggles.

test('double-clicking a document row navigates to the full document view (D #11)', async ({ page }) => {
  const title = unique('D-dblclick')

  // FIRST navigation of the test: the #215 barrier applies, and the save below is the
  // in-app navigation it would swallow.
  await gotoRouteReady(page, '/#/document/add', ROUTE_ROOT.documentEdit)
  await page.locator('#edit-title').fill(title)
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)

  await gotoDocumentList(page)
  const row = page.getByRole('row', { name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })
  await expect(row).toBeVisible()

  // Double-click navigates straight to the full document view — no slide-over,
  // no "Open" click.
  await row.dblclick()
  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
})

test('a document with more than 3 tags shows a focusable +N control whose popover reveals the rest (D #24)', async ({ page, baseURL, request, cleanup }) => {
  // Seed 5 uniquely-named tags + a document carrying all of them via REST so the
  // list row deterministically overflows (>3 tags). The tags are named so their
  // creation order is stable; the first 3 render inline, the last 2 collapse.
  const base = baseURL!
  const runId = uniqueTag('Dtag')
  const tagNames = [1, 2, 3, 4, 5].map((n) => `${runId}-${n}`)
  const tagIds: string[] = []
  for (const name of tagNames) {
    const res = await request.put(`${base}/api/tag`, { form: { name, color: '#2aabd2' } })
    expect(res.ok(), `create tag ${name}`).toBeTruthy()
    const tagId = (await res.json()).id as string
    tagIds.push(tagId)
    cleanup.defer(`delete tag ${name}`, () => deleteTagApi(page.request, tagId))
  }

  const docTitle = unique('D-overflow')
  const docForm = new URLSearchParams()
  docForm.set('title', docTitle)
  docForm.set('language', 'eng')
  for (const id of tagIds) docForm.append('tags', id)
  const docRes = await request.put(`${base}/api/document`, {
    data: docForm.toString(),
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  })
  expect(docRes.ok(), 'create tagged document').toBeTruthy()
  const docId = (await docRes.json()).id as string
  cleanup.defer('purge the tagged document', () => deleteDocApi(page.request, docId))

  await gotoDocumentList(page)
  const row = page.getByRole('row', { name: new RegExp(docTitle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })
  await expect(row).toBeVisible()

  // Exactly the first 3 tags render inline as badges; the +N control accounts for
  // the remaining 2.
  const overflow = row.getByRole('button', { name: /more tags/i })
  await expect(overflow).toBeVisible()
  await expect(overflow).toHaveText(/\+2/)

  // It is focusable (keyboard-operable) and starts collapsed.
  await overflow.focus()
  await expect(overflow).toBeFocused()
  await expect(overflow).toHaveAttribute('aria-expanded', 'false')

  // Activating it opens the popover (teleported to <body>) with the 2 hidden tags,
  // and does NOT navigate the row (still on the list).
  //
  // On the touch viewport the tap is occasionally swallowed under full-suite load: a
  // list re-render (the document query keeps previous data and refetches) replaces the
  // trigger between actionability and dispatch, so the toggle handler never runs and
  // aria-expanded stays false (#118). The reveal is idempotent and
  // the handler flips `open` synchronously, so aria-expanded is an EXACT post-tap signal:
  // retry the tap until it flips, re-tapping ONLY while still collapsed (a tap on an
  // already-open trigger would toggle it shut). Same dropped-click remedy as the
  // Empty-trash gesture in trash.spec.ts. No arbitrary sleeps — the DOM signal gates it.
  await expect(async () => {
    if ((await overflow.getAttribute('aria-expanded')) !== 'true') {
      await overflow.click()
    }
    await expect(overflow).toHaveAttribute('aria-expanded', 'true', { timeout: 1000 })
  }).toPass({ timeout: 15000 })
  await expect(page).toHaveURL(/#\/document$/)
  const panel = page.locator('.tag-overflow-panel')
  await expect(panel).toBeVisible()
  await expect(panel.getByText(tagNames[3], { exact: true })).toBeVisible()
  await expect(panel.getByText(tagNames[4], { exact: true })).toBeVisible()
  // The first-3 tags are NOT repeated inside the overflow popover.
  await expect(panel.getByText(tagNames[0], { exact: true })).toHaveCount(0)
})

/**
 * Middle-click `link` and return the tab it opens, retrying the click a bounded number of times.
 *
 * #223: on a CPU-contended run, Playwright can lose a popup's navigation signals — two surfaces,
 * one cause. Sometimes the popup's `page` event never reaches the test at all (measured: still
 * absent 55s after the click, while the popup's own requests — app bundle, /api/user,
 * /api/document/<id> — all completed against the server, so waiting longer is not a remedy).
 * Sometimes the page does arrive but its top-level request is never reported as finished, which
 * leaves a pending document on the frame; every frame-gated matcher (locator queries,
 * toHaveURL, waitForLoadState) then queues behind "waiting for … navigation to finish" until it
 * times out. The popup itself is healthy in both cases: an in-page probe during a stalled run
 * read document.readyState "complete", a PerformanceNavigationTiming with responseEnd ~7ms and
 * loadEventEnd ~74ms, the target route hash and the rendered heading.
 *
 * A retry is therefore the honest remedy for the first surface (a fresh popup gets fresh
 * bookkeeping) and evaluate() for the second. Neither weakens the test: a title that is not a
 * real link opens no tab on ANY attempt, and there is nothing for evaluate() to read.
 */
async function middleClickNewTab(context: BrowserContext, link: Locator): Promise<Page> {
  const attempts = 3
  for (let attempt = 1; attempt <= attempts; attempt++) {
    // Registered BEFORE the click so the event cannot be missed between the two.
    const opened = context.waitForEvent('page', { timeout: 4_000 }).catch(() => null)
    await link.click({ button: 'middle' })
    const newTab = await opened
    if (newTab) return newTab
  }
  throw new Error(`middle-clicking the document title opened no new tab in ${attempts} attempts`)
}

// --- #194: the document title is a REAL link, so the browser's own new-tab
//     affordances work on it. Middle-click is the one that is purely native (no app
//     code runs beyond the modifier guard declining to intercept), so it is the
//     honest end-to-end proof: a genuinely-navigable href reached the browser.
//
// REALNESS: the assertion is on the SECOND page the browser context opens and on
// ITS route + heading. Rendering the title as a <span> (the pre-#194 markup), or
// preventDefault-ing unconditionally, opens no page at all and middleClickNewTab
// throws — there is no way to satisfy this without a working link.
test('middle-clicking a document title opens the full view in a new tab (#194)', async ({
  page,
  context,
  request,
  cleanup,
}) => {
  const title = unique('D-newtab')
  const res = await request.put('/api/document', { form: { title, language: 'eng' } })
  expect(res.ok(), 'create the new-tab document').toBeTruthy()
  const docId = (await res.json()).id as string
  cleanup.defer('purge the new-tab document', () => deleteDocApi(request, docId))

  await gotoDocumentList(page)
  const link = page.getByRole('link', { name: title, exact: true })
  await expect(link).toBeVisible()
  // The href is the document's own full-view route — not "#" or a JS placeholder.
  await expect(link).toHaveAttribute('href', new RegExp(`#/document/view/${docId}$`))

  const newTab = await middleClickNewTab(context, link)

  // The popup's rendered route and its own heading — the same two facts the URL matcher and the
  // heading locator asserted before, read out of the live document instead of behind the
  // navigation barrier (see middleClickNewTab). A poll, because the first evaluate can still
  // land on the popup's initial about:blank.
  const popupState = () =>
    newTab
      .evaluate(() => ({
        hash: location.hash,
        headings: Array.from(document.querySelectorAll('h1')).map((h) => (h.textContent ?? '').trim()),
      }))
      .catch(() => ({ hash: '', headings: [] as string[] }))
  await expect
    .poll(async () => (await popupState()).hash, {
      message: "the new tab is on the document's own full-view route",
    })
    .toMatch(new RegExp(`#/document/view/${docId}`))
  await expect
    .poll(async () => (await popupState()).headings, {
      message: 'the new tab rendered the document view for THIS document',
    })
    .toContain(title)

  // Close whatever the middle clicks opened (a retried attempt can leave a second tab).
  for (const openTab of context.pages()) {
    if (openTab !== page) await openTab.close()
  }

  // The originating list did NOT navigate and did NOT open the slide-over — a middle
  // click must not be treated as the plain-click "open slide-over" gesture.
  await expect(page).toHaveURL(/#\/document$/)
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

test('admin/settings table pages render at the wider content width (D #25)', async ({ page }) => {
  // A wideSettings route (users) opts into the full-width layout; a narrow-measure
  // route (account) keeps the 800px cap. Assert the class the flag toggles.
  await gotoRouteReady(page, '/#/settings/users', ROUTE_ROOT.settingsUsers)
  await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible()
  await expect(page.locator('.settings-content.settings-content--wide')).toBeVisible()
  // And its computed max-width is unconstrained (not the 800px text cap).
  const wideMax = await page
    .locator('.settings-content')
    .evaluate((el) => getComputedStyle(el).maxWidth)
  expect(wideMax).toBe('none')

  // The narrow account page does NOT get the wide modifier.
  await gotoRouteReady(page, '/#/settings/account', ROUTE_ROOT.settingsAccount)
  await expect(page.locator('.settings-content')).toBeVisible()
  await expect(page.locator('.settings-content.settings-content--wide')).toHaveCount(0)
})

// --- #234: the quick-tag menu must go away on a right-click OUTSIDE it -------------
//
// The menu is a PrimeVue Popover, and PrimeVue dismisses a Popover on an outside `click`.
// A right-click fires no click, so a second right-click that landed anywhere but a document
// row left the menu standing while the browser drew its own menu next to it — the two menus
// at once the reporter saw. Only the stale popover is the defect: off a document row the
// native menu belongs to the user and must keep coming up.
//
// HOW THE NATIVE MENU IS ASSERTED: it is browser chrome, drawn outside the page, so no test
// can see it. What a test CAN see is whether the page cancelled the gesture — a window-level
// `contextmenu` listener reads `defaultPrevented` once every handler on the path has had its
// say. `false` means the browser went on to draw its own menu; `true` means the app claimed
// the gesture. That is the same contract the #194 shift escape hatch rests on.
//
// DESKTOP-ONLY: a right-click has no touch equivalent, and the menu has no long-press
// handler, so on the mobile project there is no gesture to leak (same skip as every other
// quick-menu spec).

// The two surfaces that raise the quick-tag menu: a table row and a gallery card.
const RIGHT_CLICK_VIEWS = ['list', 'gallery'] as const
type RightClickView = (typeof RIGHT_CLICK_VIEWS)[number]

async function seedListDocument(request: APIRequestContext, title: string): Promise<string> {
  const res = await request.put('/api/document', { form: { title, language: 'eng' } })
  expect(res.ok(), `create document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

async function gotoDocumentView(page: Page, view: RightClickView): Promise<void> {
  await gotoDocumentList(page)
  if (view !== 'gallery') return
  await page.locator('.view-mode-toggle').getByText('Gallery', { exact: true }).click()
  await expect(page.locator('article.doc-card').first()).toBeVisible()
}

function documentSurface(page: Page, view: RightClickView, title: string): Locator {
  if (view === 'gallery') {
    return page
      .locator('article.doc-card')
      .filter({ has: page.getByRole('link', { name: title, exact: true }) })
  }
  return page.getByRole('row', {
    name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
}

// The quick-tag menu specifically — the app mounts other popovers (saved filters, the tag
// overflow chip), so the match is pinned to the menu's own body.
function quickTagMenu(page: Page): Locator {
  return page.locator('.p-popover').filter({ has: page.locator('.tqm-body') })
}

async function recordContextMenus(page: Page): Promise<void> {
  await page.evaluate(() => {
    const recorded: boolean[] = []
    ;(window as unknown as { __ctxPrevented: boolean[] }).__ctxPrevented = recorded
    // Window, bubble phase: the last stop on the path, so it reads the verdict every handler
    // below it has already reached.
    window.addEventListener('contextmenu', (event) => recorded.push(event.defaultPrevented))
  })
}

async function lastContextMenuPrevented(page: Page): Promise<boolean | undefined> {
  return page.evaluate(() =>
    (window as unknown as { __ctxPrevented: boolean[] }).__ctxPrevented.at(-1),
  )
}

for (const view of RIGHT_CLICK_VIEWS) {
  test(`a right-click outside the quick-tag menu dismisses it and leaves the native menu alone (#234, ${view})`, async ({
    page,
    request,
    cleanup,
  }) => {
    test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent')
    const title = unique(`ctx-outside-${view}`)
    const docId = await seedListDocument(request, title)
    cleanup.defer('purge the outside-right-click document', () => deleteDocApi(request, docId))

    await gotoDocumentView(page, view)
    const surface = documentSurface(page, view, title)
    await expect(surface).toBeVisible()
    await recordContextMenus(page)

    // CONTROL: on a document the app owns the gesture — the menu comes up AND the native
    // menu is suppressed. Without this the test would pass on a build that never handles
    // contextmenu at all.
    await surface.click({ button: 'right' })
    const menu = quickTagMenu(page)
    await expect(menu).toBeVisible()
    expect(
      await lastContextMenuPrevented(page),
      'a right-click ON a document is claimed by the app',
    ).toBe(true)

    // A point on the page background, taken from the open menu's own box so it is provably
    // to the left of it — this cannot degrade into a click on the menu itself.
    const menuBox = (await menu.boundingBox())!
    const backgroundX = 12
    expect(backgroundX, 'the background point sits outside the open menu').toBeLessThan(menuBox.x)
    await page.mouse.click(backgroundX, Math.round(menuBox.y), { button: 'right' })

    // THE DEFECT: the menu stayed up here, beside the browser's own menu.
    await expect(menu, 'a right-click outside the quick-tag menu dismisses it').toHaveCount(0)
    // ...and that right-click still belongs to the browser: suppressing the popover must not
    // suppress the native menu the user asked for.
    expect(
      await lastContextMenuPrevented(page),
      'a right-click off a document stays with the browser',
    ).toBe(false)
  })

  test(`a right-click on another document moves the quick-tag menu instead of closing it (#234, ${view})`, async ({
    page,
    request,
    cleanup,
  }) => {
    test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent')
    const firstTitle = unique(`ctx-move-a-${view}`)
    const secondTitle = unique(`ctx-move-b-${view}`)
    const firstId = await seedListDocument(request, firstTitle)
    cleanup.defer('purge the first move document', () => deleteDocApi(request, firstId))
    const secondId = await seedListDocument(request, secondTitle)
    cleanup.defer('purge the second move document', () => deleteDocApi(request, secondId))

    await gotoDocumentView(page, view)
    const first = documentSurface(page, view, firstTitle)
    const second = documentSurface(page, view, secondTitle)
    await expect(first).toBeVisible()
    await expect(second).toBeVisible()

    // Which document the menu is bound to, read off the menu itself: its "open in new tab"
    // link is that document's own route (#194).
    const openLink = quickTagMenu(page).locator('.tqm-open-link')
    await first.click({ button: 'right' })
    await expect(openLink).toHaveAttribute('href', new RegExp(`#/document/view/${firstId}$`))

    // ONE gesture on a different document has to move the menu there. Dismissing an outside
    // right-click must not cost the user a right-click to close and another to reopen.
    await second.click({ button: 'right' })
    await expect(
      openLink,
      'the menu followed the cursor to the document under it',
    ).toHaveAttribute('href', new RegExp(`#/document/view/${secondId}$`))
    await expect(quickTagMenu(page)).toBeVisible()
  })

  test(`shift+right-click keeps the native menu and takes the quick-tag menu away (#194/#234, ${view})`, async ({
    page,
    request,
    cleanup,
  }) => {
    test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent')
    const title = unique(`ctx-shift-${view}`)
    const docId = await seedListDocument(request, title)
    cleanup.defer('purge the shift-right-click document', () => deleteDocApi(request, docId))

    await gotoDocumentView(page, view)
    const surface = documentSurface(page, view, title)
    await expect(surface).toBeVisible()
    await recordContextMenus(page)

    await surface.click({ button: 'right' })
    const menu = quickTagMenu(page)
    await expect(menu).toBeVisible()
    expect(await lastContextMenuPrevented(page), 'the plain right-click is claimed').toBe(true)

    // THE ESCAPE HATCH (#194): shift+right-click is left completely alone, so the browser's
    // own menu comes up — and the popover in front of it must not stay behind (#234).
    await surface.click({ button: 'right', modifiers: ['Shift'] })
    expect(
      await lastContextMenuPrevented(page),
      'shift+right-click is never claimed by the app',
    ).toBe(false)
    await expect(menu, 'the popover does not stay standing in front of the native menu').toHaveCount(0)
  })
}

// The other side of "outside": a right-click inside the menu's own inline tag search box must
// NOT dismiss the menu. Since TEEDY-86 the search box and its tag list render INLINE inside the
// popover (no teleported Select overlay anymore), so the filter the user right-clicks IS a DOM
// descendant of the menu — the genuine "inside" case that onOutsideContextMenu's
// `popover.container.contains(target)` guard passes over. One view is enough: the inline menu is
// the same either way.
test('a right-click in the menu\'s own tag filter leaves the menu open (#234)', async ({
  page,
  request,
  cleanup,
}) => {
  test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent')
  // At least one assignable tag, or the inline search box + tag list are not rendered.
  const tagName = uniqueTag('ctx-inside').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const tagRes = await request.put('/api/tag', { form: { name: tagName, color: '#3399cc' } })
  expect(tagRes.ok(), 'create the tag-filter tag').toBeTruthy()
  const tagId = (await tagRes.json()).id as string
  cleanup.defer('delete the tag-filter tag', () => deleteTagApi(request, tagId))
  const title = unique('ctx-inside')
  const docId = await seedListDocument(request, title)
  cleanup.defer('purge the tag-filter document', () => deleteDocApi(request, docId))

  await gotoDocumentView(page, 'list')
  const surface = documentSurface(page, 'list', title)
  await expect(surface).toBeVisible()

  await surface.click({ button: 'right' })
  const menu = quickTagMenu(page)
  await expect(menu).toBeVisible()
  // The tag search box is inline and always visible now (TEEDY-86) — no open step.
  const filter = page.locator('.p-popover input.tqm-filter-input')
  await expect(filter, 'the menu shows its inline tag search box (#171)').toBeVisible()
  // REALNESS: the filter really is a DOM descendant of the menu, so this exercises the
  // `container.contains(target)` "inside" carve-out and not a check that would pass anyway.
  const inside = await page.evaluate(() => {
    const popover = document.querySelector('.p-popover')
    const input = document.querySelector('.p-popover input.tqm-filter-input')
    return !!popover && !!input && popover.contains(input)
  })
  expect(inside, 'the tag filter is a descendant of the menu').toBe(true)

  await filter.click({ button: 'right' })

  await expect(menu, 'the menu survives a right-click in its own tag filter').toBeVisible()
  await expect(filter, 'and so does the filter the user was about to paste into').toBeVisible()
})
