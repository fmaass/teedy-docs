import { test, expect } from '@playwright/test'

// Dark-mode toggle via the REAL header control (AppHeader "Dark mode" button).
// Asserts a COMPUTED style change, not just a class attribute: teedy-theme.css maps
// `body` background from --p-surface-50 (light) to --p-surface-950 (.dark-mode), so
// getComputedStyle(document.body).backgroundColor must actually change to a dark value
// and back. The choice persists across a reload (localStorage 'teedy-dark-mode', read
// back in main.ts). We toggle back off at the end (and in a finally) so the shared
// storageState does not leak dark mode into later specs.

// Parse an "rgb(r, g, b)" / "rgba(...)" string to its channels.
function rgbChannels(color: string): [number, number, number] {
  const m = color.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/)
  if (!m) throw new Error(`unexpected color: ${color}`)
  return [Number(m[1]), Number(m[2]), Number(m[3])]
}

function perceivedLuminance(color: string): number {
  const [r, g, b] = rgbChannels(color)
  // Rec. 601 luma, 0 (black) .. 255 (white).
  return 0.299 * r + 0.587 * g + 0.114 * b
}

async function bodyBackground(page: import('@playwright/test').Page): Promise<string> {
  return page.evaluate(() => getComputedStyle(document.body).backgroundColor)
}

test('toggling dark mode changes the computed body background, persists, and toggles back', async ({ page }) => {
  const toggle = page.getByRole('button', { name: 'Dark mode' })
  try {
    await page.goto('/#/document')
    await expect(toggle).toBeVisible()

    // Light baseline.
    const lightBg = await bodyBackground(page)
    const lightLuma = perceivedLuminance(lightBg)

    // Toggle ON → the computed background actually changes and becomes dark.
    await toggle.click()
    await expect(page.locator('html')).toHaveClass(/dark-mode/)
    await expect.poll(async () => await bodyBackground(page)).not.toBe(lightBg)
    const darkBg = await bodyBackground(page)
    const darkLuma = perceivedLuminance(darkBg)
    // "Actually dark": low absolute luminance AND darker than the light state.
    expect(darkLuma, `dark bg ${darkBg} must be dark`).toBeLessThan(80)
    expect(darkLuma, `dark bg ${darkBg} must be darker than light ${lightBg}`).toBeLessThan(lightLuma)

    // Persistence: reload keeps dark mode (localStorage-backed, restored in main.ts).
    await page.reload()
    await expect(page.locator('html')).toHaveClass(/dark-mode/)
    await expect.poll(async () => perceivedLuminance(await bodyBackground(page))).toBeLessThan(80)

    // Toggle back OFF → light restored.
    await page.getByRole('button', { name: 'Dark mode' }).click()
    await expect(page.locator('html')).not.toHaveClass(/dark-mode/)
    await expect.poll(async () => await bodyBackground(page)).toBe(lightBg)
  } finally {
    // Ensure dark mode is off for later specs even if an assertion failed while on.
    await page
      .evaluate(() => {
        localStorage.setItem('teedy-dark-mode', 'false')
        document.documentElement.classList.remove('dark-mode')
      })
      .catch(() => {})
  }
})
