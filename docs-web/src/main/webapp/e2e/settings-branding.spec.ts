import { test, expect, type Page, type APIRequestContext } from './fixtures'
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
