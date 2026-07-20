import { describe, it, expect, beforeAll } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createI18n, useI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import FileUpload from 'primevue/fileupload'
import en from '../../locale/en.json'
import de from '../../locale/de.json'

// #146: the PrimeVue FileUpload "Choose" button was hardcoded English (no chooseLabel);
// both upload widgets (DocumentViewContent, DocumentEdit) now bind :chooseLabel="t('ui.choose')".
// This proves that binding renders the ACTIVE locale and reacts to a locale switch (guards against
// a regression to a static, non-reactive label or a dropped ui.choose key).

beforeAll(() => {
  if (typeof globalThis.ResizeObserver !== 'function') {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
})

// Mirrors the real widgets' binding: an advanced FileUpload whose Choose button label is t('ui.choose').
const Host = defineComponent({
  setup() {
    const { t } = useI18n()
    return () =>
      h(FileUpload, {
        mode: 'advanced',
        chooseLabel: t('ui.choose'),
        showUploadButton: false,
        showCancelButton: false,
      })
  },
})

describe('FileUpload Choose button is localized via t(ui.choose) (#146)', () => {
  it('renders the active locale and updates on a locale switch', async () => {
    const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en, de } })
    const wrapper = mount(Host, { global: { plugins: [i18n, PrimeVue] } })
    await flushPromises()

    // English: the built-in default would also be "Choose", so additionally assert the German switch.
    expect(wrapper.text()).toContain('Choose')

    i18n.global.locale.value = 'de'
    await flushPromises()

    // Proves the label is driven by t('ui.choose') (German "Auswählen"), not a static English default.
    expect(wrapper.text()).toContain('Auswählen')
    expect(wrapper.text()).not.toContain('Choose')
  })

  it('ui.choose is present and non-empty in every shipped locale', () => {
    const locales = import.meta.glob('../../locale/*.json', { eager: true }) as Record<
      string,
      { default: { ui?: { choose?: string } } }
    >
    const entries = Object.entries(locales)
    expect(entries.length).toBeGreaterThanOrEqual(12)
    for (const [path, mod] of entries) {
      const choose = mod.default.ui?.choose
      expect(choose, `${path} is missing ui.choose`).toBeTruthy()
      expect(typeof choose).toBe('string')
    }
  })

  it('both production upload widgets actually bind chooseLabel to t(ui.choose) (#146)', () => {
    // Ties this spec to the real widgets: the reactive-switch test above uses a synthetic host, so
    // without this a removal of the production bindings would go unnoticed.
    const sources = import.meta.glob('./{DocumentViewContent,DocumentEdit}.vue', {
      eager: true,
      query: '?raw',
      import: 'default',
    }) as Record<string, string>
    const entries = Object.entries(sources)
    expect(entries.length).toBe(2)
    for (const [path, src] of entries) {
      expect(src, `${path} must bind :chooseLabel to t('ui.choose')`).toContain(`:chooseLabel="t('ui.choose')"`)
    }
  })
})
