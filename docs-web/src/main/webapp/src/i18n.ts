import { createI18n } from 'vue-i18n'

import en from './locale/en.json'

/**
 * CLDR one/few/many selection for the Slavic locales (#260).
 *
 * vue-i18n does NOT apply per-language CLDR rules: its stock rule for a THREE-branch
 * message is zero/one/other, which would put every n >= 2 on the last branch. Russian and
 * Polish need 2-4 ("few") separated from 5+ ("many"), so a three-form translation without
 * this rule renders the wrong form while still passing the locale-parity gate — the exact
 * silent failure #260 called out.
 *
 * `choicesLength < 3` reproduces vue-i18n's stock one/other split EXACTLY — including its
 * `Math.abs()`, so a NEGATIVE choice takes the singular branch exactly as the stock rule
 * does. Without the abs this would be an almost-passthrough: -1 would silently fall to the
 * plural branch, and the next two-form key fed by a signed value would take the wrong one.
 * That keeps the pre-existing TWO-branch keys (ui.n_files, ui.trash_purges_in_days) truly
 * untouched; this rule only engages for messages that actually supply a third form.
 *
 * The equivalence is pinned by a test that diffs this branch against a STOCK-ruled i18n
 * instance over a signed input range rather than against hardcoded expectations, so a
 * change in vue-i18n's own default fails the suite instead of silently diverging. vue-i18n
 * does pass the original rule as a third argument, but its public type declares it
 * optional (`orgRule?: PluralizationRule`), so delegating to it would need a fallback
 * branch nothing can exercise — the runtime-diffed test buys the same guarantee without
 * carrying untestable dead code.
 *
 * The two languages differ in exactly one place: Russian's "one" is any number ending in 1
 * except the teens (21 -> "1 документ"), Polish's "one" is strictly 1 (21 -> "dokumentów").
 */
function slavicPlural(oneIsExactlyOne: boolean) {
  return (choice: number, choicesLength: number): number => {
    if (choicesLength < 3) return Math.abs(choice) === 1 ? 0 : 1
    const n = Math.abs(choice)
    const mod10 = n % 10
    const mod100 = n % 100
    const isOne = oneIsExactlyOne ? n === 1 : mod10 === 1 && mod100 !== 11
    if (isOne) return 0
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 1
    return 2
  }
}

/**
 * Exported so unit tests exercise the SHIPPED branch-selection logic rather than a
 * hand-written copy of it — a wrong plural form is invisible to the parity gate, so the
 * rule itself has to be the thing under test.
 */
export const pluralRules = {
  ru: slavicPlural(false),
  pl: slavicPlural(true),
}

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en },
  pluralRules,
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
