import { usePrimeVue } from 'primevue/config'
import { buildPreset } from '../theme/primary'
import { DARK_MODE_SELECTOR } from '../constants/theme'
import { loadPreset, themeNames, getStoredTheme } from '../theme/presets'

export { themeNames, getStoredTheme }

export function useThemeSwitch() {
  const PrimeVue = usePrimeVue()

  async function switchTheme(name: string) {
    // Through the SHARED preset builder, never a locally pasted primary scale: a family switch
    // rebuilds the preset from the family's own base, so anything the rebuild does not carry over
    // is lost. Hard-coding the stock scale here is what used to wipe a custom brand colour (#241)
    // the moment a user picked Aura/Lara/Material/Nora.
    const base = await loadPreset(name)
    const preset = buildPreset(base)
    PrimeVue.config.theme = {
      preset,
      options: { darkModeSelector: DARK_MODE_SELECTOR },
    }
    localStorage.setItem('teedy-theme', name)
  }

  return { switchTheme, themeNames }
}
