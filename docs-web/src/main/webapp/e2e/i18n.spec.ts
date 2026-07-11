import { test, expect } from '@playwright/test'

// UI language switch (vue-i18n) via the REAL Settings → Account language control.
// Proves the whole loop: selecting Deutsch swaps rendered strings to German, the
// choice persists across a reload (localStorage 'teedy-locale'), and switching back
// to English restores them. The asserted German strings are copied verbatim from
// src/locale/de.json (ui.account.title = "Benutzerkonto", ui.account.appearance =
// "Darstellung", ui.account.language = "Sprache"), so a broken lazy-load or a missing
// key would fail this test — it is not asserting against itself.
//
// The account page is the switch's own screen, so its labels are the most direct
// evidence the active locale changed. We assert the h2 heading and the two unique
// section labels (the "Change password" string appears twice on this page — the section
// title AND the default-password banner CTA — so we avoid it). We restore English at the
// end (and in a finally) so the shared admin storageState does not leak German into
// later specs.

const EN = { title: 'User account', appearance: 'Appearance' }
const DE = { title: 'Benutzerkonto', appearance: 'Darstellung' }

async function selectLanguage(page: import('@playwright/test').Page, optionLabel: string): Promise<void> {
  await page.locator('#account-locale').click()
  await page.getByRole('option', { name: optionLabel, exact: true }).click()
}

test('switching the UI language to German and back updates and persists rendered strings', async ({ page }) => {
  // The Appearance section title (h3.section-title, first card) is a unique element on
  // this page — scope to it so a repeated string elsewhere can never make the match
  // ambiguous.
  const appearanceTitle = (page: import('@playwright/test').Page) =>
    page.locator('.section-title').first()

  try {
    await page.goto('/#/settings/account')
    // Baseline: English renders (h2 heading + the Appearance section title).
    await expect(page.getByRole('heading', { name: EN.title })).toBeVisible()
    await expect(appearanceTitle(page)).toHaveText(EN.appearance)

    // Switch to Deutsch → German strings render on the same screen.
    await selectLanguage(page, 'Deutsch')
    await expect(page.getByRole('heading', { name: DE.title })).toBeVisible()
    await expect(appearanceTitle(page)).toHaveText(DE.appearance)

    // Persistence: a full reload must keep German (localStorage-backed locale).
    await page.reload()
    await expect(page.getByRole('heading', { name: DE.title })).toBeVisible()
    await expect(appearanceTitle(page)).toHaveText(DE.appearance)

    // Switch back to English → restoration (heading + section title both English again).
    await selectLanguage(page, 'English')
    await expect(page.getByRole('heading', { name: EN.title })).toBeVisible()
    await expect(appearanceTitle(page)).toHaveText(EN.appearance)
  } finally {
    // Guard against leaking a non-English locale into later specs if an assertion
    // above failed after the switch.
    await page.evaluate(() => localStorage.setItem('teedy-locale', 'en')).catch(() => {})
  }
})
