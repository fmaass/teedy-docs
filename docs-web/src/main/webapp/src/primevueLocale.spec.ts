import { describe, it, expect, beforeEach, vi } from 'vitest'

// Each test drives a FRESH copy of the i18n + primevueLocale module graph so the
// module-level `boundConfig` singleton and the onLocaleChange subscription start
// clean. Because both modules come from the same reset graph, primevueLocale's
// `import { onLocaleChange } from './i18n'` resolves to the very i18n instance whose
// setLocale we call — the subscription and the emitter stay wired together.
async function freshModules() {
  vi.resetModules()
  const i18nMod = await import('./i18n')
  const pvMod = await import('./primevueLocale')
  return { ...i18nMod, ...pvMod }
}

// A stand-in for PrimeVue's reactive config.locale — the only dependency the bridge
// mutates. It starts on PrimeVue's English built-in defaults so a successful switch
// is observable as a change away from them.
function fakeConfig() {
  return { locale: { accept: 'Yes', reject: 'No' } as Record<string, unknown> }
}

describe('primevueLocale bridge', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('builds accept/reject from vue-i18n and calendar names from Intl for the active locale', async () => {
    const m = await freshModules()

    // English (the default, statically-loaded locale).
    let built = m.buildPrimeVueLocale()
    expect(built.accept).toBe('Yes')
    expect(built.reject).toBe('No')
    expect((built.monthNames as string[])[0]).toBe('January')
    expect(built.firstDayOfWeek).toBe(0)

    // Switch to German → labels track de.json, calendar tracks the Intl de data.
    await m.setLocale('de')
    built = m.buildPrimeVueLocale()
    expect(built.accept).toBe('Ja')
    expect(built.reject).toBe('Nein')
    expect(built.passwordPrompt).toBe('Passwort eingeben')
    expect((built.monthNames as string[]).length).toBe(12)
    expect((built.monthNames as string[])[0]).toBe('Januar')
    // German weeks start on Monday (Intl weekInfo.firstDay = 1 → PrimeVue 1).
    expect(built.firstDayOfWeek).toBe(1)
    // dayNames stay Sunday-first (index 0 = Sunday) per PrimeVue's convention.
    expect((built.dayNames as string[])[0]).toBe('Sonntag')
  })

  it('applies the correct PrimeVue locale when the initial setLocale resolves BEFORE the plugin is bound', async () => {
    const m = await freshModules()
    const config = fakeConfig()

    // Race order 1: language already switched before PrimeVue installation completes.
    await m.setLocale('de')
    // Nothing is bound yet, so the emitted change was a no-op on the config.
    expect(config.locale.accept).toBe('Yes')

    // Binding at install time must immediately apply the already-active locale.
    m.bindPrimeVueConfig(config)
    expect(config.locale.accept).toBe('Ja')
    expect(config.locale.reject).toBe('Nein')
  })

  it('applies the correct PrimeVue locale when setLocale resolves AFTER the plugin is bound', async () => {
    const m = await freshModules()
    const config = fakeConfig()

    // Race order 2: PrimeVue installed (and bound) while still on English.
    m.bindPrimeVueConfig(config)
    expect(config.locale.accept).toBe('Yes')

    // A later locale switch must reach the bound config through onLocaleChange.
    await m.setLocale('de')
    expect(config.locale.accept).toBe('Ja')
    expect(config.locale.reject).toBe('Nein')

    // And switching back restores English.
    await m.setLocale('en')
    expect(config.locale.accept).toBe('Yes')
    expect(config.locale.reject).toBe('No')
  })
})
