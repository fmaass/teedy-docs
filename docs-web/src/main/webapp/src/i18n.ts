import { createI18n } from 'vue-i18n'

import en from './locale/en.json'

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en },
})

const localeImports: Record<string, () => Promise<any>> = {
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

export async function setLocale(locale: string) {
  if (locale !== 'en' && localeImports[locale]) {
    const messages = await localeImports[locale]()
    i18n.global.setLocaleMessage(locale, messages.default || messages)
  }
  ;(i18n.global.locale as any).value = locale
}
