import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin, QueryClient } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import 'primeicons/primeicons.css'
import './assets/teedy-tokens.css'
import './assets/teedy-theme.css'
import { buildPreset } from './theme/primary'
import { DARK_MODE_SELECTOR } from './constants/theme'
import { getStoredTheme, loadPreset } from './theme/presets'

import App from './App.vue'
import router from './router'
import { armBootNavigationLatch } from './router/bootNavigationLatch'
import { i18n, setLocale } from './i18n'
import { buildPrimeVueLocale, bindPrimeVueConfig } from './primevueLocale'

// #216: a navigation the user issues while the app is still booting has to win over the
// initial one. Armed here, before the first `await` below, so the whole boot — the theme
// preset fetch, the router's first navigation, the async auth guard — is covered.
const replayBootNavigation = armBootNavigationLatch()

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

// Built through the shared preset builder, which is the ONE place that knows the brand primary.
// A custom brand colour (#241) is not known yet at this point — GET /api/theme is deliberately
// not awaited before mount — so this boots on the stock scale and useThemeBranding swaps in the
// derived palette once the theme resolves.
const TeedyPreset = buildPreset(basePreset)

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

// #216: mount only once the router's first navigation has FINALIZED, and only after any
// navigation the user issued in the meantime has been replayed. Mounting before the
// router is ready is what lets a fast follow-up navigation be reverted; mounting between
// readiness and the replay is what would flash the route the user has already left. The
// cost is that the shell paints a moment later on a slow link — accepted.
//
// This must NOT be a top-level await, however tempting: every lazily-imported route chunk
// statically imports THIS entry chunk, so suspending the module body on `router.isReady()`
// deadlocks the very chunk the first navigation is waiting for — the router never becomes
// ready and nothing ever mounts. Letting the module finish and doing the wait in a task
// keeps that dependency satisfiable.
void (async () => {
  try {
    await router.isReady()
  } catch {
    // The initial navigation FAILED (a route chunk that will not load, a guard that threw).
    // Mount anyway: an app shell with an empty view is what this has always shown for that
    // case, and a boot that never mounts at all would be the worse regression.
  }
  await replayBootNavigation(router)

  app.mount('#app')
})()
