import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin, QueryClient } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import { definePreset } from '@primeuix/themes'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import 'primeicons/primeicons.css'
import './assets/teedy-tokens.css'
import './assets/teedy-theme.css'
import { teedyPrimary } from './theme/primary'
import { DARK_MODE_SELECTOR } from './constants/theme'
import { getStoredTheme, loadPreset } from './theme/presets'

import App from './App.vue'
import router from './router'
import { i18n, setLocale } from './i18n'
import { buildPrimeVueLocale, bindPrimeVueConfig } from './primevueLocale'

const savedLocale = localStorage.getItem('teedy-locale')
if (savedLocale && savedLocale !== 'en') {
  setLocale(savedLocale)
}
if (localStorage.getItem('teedy-dark-mode') === 'true') {
  document.documentElement.classList.add('dark-mode')
}

// Only the active theme's preset is fetched at startup (its own lazy chunk),
// keeping the other three presets out of the initial bundle.
const basePreset = await loadPreset(getStoredTheme())

const TeedyPreset = definePreset(basePreset, {
  semantic: {
    primary: teedyPrimary,
  },
})

const app = createApp(App)

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
})

app.use(createPinia())
app.use(VueQueryPlugin, { queryClient })
app.use(router)
app.use(i18n)
app.use(PrimeVue, {
  // Seed PrimeVue's built-in strings from the app's active locale. The initial
  // setLocale above is async and un-awaited, so at this point it may or may not
  // have switched the locale yet — buildPrimeVueLocale reads whatever is active,
  // and bindPrimeVueConfig re-applies once installation completes; the
  // onLocaleChange subscription inside primevueLocale then catches a later resolve.
  locale: buildPrimeVueLocale(),
  theme: {
    preset: TeedyPreset,
    options: {
      darkModeSelector: DARK_MODE_SELECTOR,
      cssLayer: {
        name: 'primevue',
        order: 'primevue',
      },
    },
  },
})
bindPrimeVueConfig(
  (app.config.globalProperties.$primevue as unknown as { config: { locale: Record<string, unknown> } }).config,
)
app.use(ToastService)
app.use(ConfirmationService)
app.directive('tooltip', Tooltip)

app.mount('#app')
