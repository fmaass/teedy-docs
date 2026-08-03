import { describe, it, expect, beforeEach, vi } from 'vitest'

// --- Dependency mocks (NOT the unit under test) ---
// usePrimeVue gives the composable the live theme config object it assigns to; loadPreset
// normally dynamic-imports a family's full token set, which a unit test has no use for.
const primeVueConfig = vi.hoisted(() => ({ theme: undefined as unknown }))
vi.mock('primevue/config', () => ({ usePrimeVue: () => ({ config: primeVueConfig }) }))
vi.mock('../theme/presets', () => ({
  loadPreset: vi.fn(async (name: string) => ({ family: name, semantic: { primary: { 500: '#000000' } } })),
  themeNames: ['Aura', 'Lara', 'Material', 'Nora'],
  getStoredTheme: () => 'Lara',
}))

import { useThemeSwitch } from './useThemeSwitch'
import { derivePrimaryScale, resetBrandPrimaryForTest, setBrandPrimary, teedyPrimary } from '../theme/primary'

type ThemeConfig = { preset: { semantic: { primary: Record<string, string> } }; options: { darkModeSelector: string } }

function appliedPreset(): ThemeConfig {
  return primeVueConfig.theme as ThemeConfig
}

beforeEach(() => {
  resetBrandPrimaryForTest()
  primeVueConfig.theme = undefined
  localStorage.clear()
})

describe('useThemeSwitch — a family switch must not discard the brand colour (#241)', () => {
  it('carries a CUSTOM brand primary into every family the user switches to', async () => {
    const branded = derivePrimaryScale('#ff5722')!
    setBrandPrimary(branded)

    const { switchTheme } = useThemeSwitch()
    for (const family of ['Aura', 'Lara', 'Material', 'Nora']) {
      await switchTheme(family)
      // The regression this pins: the switch used to rebuild the preset with the hard-coded
      // stock scale, so picking any family silently reverted a branded instance to Teedy blue.
      expect(appliedPreset().preset.semantic.primary, `family ${family}`).toMatchObject(branded)
    }
  })

  it('uses the stock brand primary on an instance with no brand colour', async () => {
    const { switchTheme } = useThemeSwitch()
    await switchTheme('Aura')
    expect(appliedPreset().preset.semantic.primary).toMatchObject(teedyPrimary)
  })

  it('keeps the dark-mode selector and persists the chosen family', async () => {
    const { switchTheme } = useThemeSwitch()
    await switchTheme('Material')
    expect(appliedPreset().options.darkModeSelector).toBe('.dark-mode')
    expect(localStorage.getItem('teedy-theme')).toBe('Material')
  })
})
