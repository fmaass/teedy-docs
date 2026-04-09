import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin, QueryClient } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import Lara from '@primeuix/themes/lara'
import { definePreset } from '@primeuix/themes'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import 'primeicons/primeicons.css'
import './assets/teedy-theme.css'
import './assets/teedy-tokens.css'
import { teedyPrimary } from './theme/primary'

import App from './App.vue'
import router from './router'
import { i18n, setLocale } from './i18n'

// Restore persisted preferences
const savedLocale = localStorage.getItem('teedy-locale')
if (savedLocale && savedLocale !== 'en') {
  setLocale(savedLocale)
}
if (localStorage.getItem('teedy-dark-mode') === 'true') {
  document.documentElement.classList.add('dark-mode')
}

const TeedyPreset = definePreset(Lara, {
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
  theme: {
    preset: TeedyPreset,
    options: {
      darkModeSelector: '.dark-mode',
    },
  },
})
app.use(ToastService)
app.use(ConfirmationService)

app.mount('#app')

// Restore saved theme (must be after mount so usePrimeVue works)
const savedTheme = localStorage.getItem('teedy-theme')
if (savedTheme && savedTheme !== 'Lara') {
  import('./composables/useThemeSwitch').then(({ useThemeSwitch }) => {
    const { switchTheme } = useThemeSwitch()
    switchTheme(savedTheme)
  })
}
