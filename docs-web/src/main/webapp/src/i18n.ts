import { createI18n } from 'vue-i18n'

import en from './locale/en.json'

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en },
  missingWarn: import.meta.env.DEV,
  fallbackWarn: false,
})

type LocaleMessages = Record<string, unknown>
type LocaleModule = { default: LocaleMessages } | LocaleMessages

const localeImports: Record<string, () => Promise<LocaleModule>> = {
  de: () => import('./locale/de.json'),
  es: () => import('./locale/es.json'),
  fr: () => import('./locale/fr.json'),
  it: () => import('./locale/it.json'),
  pt: () => import('./locale/pt.json'),
  pl: () => import('./locale/pl.json'),
  el: () => import('./locale/el.json'),
  ru: () => import('./locale/ru.json'),
  zh_CN: () => import('./locale/zh_CN.json'),
  zh_TW: () => import('./locale/zh_TW.json'),
  sq_AL: () => import('./locale/sq_AL.json'),
}

type LocaleListener = (locale: string) => void
const localeListeners = new Set<LocaleListener>()

/**
 * Subscribe to locale switches. The callback fires after the new locale's
 * messages are loaded AND the active locale has been switched, so a listener can
 * safely read the freshly-active translations (e.g. to mirror them into a
 * non-vue-i18n consumer such as PrimeVue's own built-in locale). Returns nothing;
 * listeners live for the app's lifetime.
 */
export function onLocaleChange(fn: LocaleListener): void {
  localeListeners.add(fn)
}

export async function setLocale(locale: string) {
  if (locale !== 'en' && localeImports[locale]) {
    const messages = await localeImports[locale]()
    const resolvedMessages = 'default' in messages ? messages.default : messages
    i18n.global.setLocaleMessage(locale, resolvedMessages as typeof en)
  }
  ;(i18n.global.locale as { value: string }).value = locale
  document.documentElement.lang = locale.replace('_', '-')
  localeListeners.forEach((fn) => fn(locale))
}
