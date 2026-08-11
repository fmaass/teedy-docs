import { test, expect, type Page, type Locator, type APIRequestContext } from './fixtures'
import { isMobileViewport, ROUTE_ROOT, expectRouteReady, gotoRaw } from './helpers'

// REAL visual-regression + multi-language (EN/DE) gate over the key screens most
// prone to layout / overflow, at BOTH viewports (the `desktop` and `mobile`
// Playwright projects) × BOTH locales (English + German). German UI strings are
// ~30% longer than English (the #1 overflow cause), so the German pass is the key
// glitch-catcher.
//
// This is a STANDING gate, not an E2E_VISUAL-gated soft check: the toHaveScreenshot
// calls run by default. Baselines are OS-namespaced by Playwright (`*-linux.png`);
// CI runs Linux, so ONLY the `*-linux.png` baselines are authoritative and committed.
// A screen with no committed Linux baseline fails loudly ("missing snapshot") — that
// is intentional, so a newly-added screen can't silently ship un-baselined. See
// e2e/COVERAGE.md for how the Linux baselines are generated (Playwright Docker image).
//
// Determinism: animations are disabled (config `toHaveScreenshot.animations`), a
// belt-and-braces stylesheet kills transitions/caret, and every dynamic region
// (running version badge, per-row + slide-over dates) is HIDDEN via visibility:hidden
// so a diff only ever reflects a real layout/CSS change — never the clock or the build
// number. (A hidden element keeps its layout box but has no pixels, so unlike a mask
// overlay it introduces no anti-aliased edge that could flake a tight diff.)

// --- Locale control ----------------------------------------------------------
// The app reads the persisted locale from localStorage key `teedy-locale` at boot
// (src/main.ts) and SettingsAccount writes the same key. Seeding it before a fresh
// navigation + reload is the most robust way to render a whole screen in a locale
// (no per-screen click into Settings). We seed on the real origin, then reload so
// main.ts picks it up on startup.
async function setLocale(page: Page, locale: 'en' | 'de'): Promise<void> {
  // The origin must exist in this context before localStorage is writable; a prior
  // goto in the caller guarantees it. Write then reload so main.ts' boot-time read
  // (savedLocale) applies the locale to the whole app.
  await page.evaluate((l) => localStorage.setItem('teedy-locale', l), locale)
  await page.reload()
}

// Kill every transition/animation and the blinking caret so a screenshot is a fully
// settled frame regardless of renderer timing. Complements the config-level
// `animations: 'disabled'` (which freezes CSS/Web animations at capture) by also
// zeroing durations for any JS-driven or delayed transition.
// CSS selectors whose CONTENT legitimately varies run-to-run and would otherwise
// produce a false diff. We HIDE these with `visibility:hidden` (preserves layout box,
// renders nothing) rather than Playwright's `mask` overlay — a mask paints an
// anti-aliased box whose sub-pixel edges drift a few px between runs and flake a tight
// diff. A hidden element has no pixels to compare, so the region is fully deterministic
// while the surrounding layout is unchanged.
//   * .doc-meta / .meta-val — per-row + slide-over create-date (formatDate of a
//     runtime-created doc); .meta-val also hides language/creator (harmless, not the
//     screen's subject).
//   * .about-version — the live running-version badge (e.g. "v3.6.0").
const VOLATILE_HIDE_CSS =
  '.doc-meta, .meta-val, .about-version { visibility: hidden !important; }'

async function freeze(page: Page): Promise<void> {
  await page
    .addStyleTag({
      content:
        '*, *::before, *::after { transition: none !important; animation: none !important; caret-color: transparent !important; }' +
        VOLATILE_HIDE_CSS,
    })
    .catch(() => {})
  // Icon glyphs come from the async `primeicons` @font-face (src/main.ts). A capture taken
  // before that font finishes decoding paints the glyph as an empty box and shifts the label
  // beside it, so any icon-bearing control — the pi-key button in DefaultPasswordBanner, the
  // header action icons — rendered differently run to run and left ~half the baselines
  // byte-unstable even after the DataTable-overlay fix (#267). Wait for every font to finish
  // loading before the shot; document.fonts.ready settles once all in-flight faces resolve.
  await page.evaluate(async () => {
    await document.fonts.ready
  }).catch(() => {})
  // Drop focus so a racy autofocus ring cannot be painted in one capture and missing in the
  // next: PrimeVue's Dialog focuses its close button on show (focusOnShow), and the About
  // dialog's ✕ ring appeared only sometimes. Real users still get focus rings — this only
  // settles the screenshot to a single deterministic state.
  await page.evaluate(() => {
    ;(document.activeElement as HTMLElement | null)?.blur()
  }).catch(() => {})
}

// --- Below-the-fold sight (#259) ---------------------------------------------
// The app shell is a fixed 100vh layout whose scrolling happens INSIDE `.app-content`
// (AppLayout.vue, `overflow-y: auto`, wraps the router-view of every authenticated
// route). The PAGE therefore never exceeds the viewport, so `fullPage: true` captures
// exactly the viewport and everything below the fold is invisible to this gate — commit
// ffc31d5f added a seventh admin card to the settings hub and the baselines still matched
// byte for byte (#259). An element screenshot cannot rescue it: a Playwright element shot
// of an `overflow: auto` box captures its CLIENT box, not its scrollHeight.
//
// The mechanism that DOES work is a taller viewport for the tests whose surface is taller
// than the fold: size the viewport so the container no longer scrolls, and the ordinary
// capture then contains the whole surface. Only the HEIGHT changes — the width (1280
// desktop / 393 mobile) and the device scale factor are what the projects' layout and
// baselines depend on, and `setViewportSize` leaves both alone.
//
// Measured `.app-content` scrollHeight at the standard viewport (2026-08-11, admin,
// seeded instance; clientHeight there is 675 desktop / 682 mobile — the fixed header
// takes 45px of the 720/727 viewport):
//   settings-hub     desktop en 1326 / de 1376    mobile en 1593 / de 1689
//   settings-config  desktop en 1854 / de 2034    mobile en 2164 / de 2315
// The heights below are the worst locale + those 45px + ~15% headroom, i.e. room for a
// few more rows before the guard below has to be revisited. They are deliberately NOT
// larger: a taller frame dilutes every diff ratio (the same reworded line is a smaller
// fraction of a bigger image), which is the sensitivity this gate is calibrated on.
const TALL_VIEWPORT = {
  settingsHub: { desktop: 1600, mobile: 2000 },
  settingsConfig: { desktop: 2400, mobile: 2700 },
} as const

// Grow the CURRENT project's viewport to `heights` for this test only (Playwright gives
// each test a fresh context, so nothing leaks to the next one). Called BEFORE the first
// navigation so the surface is laid out at its final size from the first paint — there is
// no post-resize reflow left to settle out, and the per-test route-ready + content
// assertions + freeze() (fonts.ready, blur) that follow are the settle before capture.
async function growViewport(page: Page, heights: { desktop: number; mobile: number }): Promise<void> {
  const width = page.viewportSize()!.width
  await page.setViewportSize({ width, height: isMobileViewport(page) ? heights.mobile : heights.desktop })
}

// Assert that the BOTTOM EDGE of the surface's structurally-last element lies inside the
// viewport. `toBeVisible()` is not enough for this job: Playwright counts an element as
// visible when it has a non-empty box, whether or not that box is inside the frame the
// screenshot captures — so a cropped capture would still pass. The bottom edge is the
// thing the taller viewport is supposed to buy, so it is the thing asserted.
// Runs AFTER freeze() so it measures the same layout the capture takes.
async function expectBottomInFrame(page: Page, anchor: Locator, label: string): Promise<void> {
  const box = await anchor.boundingBox()
  expect(box, `${label}: the bottom anchor has a layout box`).not.toBeNull()
  expect(
    Math.ceil(box!.y + box!.height),
    `${label}: the bottom of the surface must lie inside the captured frame (#259) — ` +
      `the last element ends at y=${Math.ceil(box!.y + box!.height)} in a ${page.viewportSize()!.height}px viewport`,
  ).toBeLessThanOrEqual(page.viewportSize()!.height)
}

// LOUD-FAIL guard for the mechanism above: the taller viewport only buys below-fold sight
// while the surface actually FITS it. If the screen grows past that height the container
// starts scrolling again and the capture silently crops — the exact #259 blindness, and a
// stale baseline would keep passing. Asserting "the scroll container has nothing left to
// scroll" makes that failure loud and self-explaining instead. Called AFTER freeze() and
// immediately before the capture: freeze() waits on document.fonts.ready, and a font that
// lands late reflows the content, so a pre-freeze reading would not be the geometry the
// screenshot actually takes.
async function expectSurfaceFitsViewport(page: Page, screen: string): Promise<void> {
  const box = await page.locator('.app-content').evaluate((el) => ({
    scrollHeight: el.scrollHeight,
    clientHeight: el.clientHeight,
  }))
  expect(
    box.scrollHeight,
    `${screen}: the surface must fit the taller viewport, else the capture crops below the fold (#259). ` +
      `.app-content scrollHeight=${box.scrollHeight} > clientHeight=${box.clientHeight}: raise TALL_VIEWPORT.${screen} ` +
      `and regenerate this screen's baselines.`,
  ).toBeLessThanOrEqual(box.clientHeight)
}

// Wait for the PrimeVue DataTable loading overlay to be GONE before capturing.
// The document list renders `<DataTable :loading="isLoading">`; PrimeVue mounts a
// `.p-datatable-mask` (a white overlay + spinner) via `v-if="loading"` while that flag
// is set. setLocale() does a full `page.reload()`, so the post-reload mount refetches
// the list from scratch and the mask flashes over the table; a `fullPage` screenshot
// caught that transient overlay ~34% (en-desktop) / ~44% (en-mobile) of the time, and
// the committed baseline was itself one of those overlay frames (#267). The list query
// uses `placeholderData: keepPreviousData`, so `isLoading` is monotonic — true only for
// the first fetch of a mount, false forever after — which means the mask, once detached,
// never returns for that page. Waiting for it to detach is therefore a permanent settle,
// not a race window. Mirrors move.spec.ts waiting `.p-dialog-mask` down to count 0.
async function settleDataTable(page: Page): Promise<void> {
  await expect(
    page.locator('.p-datatable-mask'),
    'the DataTable loading overlay detached (the list finished loading — #267)',
  ).toHaveCount(0)
}

// --- Deterministic seed corpus ----------------------------------------------
// A tiny fixed corpus so the list / gallery / slide-over screens render real rows.
// Titles are STABLE (not unique()) so the screenshot content is byte-stable across
// runs; the seed is idempotent (delete-then-create by exact title) so a re-run does
// not accumulate duplicates that would shift the layout.
const SEED_DOCS = [
  { title: 'ACME invoice 2026-0042', tag: 'invoice', color: '#e67e22' },
  { title: 'Office lease agreement', tag: 'contract', color: '#2aabd2' },
  { title: 'Q2 financial report', tag: 'report', color: '#27ae60' },
]
// A single very-long-title doc for the slide-over long-title screen (the #68 area).
// Kept under the backend 100-char title cap. Stable text for a stable screenshot.
const LONG_TITLE =
  'A Very Long Document Title That Exercises The Slide-Over Header Truncation And Wrapping'

// Purge EVERY document so the corpus is a deterministic, fixed set regardless of prior
// DB state. This is the key to a stable list/gallery screenshot: without it, re-runs
// (or other specs' leftovers) accumulate rows that shift the layout and blow the diff.
// On a fresh CI container this is a no-op; on a re-used dev container it resets cleanly.
async function purgeAllDocuments(request: APIRequestContext): Promise<void> {
  // Delete in a bounded loop until the list is empty (list is paginated at 100).
  for (let guard = 0; guard < 50; guard++) {
    const res = await request.get('/api/document/list?limit=100&sort_column=3&asc=false')
    if (!res.ok()) return
    const docs = (await res.json()).documents ?? []
    if (docs.length === 0) return
    for (const d of docs as { id: string }[]) {
      await request.delete(`/api/document/${d.id}`)
    }
  }
}

async function apiEnsureTag(request: APIRequestContext, name: string, color: string): Promise<string> {
  // Reuse an existing same-name tag if present (tag names are unique per user), else create.
  const list = await request.get('/api/tag/list')
  if (list.ok()) {
    const existing = ((await list.json()).tags ?? []).find((t: { name: string; id: string }) => t.name === name)
    if (existing) return existing.id
  }
  const res = await request.put('/api/tag', { form: { name, color } })
  expect(res.ok(), `ensure tag ${name}`).toBeTruthy()
  return (await res.json()).id as string
}

async function apiCreateDoc(
  request: APIRequestContext,
  title: string,
  opts: { tagIds?: string[]; description?: string } = {},
): Promise<string> {
  const body = new URLSearchParams([
    ['title', title],
    ['language', 'eng'],
    ...(opts.description ? ([['description', opts.description]] as [string, string][]) : []),
    ...(opts.tagIds ?? []).map((id): [string, string] => ['tags', id]),
  ])
  const res = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: body.toString(),
  })
  expect(res.ok(), `create document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

// Seed once per worker before the visual screens run. Serial (workers=1) so this is a
// simple guard, not a lock. Docs are created SEQUENTIALLY so their millisecond
// `create_date`s are strictly increasing → the default `create_date DESC` list order
// is deterministic (LONG_TITLE last-created shows first, then report/lease/invoice).
let seeded = false
async function ensureCorpus(request: APIRequestContext): Promise<void> {
  if (seeded) return
  await purgeAllDocuments(request)
  for (const d of SEED_DOCS) {
    const tagId = await apiEnsureTag(request, d.tag, d.color)
    await apiCreateDoc(request, d.title, { tagIds: [tagId] })
  }
  await apiCreateDoc(request, LONG_TITLE)
  seeded = true
}

// The pixel-comparison block carries the `@visual` grep tag so CI can route it to the
// OS that its committed `*-linux.png` baselines were generated on. Playwright baselines
// are renderer/font-sensitive: the baselines here were produced in the
// `mcr.microsoft.com/playwright:v1.62.1-jammy` container (Ubuntu 22.04 Jammy fonts),
// but the default host e2e run happens on the GitHub `ubuntu-latest` (Noble) runner,
// whose different system fonts would make the pixel diffs fail. So:
//   * the HOST run (scripts/e2e-run.sh, no CI-visual flag) EXCLUDES @visual
//     (`--grep-invert @visual`) — the deterministic FUNCTIONAL specs still run there;
//   * a dedicated CI job runs ONLY @visual INSIDE the jammy container against the same
//     booted RC image (scripts/e2e-run.sh E2E_VISUAL_ONLY=1), the exact environment the
//     baselines match.
// The functional German-overflow block below is NOT tagged @visual — it is
// environment-independent (geometry, no baselines) and runs on the host as usual.
test.describe('@visual visual regression — key screens × {desktop,mobile} × {en,de}', () => {
  test.beforeEach(async ({ page }) => {
    await freeze(page)
  })

  // Run the SAME screen twice (en, de) inside one test so the two shots share setup.
  for (const locale of ['en', 'de'] as const) {
    test.describe(`locale=${locale}`, () => {
      // document list + gallery deliberately KEEP the standard project viewport: the seed
      // corpus is 4 documents, which fits above the fold at both sizes, so the taller-
      // viewport treatment (#259) would only churn their baselines and buy no coverage.
      // If the corpus ever grows past the fold, give them TALL_VIEWPORT entries + the
      // expectSurfaceFitsViewport guard like the settings screens.
      test(`document list [${locale}]`, async ({ page, request }) => {
        await ensureCorpus(request)
        await gotoRaw(page, '/#/document')
        await setLocale(page, locale)
        await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
        // Wait on the shell anchor visible at BOTH viewports (Logout header button).
        // NB: index.logout is "Logout" in de.json too (untranslated), so it's stable
        // across locales — a good locale-agnostic settle anchor.
        await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible()
        await expect(page.getByText('ACME invoice 2026-0042', { exact: true }).first()).toBeVisible()
        await settleDataTable(page)
        await freeze(page)
        await expect(page).toHaveScreenshot(`document-list-${locale}.png`, { fullPage: true })
      })

      test(`gallery view [${locale}]`, async ({ page, request }) => {
        await ensureCorpus(request)
        // Seed the view-mode preference so the list boots straight into gallery.
        await gotoRaw(page, '/#/document')
        await page.evaluate(() => localStorage.setItem('teedy_document_view_mode', 'gallery'))
        await setLocale(page, locale)
        await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
        await expect(page.locator('.doc-gallery')).toBeVisible()
        await expect(
          page.locator('.doc-gallery').getByText('ACME invoice 2026-0042', { exact: true }).first(),
        ).toBeVisible()
        await freeze(page)
        await expect(page).toHaveScreenshot(`gallery-${locale}.png`, {
          fullPage: true,
          // The ONE per-screen opt-out from the calibrated global tolerance (#259). At the
          // DESKTOP viewport this screen renders exactly 70 differing pixels of 921,600
          // (0.000076) against its committed baseline in every run — scattered glyph/icon
          // AA from the session the baseline was generated in, stable rather than jittery,
          // and above the 40 ppm global. 0.0002 is ~2.6x that measured floor and still ~300x
          // tighter than the 0.06 this gate used to run at. Mobile is NOT excepted: it
          // measures 0 differing pixels and keeps the global value.
          ...(isMobileViewport(page) ? {} : { maxDiffPixelRatio: 0.0002 }),
        })
        // Leave the preference as list so unrelated specs are unaffected.
        await page.evaluate(() => localStorage.setItem('teedy_document_view_mode', 'list'))
      })

      test(`document view / slide-over long title [${locale}]`, async ({ page, request }) => {
        await ensureCorpus(request)
        await gotoRaw(page, '/#/document')
        await setLocale(page, locale)
        await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
        await page.getByRole('cell', { name: LONG_TITLE }).first().click()
        const slideOver = page.getByRole('dialog')
        await expect(slideOver).toBeVisible()
        await expect(page.locator('.slide-over-title')).toHaveText(LONG_TITLE)
        await freeze(page)
        // The slide-over is the subject; capture it (its own box) for a tight,
        // viewport-stable frame at both sizes.
        await expect(slideOver).toHaveScreenshot(`slide-over-long-title-${locale}.png`)
      })

      // The settings hub is ~2x the fold at both viewports (an admin sees 16 link cards:
      // 2 personal + 14 across the three admin groups), so it captures at a TALLER
      // viewport — see TALL_VIEWPORT (#259).
      test(`settings hub [${locale}]`, async ({ page }) => {
        await growViewport(page, TALL_VIEWPORT.settingsHub)
        await gotoRaw(page, '/#/settings')
        await setLocale(page, locale)
        await expectRouteReady(page, '/#/settings', ROUTE_ROOT.settingsHub)
        await expect(
          page.getByRole('heading', { name: locale === 'de' ? 'Einstellungen' : 'Settings' }),
        ).toBeVisible()
        // The LAST card of the LAST admin group — ~600px below the standard fold.
        const lastHubCard = page
          .locator('.settings-hub')
          .getByRole('link', { name: locale === 'de' ? 'Überwachung' : 'Monitoring' })
        await expect(lastHubCard).toBeVisible()
        await freeze(page)
        await expectBottomInFrame(page, lastHubCard, 'settingsHub')
        await expectSurfaceFitsViewport(page, 'settingsHub')
        await expect(page).toHaveScreenshot(`settings-hub-${locale}.png`, { fullPage: true })
      })

      // The Config form is ~3x the fold at both viewports, so it too captures at a TALLER
      // viewport — see TALL_VIEWPORT (#259).
      test(`settings config form [${locale}]`, async ({ page }) => {
        await growViewport(page, TALL_VIEWPORT.settingsConfig)
        await gotoRaw(page, '/#/settings/config')
        await setLocale(page, locale)
        await expectRouteReady(page, '/#/settings/config', ROUTE_ROOT.settingsConfig)
        // The Config screen renders section headings (h2). Wait on the SMTP/email
        // section which is present regardless of env-managed state.
        await expect(page.locator('.settings-config, form, .p-card').first()).toBeVisible()
        await expect(page.locator('h2').first()).toBeVisible()
        // The structurally LAST element of the form: the closing hint of the maintenance
        // ("danger zone") card, which is the last child of the last card of
        // SettingsConfig.vue. Class-based, so it is the same anchor in both locales, and
        // it sits ~1100px below the standard fold. (The page renders exactly ONE h2 — the
        // title at the very top — so an `h2` locator can never be a bottom anchor here.)
        const lastConfigElement = page.locator('.config-settings .danger-zone .clean-storage-hint')
        await expect(lastConfigElement).toBeVisible()
        await freeze(page)
        await expectBottomInFrame(page, lastConfigElement, 'settingsConfig')
        await expectSurfaceFitsViewport(page, 'settingsConfig')
        await expect(page).toHaveScreenshot(`settings-config-${locale}.png`, { fullPage: true })
      })

      test(`rich description editor with ordered+unordered lists [${locale}]`, async ({ page }) => {
        await gotoRaw(page, '/#/document/add')
        await setLocale(page, locale)
        await expectRouteReady(page, '/#/document/add', ROUTE_ROOT.documentEdit)
        await expect(page.locator('#edit-desc .ql-editor')).toBeVisible()
        const editor = page.locator('#edit-desc .ql-editor')
        // Build an ordered list then an unordered list — the #70 area (double-marker
        // bug). Type items, selecting the list format from the toolbar for each block.
        await editor.click()
        await editor.type('First ordered item')
        await page.locator('#edit-desc button.ql-list[value="ordered"]').click()
        await editor.press('Enter')
        await editor.type('Second ordered item')
        await editor.press('Enter')
        await editor.press('Enter') // exit the list
        await editor.type('First bullet item')
        await page.locator('#edit-desc button.ql-list[value="bullet"]').click()
        await editor.press('Enter')
        await editor.type('Second bullet item')
        // Quill 2 renders BOTH ordered and bullet lists as <ol> with a per-item
        // `data-list` attribute (see RichDescriptionEditor #70 note) — there is NO
        // <ul>. Assert the two list kinds via that attribute.
        await expect(editor.locator('li[data-list="ordered"]').first()).toBeVisible()
        await expect(editor.locator('li[data-list="bullet"]').first()).toBeVisible()
        await freeze(page)
        // Capture the editor card (toolbar + list content) — the subject of #70.
        const editorRoot = page.locator('#edit-desc')
        await expect(editorRoot).toHaveScreenshot(`rich-editor-lists-${locale}.png`)
      })

      test(`about dialog [${locale}]`, async ({ page }) => {
        await gotoRaw(page, '/#/document')
        await setLocale(page, locale)
        await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
        // The About header action renders at both viewports (see responsive.spec).
        await page.getByRole('button', { name: locale === 'de' ? 'Über' : 'About', exact: true }).click()
        const dialog = page.getByRole('dialog')
        await expect(dialog).toBeVisible()
        // "What's new in 3.x" heading proves the dialog body rendered.
        await expect(dialog.locator('.about-heading')).toBeVisible()
        await freeze(page)
        await expect(dialog).toHaveScreenshot(`about-dialog-${locale}.png`)
      })
    })
  }
})

// --- FUNCTIONAL German-overflow assertions (hard gate) -----------------------
// A German label/button that overflows its container is a REAL bug (German strings
// run ~30% longer). These are environment-independent geometry checks — they run at
// BOTH viewports and are the HARD gate (unlike the pixel screenshots above). If one
// fails, the app has a genuine German-overflow bug to fix.
test.describe('German layout — no overflow (functional)', () => {
  // Assert an element's box lies within its container's box (with a 1px tolerance for
  // sub-pixel rounding). A child wider/taller than its container = overflow.
  async function expectWithinContainer(child: Locator, container: Locator, label: string): Promise<void> {
    const cb = await child.boundingBox()
    const pb = await container.boundingBox()
    expect(cb, `${label}: child has a box`).not.toBeNull()
    expect(pb, `${label}: container has a box`).not.toBeNull()
    expect(cb!.x, `${label}: not off left of container`).toBeGreaterThanOrEqual(pb!.x - 1)
    expect(cb!.x + cb!.width, `${label}: right edge within container`).toBeLessThanOrEqual(pb!.x + pb!.width + 1)
  }

  test('German header action buttons stay within the viewport width', async ({ page }) => {
    await gotoRaw(page, '/#/document')
    await setLocale(page, 'de')
    await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
    const vw = page.viewportSize()!.width
    // The always-visible header actions in German (Aktivitätsverlauf=Activity history,
    // Papierkorb=Trash, Über=About; Logout stays "Logout" — untranslated). Each must render
    // inside the viewport. Every control added to this bar belongs in this list — a 5th icon
    // button is exactly the pressure this gate exists for (#177).
    for (const name of ['Aktivitätsverlauf', 'Papierkorb', 'Über', 'Logout']) {
      const btn = page.getByRole('button', { name, exact: true })
      await expect(btn, `German header action "${name}" visible`).toBeVisible()
      const box = await btn.boundingBox()
      expect(box, `"${name}" has a box`).not.toBeNull()
      expect(box!.x, `"${name}" not off-screen left`).toBeGreaterThanOrEqual(-1)
      expect(box!.x + box!.width, `"${name}" right edge within viewport`).toBeLessThanOrEqual(vw + 1)
    }
  })

  test('German nav labels stay within their nav container', async ({ page }) => {
    await gotoRaw(page, '/#/document')
    await setLocale(page, 'de')
    await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
    // The nav container is the desktop left panel or the mobile Drawer. The shared
    // openNav() helper hardcodes the English "Menu" hamburger label, but in German
    // the app (correctly) localizes it to "Menü" — so open the nav here in a
    // locale-aware way rather than via openNav. The footer nav links (Tags verwalten
    // = "Manage tags", Einstellungen = "Settings") must not overflow that container.
    let nav: Locator
    if (isMobileViewport(page)) {
      await page.getByRole('button', { name: 'Menü', exact: true }).click()
      nav = page.getByRole('dialog').filter({ has: page.locator('.mobile-panel-body') })
      await expect(nav).toBeVisible()
      // Wait for the Drawer's slide-in transform to settle to identity before reading
      // geometry — a mid-slide read catches a transiently negative x (the panel still
      // translated off-screen-left) and reports a false "overflow". Poll the panel's
      // computed transform until it is the identity matrix.
      const panel = nav.locator('xpath=self::*[contains(@class,"p-drawer")] | .//*[contains(@class,"p-drawer")]').first()
      const target = (await panel.count()) ? panel : nav
      await expect
        .poll(async () => target.evaluate((el) => getComputedStyle(el as HTMLElement).transform), {
          message: 'drawer transform settled to identity',
          intervals: [50, 100, 100, 200],
        })
        .toMatch(/^(none|matrix\(1, 0, 0, 1, 0, 0\))$/)
    } else {
      nav = page.locator('aside.left-panel')
    }
    for (const name of ['Tags verwalten', 'Einstellungen']) {
      const link = nav.getByRole('link', { name })
      if (!(await link.count())) continue
      await expect(link.first(), `German nav link "${name}" visible`).toBeVisible()
      await expectWithinContainer(link.first(), nav, `nav link "${name}"`)
    }
  })

  test('German settings-hub section cards do not overflow the page', async ({ page }) => {
    await gotoRaw(page, '/#/settings')
    await setLocale(page, 'de')
    await expectRouteReady(page, '/#/settings', ROUTE_ROOT.settingsHub)
    await expect(page.getByRole('heading', { name: 'Einstellungen' })).toBeVisible()
    const vw = page.viewportSize()!.width
    // Each hub nav link card must sit within the viewport width (long German labels
    // like "Automatische Verschlagwortung" are the overflow risk here).
    const cards = page.locator('.hub-section a, .hub-section [role="link"]')
    const n = await cards.count()
    expect(n, 'settings hub has nav cards').toBeGreaterThan(0)
    for (let i = 0; i < n; i++) {
      const box = await cards.nth(i).boundingBox()
      if (!box) continue
      expect(box.x + box.width, `hub card ${i} right edge within viewport`).toBeLessThanOrEqual(vw + 1)
    }
  })
})

// --- #67: the document filter toolbar must fit its row (no horizontal overflow) ----
// The filter toolbar (.wf-filter-row: the workflow/favorites toggles, saved-filters, and
// the view-mode + page-size selects) overflowed a phone-width bar — the row scrolled
// sideways with the last control clipped off the right edge, and the "Saved filters"
// label wrapped onto two lines (reporter vmario89, #67). This is a geometry check,
// locale-independent, run at BOTH the desktop and mobile projects: it FAILS at the mobile
// viewport before the flex-wrap fix (the row's content is ~140px wider than its box) and
// PASSES once the controls wrap to a second row instead of spilling.
test.describe('Document filter toolbar — no horizontal overflow (#67)', () => {
  test('the filter toolbar does not scroll sideways at any viewport', async ({ page }) => {
    await gotoRaw(page, '/#/document')
    await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
    const row = page.locator('.wf-filter-row')
    await expect(row).toBeVisible()
    // scrollWidth > clientWidth means the controls spill past the row's box and it scrolls
    // horizontally — the clipped-control symptom. 1px tolerance for sub-pixel rounding.
    const overflow = await row.evaluate((el) => el.scrollWidth - el.clientWidth)
    expect(overflow, 'filter toolbar horizontal overflow (px)').toBeLessThanOrEqual(1)
  })

  // Guard for the two-button SavedFilters state: an active savable filter renders BOTH
  // "Saved filters" and "Save current". Pinning the row's children at natural width (so
  // they wrap as whole units) would leave that group rigid, and at a sub-phone width with
  // long German labels it spilled the row (~35px) even though the default toolbar did not.
  // The group now caps its width and wraps its own buttons. 320px is deliberately below the
  // 393px mobile project so this fails at the narrow edge before the SavedFilters wrap fix.
  test('the saved-filters group wraps rather than overflowing at 320px/de with a savable filter (#67)', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 780 })
    await gotoRaw(page, '/#/document?search=probe')
    await setLocale(page, 'de')
    await page.waitForSelector('.wf-filter-row')
    // Guard is only meaningful when the two-button state is actually rendered.
    await expect(page.locator('.saved-filters button')).toHaveCount(2)
    const row = page.locator('.wf-filter-row')
    const overflow = await row.evaluate((el) => el.scrollWidth - el.clientWidth)
    expect(overflow, 'filter toolbar overflow at 320px/de with a savable filter (px)').toBeLessThanOrEqual(1)
  })
})

// --- #272: the rows-per-page control gets its own full-width row on a phone -----------
// At phone width the "count / page" control (.per-page-select, the last child of
// .wf-filter-row) packed onto the same line as the view-mode toggle, spending width the
// document list needs (reporter vmario89, #272). The fix gives it flex-basis:100% below the
// app's existing 640px breakpoint (already used by DocumentEdit/DocumentView/SettingsMetadata)
// so it wraps to its own row and spans the full toolbar width; desktop (above 640px) is
// untouched. Geometry check, locale-independent, run at BOTH projects: it FAILS at the 393px
// mobile project before the fix (the select shares the toggle's line at its natural width) and
// PASSES once it takes its own full-width row. The desktop branch pins that the control keeps
// its natural width there, so the mobile rule can't leak upward.
test.describe('Rows-per-page control wraps to its own row on narrow viewports (#272)', () => {
  test('the per-page control takes a full-width row below the breakpoint, natural width above', async ({
    page,
  }) => {
    await gotoRaw(page, '/#/document')
    await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
    const row = page.locator('.wf-filter-row')
    await expect(row).toBeVisible()
    await expect(row.locator('.view-mode-toggle')).toBeVisible()
    await expect(row.locator('.per-page-select')).toBeVisible()

    // One atomic read: the toolbar row, the view-mode toggle and the page-size select, all at
    // a single layout so nothing shifts between measurements.
    const geom = await row.evaluate((el) => {
      const box = (target: Element) => {
        const r = target.getBoundingClientRect()
        return { x: r.x, y: r.y, width: r.width, height: r.height }
      }
      return {
        row: box(el),
        toggle: box(el.querySelector('.view-mode-toggle')!),
        select: box(el.querySelector('.per-page-select')!),
      }
    })

    if (isMobileViewport(page)) {
      // Its OWN row: the control sits below the view-mode toggle (top at or past the toggle's
      // bottom edge), not sharing its line. Before the fix the two share a line and this fails.
      expect(
        geom.select.y,
        'per-page control is on its own line below the view-mode toggle',
      ).toBeGreaterThanOrEqual(geom.toggle.y + geom.toggle.height - 2)
      // …and it spans the full toolbar width (flex-basis:100% on its own line).
      expect(
        geom.select.width,
        'per-page control spans the full toolbar width',
      ).toBeGreaterThanOrEqual(geom.row.width - 2)
    } else {
      // Desktop is unchanged: the control keeps its natural (small) width and never takes a
      // full-width row — pinned by assertion, not by inspection.
      expect(
        geom.select.width,
        'desktop per-page control keeps its natural width',
      ).toBeLessThan(geom.row.width / 2)
    }
  })
})
