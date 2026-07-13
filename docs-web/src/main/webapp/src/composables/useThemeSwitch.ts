import { usePrimeVue } from 'primevue/config'
import { definePreset } from '@primeuix/themes'
import { teedyPrimary } from '../theme/primary'
import { DARK_MODE_SELECTOR } from '../constants/theme'
import { loadPreset, themeNames, getStoredTheme } from '../theme/presets'

export { themeNames, getStoredTheme }

export function useThemeSwitch() {
  const PrimeVue = usePrimeVue()

  async function switchTheme(name: string) {
    const base = await loadPreset(name)
    const preset = definePreset(base, { semantic: { primary: teedyPrimary } })
    PrimeVue.config.theme = {
      preset,
      options: { darkModeSelector: DARK_MODE_SELECTOR },
    }
    localStorage.setItem('teedy-theme', name)
  }

  return { switchTheme, themeNames }
}
