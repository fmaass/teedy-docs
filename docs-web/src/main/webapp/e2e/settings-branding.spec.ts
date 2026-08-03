import { test, expect, type Locator, type Page, type APIRequestContext } from './fixtures'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import {
  ROUTE_ROOT,
  closeNav,
  confirmDanger,
  expectResponseOk,
  expectRouteReady,
  gotoRouteReady,
  openNav,
} from './helpers'

// #57 + #241 — the Branding settings section.
//
// #57 shipped a theme BACKEND (name / navbar colour / custom CSS / logo+background+favicon) and a
// display half (tab title + favicon), but no settings UI ever called a theme mutation and nothing
// ever loaded the compiled stylesheet — branding was reachable only by hand-crafting API calls.
// These specs drive the real screen against the real app and assert the EFFECT, not the request:
//
//   * the section is reachable from both the hub and the settings sidebar;
//   * an admin sets the app name and replaces the favicon entirely from the UI (the #57 contract);
//   * a brand colour derives the interface palette AND survives a preset-family switch and a
//     dark-mode toggle (the #241 regression that a family switch used to wipe);
//   * custom CSS actually styles the page, and custom JavaScript actually executes.
//
// Every test resets the theme through the API in a deferred cleanup: this is INSTANCE-WIDE state
// on a shared app container, so anything left behind would follow the rest of the suite around.

const FIXTURES = join(dirname(fileURLToPath(import.meta.url)), 'fixtures')

const BRAND_COLOR = '#ff5722'
// Targets an element the Branding screen itself renders, and only through `outline-color` — a
// property nothing in the app sets, so a match cannot come from anywhere but the custom CSS, and
// no layout shifts.
const PROBE_CSS = '.branding-settings h2 { outline-color: rgb(1, 2, 3); }'
const PROBE_JS = "window.__teedyBrandingProbe = 'executed';"

/**
 * Reset every branding surface back to the bundled defaults. Driven over the API rather than the
 * UI so a failed assertion mid-test still leaves the instance clean for the next spec.
 */
async function resetBranding(request: APIRequestContext): Promise<void> {
  // Empty values CLEAR (an absent parameter would preserve).
  await expectResponseOk(
    await request.post('/api/theme', { form: { name: '', color: '', main_color: '', css: '' } }),
    'reset theme config',
  )
  await expectResponseOk(await request.delete('/api/theme/stylesheet'), 'reset custom CSS')
  await expectResponseOk(await request.delete('/api/theme/script'), 'reset custom script')
  for (const type of ['logo', 'background', 'favicon']) {
    await expectResponseOk(await request.delete(`/api/theme/image/${type}`), `reset ${type}`)
  }
}

/** The brand primary the running theme resolves, scheme-independent (dark mode remaps the alias). */
async function primary500(page: Page): Promise<string> {
  return page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue('--p-primary-500').trim().toLowerCase(),
  )
}

async function faviconHref(page: Page): Promise<string> {
  return page.evaluate(
    () => document.querySelector<HTMLLinkElement>('link[rel~="icon"]')?.getAttribute('href') ?? '',
  )
}

async function gotoBranding(page: Page): Promise<void> {
  await gotoRouteReady(page, '/#/settings/branding', ROUTE_ROOT.settingsBranding)
}

test.describe('Settings › Branding', () => {
  test.beforeEach(async ({ request, cleanup }) => {
    await resetBranding(request)
    cleanup.defer('reset branding to the bundled defaults', () => resetBranding(request))
  })

  test('is reachable from the settings hub and from the settings navigation', async ({ page }) => {
    await gotoRouteReady(page, '/#/settings', ROUTE_ROOT.settingsHub)

    // The hub entry carries its one-line description, like every other entry.
    await expect(
      page.getByText('Application name, colors, logo, favicon, and custom CSS or JavaScript.'),
    ).toBeVisible()
    await page.getByRole('link', { name: /Branding/ }).first().click()
    await expectRouteReady(page, '/#/settings/branding', ROUTE_ROOT.settingsBranding)
    await expect(page.getByRole('heading', { name: 'Branding' })).toBeVisible()

    // …and from the authoritative settings sidebar, so an admin already inside Settings can find
    // it without going back to the hub.
    const nav = await openNav(page)
    await expect(nav.getByRole('link', { name: 'Branding' })).toBeVisible()
    // On mobile the open Drawer's mask would intercept every later pointer event.
    await closeNav(page)
  })

  test('#57: an admin sets the application name and replaces the favicon from the UI alone', async ({ page }) => {
    await gotoBranding(page)

    const faviconBefore = await faviconHref(page)

    await page.locator('#branding-app-name').fill('Acme Docs')
    await page.getByRole('button', { name: 'Save' }).first().click()
    await expect(page.getByText('Branding saved')).toBeVisible()

    // The display half already worked; what was missing was any way to GET here from the UI.
    await expect.poll(() => page.title()).toBe('Acme Docs')

    // Replacing the image must bust the 15-day favicon cache, so the href has to change. Driven
    // through the REAL path — the per-image Upload button opens the file chooser — rather than by
    // poking the shared hidden input, which would skip the button that decides WHICH image is
    // being replaced.
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Upload Favicon' }).click()
    await (await chooser).setFiles(join(FIXTURES, 'pixel.png'))
    await expect(page.getByText('Image uploaded')).toBeVisible()
    await expect.poll(() => faviconHref(page)).not.toBe(faviconBefore)
    await expect.poll(() => faviconHref(page)).toContain('/api/theme/image/favicon?v=')

    // A reset puts the bundled default back.
    await page.getByRole('button', { name: 'Reset Favicon' }).click()
    await expect(page.getByText('Image reset to the bundled default')).toBeVisible()
    await expect.poll(() => faviconHref(page)).toBe('/api/theme/image/favicon?v=0')
  })

  test('#241: the brand colour derives the palette and survives a family switch and dark mode', async ({ page }) => {
    await gotoBranding(page)
    const stockPrimary = await primary500(page)
    expect(stockPrimary).not.toBe(BRAND_COLOR)
    // A REAL rendered control, not just a variable: the primary-filled Save button.
    const saveButton = page.getByRole('button', { name: 'Save' }).first()
    const stockButtonBackground = await saveButton.evaluate((el) => getComputedStyle(el).backgroundColor)

    await page.locator('#branding-main-color').fill(BRAND_COLOR)
    await saveButton.click()
    await expect(page.getByText('Branding saved')).toBeVisible()

    // The derived palette reaches the live theme without a reload…
    await expect.poll(() => primary500(page)).toBe(BRAND_COLOR)
    // …and repaints the control. (Asserted as "changed" rather than a literal rgb() so the test
    // pins the BEHAVIOUR, not PrimeVue's internal choice of which scale step a filled button uses.)
    await expect
      .poll(() => saveButton.evaluate((el) => getComputedStyle(el).backgroundColor))
      .not.toBe(stockButtonBackground)

    // THE #241 regression: switching preset family rebuilds the whole preset. It used to paste the
    // stock Teedy scale back in, silently un-branding the instance.
    await gotoRouteReady(page, '/#/settings/account', ROUTE_ROOT.settingsAccount)
    await page.locator('#account-theme').click()
    await page.getByRole('option', { name: 'Aura', exact: true }).click()
    await expect(page.getByText('Theme switched to Aura')).toBeVisible()
    await expect.poll(() => primary500(page)).toBe(BRAND_COLOR)

    // Dark mode shares the same semantic primary scale, so it must survive that too.
    const darkToggle = page.getByRole('button', { name: 'Dark mode' })
    await darkToggle.click()
    await expect(page.locator('html')).toHaveClass(/dark-mode/)
    await expect.poll(() => primary500(page)).toBe(BRAND_COLOR)

    await darkToggle.click()
    await expect(page.locator('html')).not.toHaveClass(/dark-mode/)
    await expect.poll(() => primary500(page)).toBe(BRAND_COLOR)

    // Clearing it returns the instance to the stock palette. Asserted on the EFFECT rather than a
    // second "Branding saved" toast, which could still be the first one on screen.
    await gotoBranding(page)
    await page.getByRole('button', { name: 'Clear' }).nth(1).click()
    await page.getByRole('button', { name: 'Save' }).first().click()
    await expect.poll(() => primary500(page)).toBe(stockPrimary)
  })

  test('custom CSS is served and applied, and custom JavaScript is loaded and runs', async ({ page }) => {
    await gotoBranding(page)

    // Nothing is loaded on an instance with no custom assets.
    expect(await page.locator('#teedy-theme-script').count()).toBe(0)

    await page.locator('#branding-css').fill(PROBE_CSS)
    await page.getByRole('button', { name: 'Save' }).nth(1).click()
    await expect(page.getByText('Custom CSS saved')).toBeVisible()

    // The compiled stylesheet is actually linked and actually styles the page — before this phase
    // GET /theme/stylesheet had no consumer at all, so custom CSS was inert in the SPA.
    await expect
      .poll(() =>
        page.evaluate(() => {
          const heading = document.querySelector('.branding-settings h2')
          return heading ? getComputedStyle(heading).outlineColor : ''
        }),
      )
      .toBe('rgb(1, 2, 3)')

    await page.locator('#branding-js').fill(PROBE_JS)
    await page.getByRole('button', { name: 'Save' }).nth(2).click()
    // Saving a non-empty script asks for a second confirmation: it runs for every user.
    await confirmDanger(page)
    await expect(page.getByText('Custom JavaScript saved')).toBeVisible()

    // Loaded as an EXTERNAL same-origin script (never inlined, never eval'd) — and it runs.
    await expect(page.locator('#teedy-theme-script')).toHaveAttribute('src', /\/api\/theme\/script\?v=.+/)
    await expect
      .poll(() => page.evaluate(() => (window as unknown as { __teedyBrandingProbe?: string }).__teedyBrandingProbe))
      .toBe('executed')

    // Clearing both boxes removes them. Asserted on the EFFECT, not on a second toast carrying the
    // same text as the one already on screen.
    await page.locator('#branding-css').fill('')
    await page.getByRole('button', { name: 'Save' }).nth(1).click()
    await expect
      .poll(() =>
        page.evaluate(() => {
          const heading = document.querySelector('.branding-settings h2')
          return heading ? getComputedStyle(heading).outlineColor : ''
        }),
      )
      .not.toBe('rgb(1, 2, 3)')

    await page.locator('#branding-js').fill('')
    await page.getByRole('button', { name: 'Save' }).nth(2).click()
    await expect(page.locator('#teedy-theme-script')).toHaveCount(0)
  })
})

// --- The in-app brand: the display half of #57 -------------------------------
//
// The settings section above SETS the name and the logo; these specs assert they actually reach
// the app chrome. Before this phase the brand shown in the most visible place on screen — the
// panel/drawer top-left — was the hardcoded literal "teedy", the About dialog carried a second
// hardcoded copy, and the uploaded logo had NO consumer anywhere in the SPA. So an operator could
// rename their instance and upload a logo and still see stock Teedy everywhere but the tab title,
// which defeats the point of #57 (telling two instances apart at a glance).
//
// The nav brand is asserted through openNav(), which resolves to the desktop left panel at the
// desktop viewport and to the mobile Drawer at the mobile one — so the `desktop` and `mobile`
// Playwright projects cover BOTH brand sites with one spec each.

const APP_NAME = 'Contoso Archive'
// Exercises the 30-character cap the name field allows: the brand must ellipsize rather than push
// the add-document button out of the panel.
const LONG_APP_NAME = 'Northwind Traders Document Hub'

/** The brand link inside whichever navigation container this viewport renders. */
async function navBrand(page: Page): Promise<Locator> {
  return (await openNav(page)).locator('.panel-brand')
}

/**
 * Re-enter the document list with a FRESH app instance.
 *
 * These specs change the theme over the API rather than through the settings screen, so nothing
 * invalidates the client cache — and the theme query is deliberately `staleTime: Infinity`. A
 * `goto` back to the same hash URL is a SAME-DOCUMENT navigation, which leaves the running SPA and
 * its cache untouched, so the change would be invisible. Only a real document reload picks it up.
 *
 * This is not a product gap: the Branding screen calls invalidateTheme() after every mutation, so
 * an admin editing through the UI sees the brand change without reloading.
 */
async function reloadDocumentList(page: Page): Promise<void> {
  await page.reload()
  await expectRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
}

/** The rendered background colour of a colour field's swatch (the PrimeVue preview button). */
async function swatchColor(page: Page, inputId: string): Promise<string> {
  return page
    .locator('.color-row')
    .filter({ has: page.locator(`#${inputId}`) })
    .locator('.p-colorpicker-preview')
    .evaluate((el) => getComputedStyle(el).backgroundColor)
}

test.describe('Settings › Branding › the brand shown inside the app', () => {
  test.beforeEach(async ({ request, cleanup }) => {
    await resetBranding(request)
    cleanup.defer('reset branding to the bundled defaults', () => resetBranding(request))
  })

  test('#57: the configured application name is the brand in the navigation and in About', async ({
    page,
    request,
  }) => {
    // An unbranded instance keeps the product name — never an empty brand.
    await gotoRouteReady(page, '/#/document', ROUTE_ROOT.documentList)
    await expect(await navBrand(page)).toHaveText('Teedy')
    await closeNav(page)

    await expectResponseOk(
      await request.post('/api/theme', { form: { name: APP_NAME } }),
      'set the application name',
    )
    await reloadDocumentList(page)

    // The nav brand — the desktop left panel, or the mobile drawer.
    await expect(await navBrand(page)).toHaveText(APP_NAME)
    // It stays a link to the document list, and the name is its accessible name.
    const brand = (await openNav(page)).getByRole('link', { name: APP_NAME })
    await expect(brand).toBeVisible()
    await expect(brand).toHaveAttribute('href', /#\/document$/)
    await closeNav(page)

    // The About dialog carried a THIRD hardcoded copy of the brand.
    await page.getByRole('button', { name: 'About', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('.about-name')).toHaveText(APP_NAME)
  })

  test('#57: a 30-character name ellipsizes instead of overflowing the brand row', async ({
    page,
    request,
  }) => {
    expect(LONG_APP_NAME.length).toBe(30)
    await expectResponseOk(
      await request.post('/api/theme', { form: { name: LONG_APP_NAME } }),
      'set a maximum-length application name',
    )
    await gotoRouteReady(page, '/#/document', ROUTE_ROOT.documentList)

    const nav = await openNav(page)
    const brand = nav.locator('.panel-brand')
    await expect(brand).toHaveText(LONG_APP_NAME)

    // The brand must stay inside its container rather than pushing the row wider — the same
    // geometry contract the German-overflow gate applies to the nav links.
    const brandBox = await brand.boundingBox()
    const navBox = await nav.boundingBox()
    expect(brandBox, 'brand has a box').not.toBeNull()
    expect(navBox, 'nav has a box').not.toBeNull()
    expect(
      brandBox!.x + brandBox!.width,
      'long brand right edge stays within the nav container',
    ).toBeLessThanOrEqual(navBox!.x + navBox!.width + 1)
    // Ellipsized, not wrapped to a second line: the row keeps its single-line height.
    const name = brand.locator('.panel-brand-name')
    await expect(name).toHaveCSS('text-overflow', 'ellipsis')
    await expect(name).toHaveCSS('white-space', 'nowrap')
    await closeNav(page)
  })

  test('#57: an uploaded logo shows in the brand row, and an instance without one shows none', async ({
    page,
    request,
  }) => {
    await gotoRouteReady(page, '/#/document', ROUTE_ROOT.documentList)

    // No custom logo: the brand stays text-only. The BUNDLED default logo is a Teedy asset, so
    // rendering it here would give every unbranded instance a logo it never chose.
    await expect((await openNav(page)).locator('.panel-brand-logo')).toHaveCount(0)
    await closeNav(page)

    await expectResponseOk(
      await request.put('/api/theme/image/logo', {
        multipart: {
          image: {
            name: 'pixel.png',
            mimeType: 'image/png',
            buffer: readFileSync(join(FIXTURES, 'pixel.png')),
          },
        },
      }),
      'upload a custom logo',
    )
    await reloadDocumentList(page)

    const logo = (await openNav(page)).locator('.panel-brand-logo')
    await expect(logo).toBeVisible()
    // Carries the image's own cache-bust token, so a replaced logo is not served stale for the
    // 15 days the image response is cached for. v=0 means "no custom logo" and must not appear.
    await expect(logo).toHaveAttribute('src', /^\/api\/theme\/image\/logo\?v=[1-9]\d*$/)
    // It really decoded — a broken <img> would report zero natural width.
    await expect
      .poll(() => logo.evaluate((el) => (el as HTMLImageElement).naturalWidth))
      .toBeGreaterThan(0)
    // The name stays beside it, so the brand keeps an accessible text name.
    await expect((await openNav(page)).locator('.panel-brand-name')).toHaveText('Teedy')
    await closeNav(page)

    // Resetting the logo returns the instance to the text-only brand.
    await expectResponseOk(await request.delete('/api/theme/image/logo'), 'reset the logo')
    await reloadDocumentList(page)
    await expect((await openNav(page)).locator('.panel-brand-logo')).toHaveCount(0)
    await closeNav(page)
  })

  test('#57: an unset brand colour does not render a red swatch', async ({ page }) => {
    await gotoBranding(page)

    // PrimeVue's ColorPicker falls back to its own `defaultColor` ('ff0000') whenever the bound
    // value is empty, so an instance that never chose a brand colour looked like it had picked
    // red — while the text field beside it correctly showed the placeholder.
    await expect(page.locator('#branding-main-color')).toHaveValue('')
    const unsetSwatch = await swatchColor(page, 'branding-main-color')
    expect(unsetSwatch).not.toBe('rgb(255, 0, 0)')

    // The swatch still TRACKS a real value — the fix must not have frozen it.
    await page.locator('#branding-main-color').fill('#123456')
    await expect.poll(() => swatchColor(page, 'branding-main-color')).not.toBe(unsetSwatch)

    // …and clearing a previously-set brand colour returns to the unset state, not to red.
    await page.getByRole('button', { name: 'Clear' }).nth(1).click()
    await expect(page.locator('#branding-main-color')).toHaveValue('')
    await expect.poll(() => swatchColor(page, 'branding-main-color')).toBe(unsetSwatch)
    expect(unsetSwatch).not.toBe('rgb(255, 0, 0)')
  })
})
