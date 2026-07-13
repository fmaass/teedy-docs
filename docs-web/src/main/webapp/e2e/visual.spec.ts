import { test, expect, type Page, type Locator, type APIRequestContext } from './fixtures'
import { isMobileViewport } from './helpers'

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
// `mcr.microsoft.com/playwright:v1.61.1-jammy` container (Ubuntu 22.04 Jammy fonts),
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
      test(`document list [${locale}]`, async ({ page, request }) => {
        await ensureCorpus(request)
        await page.goto('/#/document')
        await setLocale(page, locale)
        // Wait on the shell anchor visible at BOTH viewports (Logout header button).
        // NB: index.logout is "Logout" in de.json too (untranslated), so it's stable
        // across locales — a good locale-agnostic settle anchor.
        await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible()
        await expect(page.getByText('ACME invoice 2026-0042', { exact: true }).first()).toBeVisible()
        await freeze(page)
        await expect(page).toHaveScreenshot(`document-list-${locale}.png`, { fullPage: true })
      })

      test(`gallery view [${locale}]`, async ({ page, request }) => {
        await ensureCorpus(request)
        // Seed the view-mode preference so the list boots straight into gallery.
        await page.goto('/#/document')
        await page.evaluate(() => localStorage.setItem('teedy_document_view_mode', 'gallery'))
        await setLocale(page, locale)
        await expect(page.locator('.doc-gallery')).toBeVisible()
        await expect(
          page.locator('.doc-gallery').getByText('ACME invoice 2026-0042', { exact: true }).first(),
        ).toBeVisible()
        await freeze(page)
        await expect(page).toHaveScreenshot(`gallery-${locale}.png`, { fullPage: true })
        // Leave the preference as list so unrelated specs are unaffected.
        await page.evaluate(() => localStorage.setItem('teedy_document_view_mode', 'list'))
      })

      test(`document view / slide-over long title [${locale}]`, async ({ page, request }) => {
        await ensureCorpus(request)
        await page.goto('/#/document')
        await setLocale(page, locale)
        await page.getByRole('cell', { name: LONG_TITLE }).first().click()
        const slideOver = page.getByRole('dialog')
        await expect(slideOver).toBeVisible()
        await expect(page.locator('.slide-over-title')).toHaveText(LONG_TITLE)
        await freeze(page)
        // The slide-over is the subject; capture it (its own box) for a tight,
        // viewport-stable frame at both sizes.
        await expect(slideOver).toHaveScreenshot(`slide-over-long-title-${locale}.png`)
      })

      test(`settings hub [${locale}]`, async ({ page }) => {
        await page.goto('/#/settings')
        await setLocale(page, locale)
        await expect(
          page.getByRole('heading', { name: locale === 'de' ? 'Einstellungen' : 'Settings' }),
        ).toBeVisible()
        await freeze(page)
        await expect(page).toHaveScreenshot(`settings-hub-${locale}.png`, { fullPage: true })
      })

      test(`settings config form [${locale}]`, async ({ page }) => {
        await page.goto('/#/settings/config')
        await setLocale(page, locale)
        // The Config screen renders section headings (h2). Wait on the SMTP/email
        // section which is present regardless of env-managed state.
        await expect(page.locator('.settings-config, form, .p-card').first()).toBeVisible()
        await expect(page.locator('h2').first()).toBeVisible()
        await freeze(page)
        await expect(page).toHaveScreenshot(`settings-config-${locale}.png`, { fullPage: true })
      })

      test(`rich description editor with ordered+unordered lists [${locale}]`, async ({ page }) => {
        await page.goto('/#/document/add')
        await setLocale(page, locale)
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
        await page.goto('/#/document')
        await setLocale(page, locale)
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
    await page.goto('/#/document')
    await setLocale(page, 'de')
    const vw = page.viewportSize()!.width
    // The always-visible header actions in German (Papierkorb=Trash, Über=About;
    // Logout stays "Logout" — untranslated). Each must render inside the viewport.
    for (const name of ['Papierkorb', 'Über', 'Logout']) {
      const btn = page.getByRole('button', { name, exact: true })
      await expect(btn, `German header action "${name}" visible`).toBeVisible()
      const box = await btn.boundingBox()
      expect(box, `"${name}" has a box`).not.toBeNull()
      expect(box!.x, `"${name}" not off-screen left`).toBeGreaterThanOrEqual(-1)
      expect(box!.x + box!.width, `"${name}" right edge within viewport`).toBeLessThanOrEqual(vw + 1)
    }
  })

  test('German nav labels stay within their nav container', async ({ page }) => {
    await page.goto('/#/document')
    await setLocale(page, 'de')
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
    await page.goto('/#/settings')
    await setLocale(page, 'de')
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
