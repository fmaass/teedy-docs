import { test, expect, type Page } from './fixtures'
import { login, deleteUser } from './helpers'

// Regression gate for issue #86 (two buttons broken at the mobile viewport in German):
//   1. Tag create button ("Erstellen") is squeezed by its flex row and clips its label.
//   2. Metadata "Feld hinzufügen" button wraps its label to two lines and clips at the
//      right edge, crowding the section heading.
//
// This is a SHARED spec (not responsive.spec.ts): it runs under BOTH the `desktop` and
// `mobile` Playwright projects. On mobile it is the hard gate that reproduces #86; on
// desktop it is the guarantee the fix introduced no desktop-layout regression (the same
// geometry invariants hold at the wide viewport, where both labels already fit).
//
// German is the reported failure locale (the English labels happen to fit), so every test
// activates `de` before the app boots.

const TOL = 1

interface Box {
  x: number
  y: number
  width: number
  height: number
}

// True when `inner` lies fully inside `outer` (with a sub-pixel tolerance).
function contains(outer: Box, inner: Box, tol = TOL): boolean {
  return (
    inner.x >= outer.x - tol &&
    inner.y >= outer.y - tol &&
    inner.x + inner.width <= outer.x + outer.width + tol &&
    inner.y + inner.height <= outer.y + outer.height + tol
  )
}

// True when two boxes do not intersect (touching edges count as disjoint).
function disjoint(a: Box, b: Box): boolean {
  return (
    a.x + a.width <= b.x ||
    b.x + b.width <= a.x ||
    a.y + a.height <= b.y ||
    b.y + b.height <= a.y
  )
}

interface ButtonGeom {
  box: Box
  scrollWidth: number
  clientWidth: number
  scrollHeight: number
  clientHeight: number
  labelText: string | null
  // Client rects of the label text, measured via a Range — where the glyphs are
  // ACTUALLY painted. Asserting only the label's own scrollWidth would be fooled by an
  // `overflow: hidden` parent that clips the label (it reports no overflow while its
  // glyphs are visibly cut off); the Range rects catch a clipped label.
  labelRects: Box[]
}

// Assert the shared per-button invariants (a,b,c): box inside the viewport, no internal
// overflow, every painted label rect inside the button box.
function assertButtonWithinAndLabelUnclipped(geom: ButtonGeom, viewportWidth: number) {
  // (a) button box right edge within the viewport.
  expect(geom.box.x + geom.box.width, 'button right edge within viewport').toBeLessThanOrEqual(
    viewportWidth + 1,
  )
  // (b) button root does not overflow internally (a squeezed/wrapped button clips text).
  expect(geom.scrollWidth, 'button scrollWidth <= clientWidth').toBeLessThanOrEqual(
    geom.clientWidth + 1,
  )
  expect(geom.scrollHeight, 'button scrollHeight <= clientHeight').toBeLessThanOrEqual(
    geom.clientHeight + 1,
  )
  // (c) every painted label rect lies inside the button box (glyphs not clipped).
  expect(geom.labelRects.length, 'label has painted text rects').toBeGreaterThan(0)
  for (const [i, rect] of geom.labelRects.entries()) {
    expect(contains(geom.box, rect), `label rect ${i} painted within the button box`).toBe(true)
  }
}

test.describe('mobile button label overflow (#86)', () => {
  // Activate German BEFORE any app script runs so main.ts (main.ts:19) boots into `de`.
  // Scoped to this describe's pages; not written back to the shared storageState.
  test.beforeEach(async ({ page }: { page: Page }) => {
    await page.addInitScript(() => localStorage.setItem('teedy-locale', 'de'))
  })

  test('tag create button ("Erstellen") stays within its row and never clips its label', async ({
    page,
  }) => {
    await page.goto('/#/tag')
    // Gate on the German heading so measurement waits for the `de` chunk to swap in.
    await expect(page.getByRole('heading', { name: 'Tag erstellen' })).toBeVisible()

    // The second `.create-row` is the Select + submit-button row (the first holds the
    // colour picker + name input).
    const createRow = page.locator('.create-row').nth(1)
    const button = createRow.locator('.p-button')
    await expect(button).toBeVisible()
    // Confirm it is the create button in German (guards against a selector drift).
    await expect(button.locator('.p-button-label')).toHaveText('Erstellen')

    // ONE atomic layout snapshot of the whole row (row + select + button + label rects),
    // read at a single scroll position. Measuring the container and its children in
    // separate calls risks Playwright's auto-scroll shifting the viewport between reads.
    const geom = await createRow.evaluate((row) => {
      const box = (el: Element) => {
        const r = el.getBoundingClientRect()
        return { x: r.x, y: r.y, width: r.width, height: r.height }
      }
      const probeButton = (btn: Element) => {
        const b = btn.getBoundingClientRect()
        const label = btn.querySelector('.p-button-label')
        let labelRects: { x: number; y: number; width: number; height: number }[] = []
        let labelText: string | null = null
        if (label) {
          labelText = label.textContent
          const range = document.createRange()
          range.selectNodeContents(label)
          labelRects = Array.from(range.getClientRects()).map((x) => ({
            x: x.x,
            y: x.y,
            width: x.width,
            height: x.height,
          }))
        }
        return {
          box: { x: b.x, y: b.y, width: b.width, height: b.height },
          scrollWidth: (btn as HTMLElement).scrollWidth,
          clientWidth: (btn as HTMLElement).clientWidth,
          scrollHeight: (btn as HTMLElement).scrollHeight,
          clientHeight: (btn as HTMLElement).clientHeight,
          labelText,
          labelRects,
        }
      }
      return {
        rowBox: box(row),
        selectBox: box(row.querySelector('.p-select')!),
        button: probeButton(row.querySelector('.p-button')!),
        viewportWidth: window.innerWidth,
      }
    })

    assertButtonWithinAndLabelUnclipped(geom.button, geom.viewportWidth)
    // (row containment) button box contained by its own .create-row.
    expect(contains(geom.rowBox, geom.button.box), 'button box contained by its .create-row').toBe(
      true,
    )
    // (e) the Select is also contained by the row (the row is not overrun).
    expect(contains(geom.rowBox, geom.selectBox), 'Select box contained by its .create-row').toBe(
      true,
    )
  })

  test('metadata "Feld hinzufügen" button fits without clipping or crowding the heading', async ({
    page,
  }) => {
    await page.goto('/#/settings/metadata')
    await expect(page.getByRole('heading', { name: 'Benutzerdefinierte Metadaten' })).toBeVisible()

    const button = page.locator('.section-header .p-button')
    await expect(button).toBeVisible()
    await expect(button.locator('.p-button-label')).toHaveText('Feld hinzufügen')

    // ONE atomic snapshot of the section header: the heading/description wrapper (first
    // child div) and the add-field button, measured at a single scroll position.
    const geom = await page.locator('.section-header').evaluate((header) => {
      const box = (el: Element) => {
        const r = el.getBoundingClientRect()
        return { x: r.x, y: r.y, width: r.width, height: r.height }
      }
      const probeButton = (btn: Element) => {
        const b = btn.getBoundingClientRect()
        const label = btn.querySelector('.p-button-label')
        let labelRects: { x: number; y: number; width: number; height: number }[] = []
        let labelText: string | null = null
        if (label) {
          labelText = label.textContent
          const range = document.createRange()
          range.selectNodeContents(label)
          labelRects = Array.from(range.getClientRects()).map((x) => ({
            x: x.x,
            y: x.y,
            width: x.width,
            height: x.height,
          }))
        }
        return {
          box: { x: b.x, y: b.y, width: b.width, height: b.height },
          scrollWidth: (btn as HTMLElement).scrollWidth,
          clientWidth: (btn as HTMLElement).clientWidth,
          scrollHeight: (btn as HTMLElement).scrollHeight,
          clientHeight: (btn as HTMLElement).clientHeight,
          labelText,
          labelRects,
        }
      }
      return {
        headingBox: box(header.querySelector(':scope > div')!),
        button: probeButton(header.querySelector('.p-button')!),
        viewportWidth: window.innerWidth,
      }
    })

    assertButtonWithinAndLabelUnclipped(geom.button, geom.viewportWidth)
    // (d) button box disjoint from the heading/description wrapper (no overlap).
    expect(
      disjoint(geom.button.box, geom.headingBox),
      'button does not overlap the heading/description',
    ).toBe(true)
  })
})

// #177 regression gate (same failure class as #86/#67, different control): the header action row
// holds five icon-only buttons plus the username. Every icon button is pinned `flex-shrink: 0`, so
// the username is the only elastic item — and a flex item's default `min-width: auto` made BOTH the
// username and its container refuse to shrink. A long username then painted over the Logout button
// and swallowed its clicks (Logout was unclickable at 393px; first caught by two-factor.spec, whose
// generated usernames are long).
//
// The username must be REAL: it is rendered from the auth store, so overwriting the text node from
// the page is reverted on the next Vue render and the assertion would pass vacuously. So this
// creates a genuinely long-named user and drives the header as that user.
//
// Measured against the pre-fix CSS, the condition this trips first is the VIEWPORT bound, not the
// overlap: the username claimed its full intrinsic width and pushed Logout's right edge to 429.8px
// in a 393px viewport. The click at the end is the user-visible consequence (Playwright then
// reports the username span intercepting the button's pointer events).
test('#177 a long username never overlaps the header Logout button', async ({ page, browser, cleanup }) => {
  // 30 chars — comfortably longer than the bar can fit beside five icon buttons at 393px.
  const username = `longname${Date.now()}`.slice(0, 30).toLowerCase()
  const password = 'LongNamePass123'

  await page.goto('/#/settings/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#add-user-name').fill(username)
  await dialog.locator('#add-user-email').fill(`${username}@example.com`)
  await dialog.locator('#add-user-pass').fill(password)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('User created')).toBeVisible()

  const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const userPage = await ctx.newPage()
  // FIFO, in the order the original teardown ran. This user seeds no documents — only the
  // account and its context need purging.
  cleanup.defer('close the long-username context', () => ctx.close())
  cleanup.defer('delete the long-username user', () => deleteUser(page, username))

  await login(userPage, username, password)

  const nameLocator = userPage.locator('.user-name')
  // The rendered name really is the long one (guards against a vacuous pass).
  await expect(nameLocator).toHaveText(username)

  const logout = userPage.getByRole('button', { name: 'Logout' })
  await expect(logout).toBeVisible()

  const nameBox = await nameLocator.boundingBox()
  const logoutBox = await logout.boundingBox()
  expect(nameBox, 'username has a box').not.toBeNull()
  expect(logoutBox, 'logout has a box').not.toBeNull()

  // No overlap — an overlapping span intercepts the button's pointer events.
  expect(disjoint(nameBox!, logoutBox!), 'username must not overlap Logout').toBe(true)

  // The button stays on screen...
  const vw = userPage.viewportSize()!.width
  expect(logoutBox!.x, 'logout not off-screen left').toBeGreaterThanOrEqual(-TOL)
  expect(logoutBox!.x + logoutBox!.width, 'logout right edge within viewport').toBeLessThanOrEqual(vw + TOL)

  // ...and is genuinely clickable, which is the user-visible property that broke.
  await logout.click()
  await expect(userPage).toHaveURL(/#\/login/)
})
