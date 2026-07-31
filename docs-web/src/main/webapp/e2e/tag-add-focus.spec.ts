import { test, expect, type APIRequestContext, type ConsoleMessage } from './fixtures'
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

// QUARANTINED (@flaky, #213). The auto-opened Select overlay intermittently self-dismisses
// before its filter input mounts, so `.p-select-overlay input.p-select-filter` never appears
// and this test reds a required CI job on a coin flip. The behaviour it covers is real and the
// assertions are sound — the QUARANTINE is on the flake, not on the feature — so the tag comes
// off as soon as #213 (the overlay self-dismissal) is fixed, with no other change to this test.
// Semantics of the tag: scripts/e2e-run.sh excludes `@flaky` from every default run (the
// release-gating e2e job in build-deploy.yml), while the nightly Scheduled Regression sets
// E2E_INCLUDE_FLAKY=1 (regression.yml) so the flake stays visible for triage.
test('@flaky right-click tag menu focuses the filter and adds a tag by keyboard alone (#171, quarantined #213)', async ({ page, request, cleanup }) => {
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

// #204 — the auto-open (#171) must not throw when the popover is dismissed while the
// Select's overlay is coming up.
//
// MECHANISM: opening the quick menu opens the Select, whose overlay-enter work scrolls
// `.app-content` (the app's page scroller). That scroll is exactly what the Popover's
// ConnectedOverlayScrollHandler dismisses on, so the popover tears its own Select down
// while PrimeVue 4.5.4 still has an unguarded `setTimeout(() => focus(this.$refs
// .filterInput.$el), 1)` in flight — the timer then dereferences a null ref and the page
// throws `TypeError: Cannot read properties of null (reading '$el')`.
//
// The popover CLOSING is expected product behaviour; the defect is console-only, so the
// assertion here is "nothing threw", which no other spec in this file makes.
//
// DETERMINISM: in the wild the dismissal only wins that race on a loaded machine (the
// pinned-CPU harness reproduces it ~6 runs in 8, which is precisely why it surfaced as a
// flake). Hoping for the interleaving would make this spec as load-sensitive as the bug.
// Instead a MutationObserver fires a `scroll` on `.app-content` the instant the Select's
// overlay is inserted — the same handler, the same dismissal, landing inside the same
// window the focus timer is armed in, on every run.
test('a scroll dismissal during the quick-menu auto-open raises no page error (#204)', async ({
  page,
  request,
  cleanup,
}) => {
  test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance with no touch equivalent')
  const pageErrors: string[] = []
  page.on('pageerror', (err) => pageErrors.push(`pageerror: ${err.message}`))
  page.on('console', (msg: ConsoleMessage) => {
    if (msg.type() === 'error') pageErrors.push(`console: ${msg.text()}`)
  })

  // At least one assignable tag, or the Select (and with it the whole race) is absent.
  const name = tagName()
  const title = unique('tqm-dismiss-doc')
  const tagId = await apiCreateTag(request, name)
  cleanup.defer('delete the dismissal-race tag', () => deleteTagApi(request, tagId))
  const docId = await apiCreateDocument(request, title)
  cleanup.defer('purge the dismissal-race document', () => deleteDocApi(request, docId))

  await page.goto('/#/document')
  const row = page.getByRole('row', {
    name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
  await expect(row).toBeVisible()

  await page.evaluate(() => {
    const scroller = document.querySelector('.app-content')
    const observer = new MutationObserver((records) => {
      for (const record of records) {
        for (const node of Array.from(record.addedNodes)) {
          if (node instanceof HTMLElement && node.classList.contains('p-select-overlay')) {
            observer.disconnect()
            // The real dismissal arrives as a `scroll` event on this element from the
            // overlay's own scroll-into-view; dispatching it directly reproduces that
            // wakeup without depending on machine load or popover geometry.
            scroller?.dispatchEvent(new Event('scroll'))
            ;(window as unknown as { __tqmOverlayDismissed?: boolean }).__tqmOverlayDismissed = true
            return
          }
        }
      }
    })
    observer.observe(document.body, { childList: true, subtree: true })
  })

  await row.click({ button: 'right' })

  // REALNESS: the race is only exercised if the Select's overlay actually mounted (the
  // arming fired) AND the dismissal actually reached the popover. Without both, "no page
  // error" would be vacuously true.
  await expect
    .poll(
      () =>
        page.evaluate(
          () =>
            (window as unknown as { __tqmOverlayDismissed?: boolean }).__tqmOverlayDismissed ===
            true,
        ),
      { message: 'the tag Select overlay mounted and the scroll dismissal was armed' },
    )
    .toBe(true)
  await expect(page.locator('.p-popover'), 'the scroll dismissed the quick menu').toHaveCount(0)

  // The focus timer is armed for 1ms but only runs once the thread is free; give it — and
  // any other deferred work the teardown left behind — room to land before asserting.
  await page.waitForTimeout(1000)
  expect(pageErrors, `page errors: ${pageErrors.join(' | ')}`).toEqual([])
})

// QUARANTINED (@flaky, #213) — the same exposure as the right-click test above, reached
// through the slide-over instead of the context menu: both drive expectFilterFocused, so the
// overlay self-dismissing before its filter input mounts reds this one identically. Quarantining
// only its sibling would leave the flake free to red the nightly from here. Same terms: the tag
// comes off when #213 is fixed, with no other change to this test.
test('@flaky slide-over tag-add focuses the filter and adds a tag by keyboard alone (#171, quarantined #213)', async ({ page, request, cleanup }) => {
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
