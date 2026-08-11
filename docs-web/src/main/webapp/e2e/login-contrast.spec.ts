import { test, expect, type Page } from './fixtures'
import { gotoRouteReady, ROUTE_ROOT } from './helpers'

// #263 / TEEDY-28: brand-coloured text on the login screen must clear WCAG AA. The brand primary
// was painted through --p-primary-color (palette step 500) both as text (the "Password lost?"
// link, .forgot-link) and as a button background under a white label (the Sign in button), and
// step 500 measures 2.67:1 against white — under the 4.5:1 AA bar for normal text. The fix moves
// the text-carrying primary onto a darker step at the palette-derivation point (see
// theme/primary.ts and assets/teedy-tokens.css). This is a STANDING gate that fails loudly if a
// future change reintroduces the low-contrast step; it also IS the acceptance measurement the
// issue asks for — Chromium computed styles against a running instance, not calculated hexes.
//
// Only LIGHT mode is asserted: that is the confirmed-failing case (a white surface). In dark mode
// the same token already lands well clear of AA (the primary is read as a light step against a
// dark surface), so a dark assertion would be green before and after and would test the palette
// rather than this fix.

const AA_NORMAL = 4.5

// WCAG relative luminance + contrast ratio, from getComputedStyle rgb()/rgba() strings.
function parseRgb(s: string): [number, number, number, number] {
  const m = s.match(/rgba?\(([^)]+)\)/)
  if (!m) return [0, 0, 0, 0]
  const parts = m[1].split(',').map((p) => parseFloat(p.trim()))
  return [parts[0], parts[1], parts[2], parts[3] === undefined ? 1 : parts[3]]
}
function relLuminance([r, g, b]: [number, number, number, number]): number {
  const lin = [r, g, b].map((c) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
}
function contrast(fg: string, bg: string): number {
  const l1 = relLuminance(parseRgb(fg))
  const l2 = relLuminance(parseRgb(bg))
  const hi = Math.max(l1, l2)
  const lo = Math.min(l1, l2)
  return (hi + 0.05) / (lo + 0.05)
}

// Measure the effective foreground/background of a selector in the running page. For a text
// element (transparent background) the effective background is the first ancestor with a
// non-transparent background-color; for a solid element (a button) it is the element's own.
async function measure(page: Page, selector: string): Promise<{ fg: string; bg: string }> {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel) as HTMLElement | null
    if (!el) throw new Error(`selector not found: ${sel}`)
    const fg = getComputedStyle(el).color
    let node: HTMLElement | null = el
    let bg = 'rgba(0, 0, 0, 0)'
    while (node) {
      const c = getComputedStyle(node).backgroundColor
      const m = c.match(/rgba?\(([^)]+)\)/)
      const alpha = m ? (m[1].split(',')[3] === undefined ? 1 : parseFloat(m[1].split(',')[3])) : 0
      if (alpha > 0) {
        bg = c
        break
      }
      node = node.parentElement
    }
    // Fall back to the document background (white) if nothing opaque was found on the chain.
    if (bg === 'rgba(0, 0, 0, 0)') bg = getComputedStyle(document.body).backgroundColor || 'rgb(255, 255, 255)'
    return { fg, bg }
  }, selector)
}

test.describe('login brand contrast (WCAG AA — #263)', () => {
  test('Password-lost link and Sign in button clear AA on the logged-out login screen', async ({
    page,
    baseURL,
    cleanup,
  }) => {
    // A fresh anonymous context: the logged-out login screen is the surface the issue measured.
    // baseURL comes from the project config (the running instance) — deriving it from page.url()
    // would be about:blank here, since this context navigates nothing before the anon one.
    const context = await page.context().browser()!.newContext({
      storageState: { cookies: [], origins: [] },
      baseURL,
    })
    cleanup.defer('close the anonymous login-screen context', () => context.close())
    const anon = await context.newPage()
    await gotoRouteReady(anon, '/#/login?local=1', ROUTE_ROOT.login)

    // .forgot-link — brand colour as text on the login card.
    const forgot = await measure(anon, '.forgot-link')
    const forgotRatio = contrast(forgot.fg, forgot.bg)
    console.log(`[#263] .forgot-link  fg=${forgot.fg} bg=${forgot.bg}  ratio=${forgotRatio.toFixed(2)}:1`)

    // Sign in — white label on the brand-coloured submit button.
    const submit = await measure(anon, 'button[type="submit"]')
    const submitRatio = contrast(submit.fg, submit.bg)
    console.log(`[#263] Sign in button fg=${submit.fg} bg=${submit.bg}  ratio=${submitRatio.toFixed(2)}:1`)

    expect(forgotRatio, `.forgot-link contrast ${forgotRatio.toFixed(2)}:1 must clear AA`).toBeGreaterThanOrEqual(AA_NORMAL)
    expect(submitRatio, `Sign in button contrast ${submitRatio.toFixed(2)}:1 must clear AA`).toBeGreaterThanOrEqual(AA_NORMAL)
  })
})
