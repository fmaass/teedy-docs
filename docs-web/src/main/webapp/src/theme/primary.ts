import { definePreset, palette, updatePrimaryPalette } from '@primeuix/themes'
import type { ThemePreset } from './presets'

/**
 * A PrimeVue primary colour scale: the 50…950 semantic steps every preset family reads its
 * accent colour from.
 */
export type PrimaryScale = Record<string, string>

/**
 * The stock Teedy brand primary — the accent an instance shows when no admin has set a custom
 * brand colour (#241 `main_color`).
 */
export const teedyPrimary: PrimaryScale = {
  50: '#e8f7fb',
  100: '#c9edf6',
  200: '#95dbee',
  300: '#5ec7e3',
  400: '#38b6da',
  500: '#2aabd2',
  600: '#2493b5',
  700: '#1e7a97',
  800: '#18627a',
  900: '#124a5c',
  950: '#0c323e',
}

/**
 * THE brand primary in force. Single source of truth on purpose: the preset is rebuilt from
 * scratch every time the user picks another family (Aura/Lara/Material/Nora), and before this
 * module owned the value each rebuild pasted the hard-coded stock scale back in — silently
 * discarding a custom brand colour the moment someone switched theme.
 */
let brandPrimary: PrimaryScale = teedyPrimary

/**
 * The brand colour whose scale is currently applied to the live theme. Starts as `null` because
 * that is the state the app boots in (the stock scale, baked into the initial preset), so an
 * unbranded instance never pays for a redundant runtime palette update.
 */
let appliedBrandColor: string | null = null

/**
 * Derives a full 50…950 primary scale from a brand colour, using PrimeVue's own derivation so a
 * custom brand colour produces exactly the tints/shades its component tokens expect.
 *
 * Returns null for anything that is not a 3- or 6-digit hex colour, or for a derivation that did
 * not produce a usable scale — the caller then keeps the stock brand primary rather than
 * rendering a half-derived palette.
 */
export function derivePrimaryScale(color: string | null | undefined): PrimaryScale | null {
  const hex = (color ?? '').trim()
  if (!/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(hex)) return null

  // palette() is typed `string | ColorScale` — it passes a CSS variable reference through
  // unchanged — so only the scale form is usable here.
  const derived = palette(hex)
  if (typeof derived !== 'object' || derived === null) return null

  const scale: PrimaryScale = {}
  for (const [step, value] of Object.entries(derived)) {
    if (typeof value === 'string') scale[step] = value
  }
  return scale['500'] ? scale : null
}

/**
 * Sets the brand primary every subsequent preset build will use. A null scale restores the stock
 * Teedy primary.
 */
export function setBrandPrimary(scale: PrimaryScale | null): void {
  brandPrimary = scale ?? teedyPrimary
}

/**
 * The brand primary currently in force.
 */
export function getBrandPrimary(): PrimaryScale {
  return brandPrimary
}

/**
 * Builds the app's PrimeVue preset from a base family preset. THE preset builder: both the boot
 * path (main.ts) and the runtime family switch (useThemeSwitch) go through it, which is what
 * makes the brand primary survive a family switch.
 */
export function buildPreset(base: ThemePreset) {
  return definePreset(base, {
    semantic: {
      primary: getBrandPrimary(),
    },
  })
}

/**
 * Applies the configured brand colour to the RUNNING theme.
 *
 * Called once the theme query resolves rather than awaited before mount (#216 keeps first paint
 * off the network), so this reaches for `updatePrimaryPalette` — the runtime path — instead of
 * rebuilding the preset. Dark mode needs no second call: `.dark-mode` reads the same semantic
 * primary scale.
 *
 * A no-op when the colour has not changed since the last application, so the repeated emissions
 * of the shared theme query cannot thrash the generated stylesheet.
 */
export function applyBrandPrimary(color: string | null | undefined): void {
  const normalized = (color ?? '').trim().toLowerCase() || null
  if (normalized === appliedBrandColor) return

  const scale = normalized ? derivePrimaryScale(normalized) : null
  appliedBrandColor = scale ? normalized : null
  setBrandPrimary(scale)
  updatePrimaryPalette(getBrandPrimary())
}

/**
 * Test seam: forget which brand colour has been applied, so a spec can drive applyBrandPrimary
 * from the app's boot state.
 */
export function resetBrandPrimaryForTest(): void {
  brandPrimary = teedyPrimary
  appliedBrandColor = null
}
