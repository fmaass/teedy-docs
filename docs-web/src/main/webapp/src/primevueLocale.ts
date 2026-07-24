import { i18n, onLocaleChange } from './i18n'

// PrimeVue ships only English for the built-in strings its components render
// themselves — the confirm dialog's Yes/No, the password-strength meter, the
// empty-dropdown messages, and the date picker's calendar. Left unconfigured, a
// German user still sees "Yes"/"No" on every delete confirm. This bridge feeds
// PrimeVue a locale object assembled from two sources:
//   - short text labels come from the app's own vue-i18n catalog, so they track
//     the same translations (and du-form German) as the rest of the UI; and
//   - calendar month/day names come from the Intl API, which already knows every
//     locale's names — hand-translating twelve months × twelve languages into the
//     locale files would only duplicate what the platform provides for free.
//
// Only the keys the app's shipped components actually render are set here; every
// other PrimeVue locale key keeps its English default via the plugin's deep merge.

type PrimeLocale = Record<string, unknown>

/** The reactive slice of PrimeVue's config that owns the live locale object. */
interface PrimeConfigLocale {
  locale: PrimeLocale
}

function tr(key: string): string {
  return i18n.global.t(key)
}

// Text labels sourced from vue-i18n. accept/reject/cancel/choose reuse the app's
// existing generic terms so a confirm's Yes/No matches the app's own Yes/No; the
// primevue.* keys cover strings PrimeVue renders that had no app equivalent.
function labels(): PrimeLocale {
  return {
    accept: tr('yes'),
    reject: tr('no'),
    cancel: tr('cancel'),
    choose: tr('ui.choose'),
    today: tr('primevue.today'),
    clear: tr('primevue.clear'),
    weak: tr('primevue.weak'),
    medium: tr('primevue.medium'),
    strong: tr('primevue.strong'),
    passwordPrompt: tr('primevue.password_prompt'),
    emptyMessage: tr('primevue.empty_message'),
    emptyFilterMessage: tr('primevue.empty_filter_message'),
    emptySearchMessage: tr('primevue.empty_filter_message'),
  }
}

function firstDayOfWeek(bcp47: string): number {
  // Intl reports the first weekday as 1=Mon..7=Sun; PrimeVue wants 0=Sun..6=Sat,
  // so map n → n % 7. The accessor is spelled two ways across engines
  // (getWeekInfo() in newer, the weekInfo property in others) and is absent on
  // older runtimes, so probe both and fall back to Monday for non-English locales.
  try {
    const locale = new Intl.Locale(bcp47) as Intl.Locale & {
      weekInfo?: { firstDay: number }
      getWeekInfo?: () => { firstDay: number }
    }
    const info = typeof locale.getWeekInfo === 'function' ? locale.getWeekInfo() : locale.weekInfo
    if (info && typeof info.firstDay === 'number') return info.firstDay % 7
  } catch {
    // fall through to the heuristic default
  }
  return bcp47.startsWith('en') ? 0 : 1
}

// PrimeVue indexes its day-name arrays Sunday-first (index 0 = Sunday) and rotates
// the visible week itself via firstDayOfWeek, so the arrays stay Sunday-first here
// regardless of the locale's first day. Dates are built in UTC to keep the
// weekday/month mapping independent of the runner's timezone.
function calendar(bcp47: string): PrimeLocale {
  const months = (month: 'long' | 'short') => {
    const fmt = new Intl.DateTimeFormat(bcp47, { month, timeZone: 'UTC' })
    return Array.from({ length: 12 }, (_, m) => fmt.format(Date.UTC(2021, m, 15)))
  }
  const days = (weekday: 'long' | 'short' | 'narrow') => {
    const fmt = new Intl.DateTimeFormat(bcp47, { weekday, timeZone: 'UTC' })
    // 2021-08-01 (UTC) is a Sunday; step one day at a time for a Sunday-first week.
    return Array.from({ length: 7 }, (_, d) => fmt.format(Date.UTC(2021, 7, 1 + d)))
  }
  return {
    monthNames: months('long'),
    monthNamesShort: months('short'),
    dayNames: days('long'),
    dayNamesShort: days('short'),
    dayNamesMin: days('narrow'),
    firstDayOfWeek: firstDayOfWeek(bcp47),
  }
}

/**
 * Build the PrimeVue locale partial for the currently-active app locale. Reads the
 * live vue-i18n locale, so callers must invoke it only after setLocale has switched
 * (which is exactly when applyPrimeVueLocale runs).
 */
export function buildPrimeVueLocale(): PrimeLocale {
  const bcp47 = i18n.global.locale.value.replace('_', '-')
  return { ...labels(), ...calendar(bcp47) }
}

let boundConfig: PrimeConfigLocale | null = null

/**
 * Push the current locale onto the live PrimeVue config. Mutating the reactive
 * locale object in place (rather than reassigning it) keeps PrimeVue's own
 * reactivity intact so mounted components pick up the change. No-ops until a config
 * is bound, which makes the onLocaleChange subscription below safe to fire early.
 */
export function applyPrimeVueLocale(): void {
  if (!boundConfig) return
  Object.assign(boundConfig.locale, buildPrimeVueLocale())
}

/**
 * Hand this module the reactive PrimeVue config once the plugin is installed, then
 * apply immediately. The immediate apply covers the race where the un-awaited
 * initial setLocale in main.ts resolved BEFORE PrimeVue was installed; the
 * onLocaleChange subscription below covers the opposite order (setLocale resolving
 * after installation) and every later user-driven language switch.
 */
export function bindPrimeVueConfig(config: PrimeConfigLocale): void {
  boundConfig = config
  applyPrimeVueLocale()
}

onLocaleChange(() => applyPrimeVueLocale())
