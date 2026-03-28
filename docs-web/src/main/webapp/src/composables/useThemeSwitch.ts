import { usePrimeVue } from 'primevue/config'
import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'
import Lara from '@primeuix/themes/lara'
import Material from '@primeuix/themes/material'
import Nora from '@primeuix/themes/nora'

const presets: Record<string, any> = { Aura, Lara, Material, Nora }

const teedyPrimary = {
  50: '{sky.50}',
  100: '{sky.100}',
  200: '{sky.200}',
  300: '{sky.300}',
  400: '{sky.400}',
  500: '#2aabd2',
  600: '#2493b5',
  700: '#1e7a97',
  800: '#18627a',
  900: '#124a5c',
  950: '#0c323e',
}

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
