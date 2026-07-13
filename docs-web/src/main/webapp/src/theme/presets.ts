import type Lara from '@primeuix/themes/lara'

// Type of a PrimeVue theme preset object (all four presets share the shape).
export type ThemePreset = typeof Lara

// Presets are dynamically imported so only the ACTIVE theme lands in the initial
// bundle. Statically importing all four pulled the full @primeuix/themes token
// set (~450 kB) into the entry chunk even though one theme is used per session.
// Each loader resolves to its own lazy chunk; main.ts awaits exactly one at
// startup and useThemeSwitch fetches another only when the user switches theme.
const presetLoaders: Record<string, () => Promise<{ default: ThemePreset }>> = {
  Aura: () => import('@primeuix/themes/aura'),
  Lara: () => import('@primeuix/themes/lara'),
  Material: () => import('@primeuix/themes/material'),
  Nora: () => import('@primeuix/themes/nora'),
}

export const themeNames = Object.keys(presetLoaders)

export const DEFAULT_THEME = 'Lara'

export function getStoredTheme(): string {
  return localStorage.getItem('teedy-theme') || DEFAULT_THEME
}

export async function loadPreset(name: string): Promise<ThemePreset> {
  const loader = presetLoaders[name] ?? presetLoaders[DEFAULT_THEME]
  const mod = await loader()
  return mod.default
}
