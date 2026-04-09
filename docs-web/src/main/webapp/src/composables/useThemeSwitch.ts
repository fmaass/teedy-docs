import { usePrimeVue } from 'primevue/config'
import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'
import Lara from '@primeuix/themes/lara'
import Material from '@primeuix/themes/material'
import Nora from '@primeuix/themes/nora'
import { teedyPrimary } from '../theme/primary'

const presets: Record<string, typeof Lara> = { Aura, Lara, Material, Nora }

export const themeNames = Object.keys(presets)

export function useThemeSwitch() {
  const PrimeVue = usePrimeVue()

  function switchTheme(name: string) {
    const base = presets[name] ?? Lara
    const preset = definePreset(base, { semantic: { primary: teedyPrimary } })
    PrimeVue.config.theme = {
      preset,
      options: { darkModeSelector: '.dark-mode' },
    }
    localStorage.setItem('teedy-theme', name)
  }

  return { switchTheme, themeNames }
}

export function getStoredTheme(): string {
  return localStorage.getItem('teedy-theme') || 'Lara'
}
