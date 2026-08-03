import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import DocumentSearchBar from './DocumentSearchBar.vue'
import { pluralRules } from '../i18n'
import en from '../locale/en.json'
import de from '../locale/de.json'
import ru from '../locale/ru.json'
import pl from '../locale/pl.json'
import zh_CN from '../locale/zh_CN.json'

// #260: the result counter above the document list read "{count} document found" in every
// locale, so any result set other than one rendered as "10 document found".
//
// The i18n instance here carries the REAL exported pluralRules rather than a hand-written
// copy: the whole point of the ticket is that a wrong plural FORM is invisible to the
// locale-parity gate, so the branch-selection logic under test must be the shipped one. A
// stub here would assert the test's own arithmetic instead of the app's.
const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, de, ru, pl, zh_CN },
  pluralRules,
})

// Presentational PrimeVue leaves — this component's contract is the counter string, not
// their internals. Popover is only opened by the help button, which no test here clicks.
const stubs = {
  InputText: { template: '<input />' },
  Button: { template: '<button />' },
  Popover: { template: '<div><slot /></div>' },
}

function mountBar(totalCount: number) {
  return mount(DocumentSearchBar, {
    props: { modelValue: '', hasActiveFilters: false, totalCount },
    global: { plugins: [i18n], stubs },
  })
}

const countText = (totalCount: number) => mountBar(totalCount).find('.doc-count').text()

// Resolve the message directly, independent of the component's `v-if`. Needed for the
// zero case, which the template deliberately never renders (see the guard test below).
const countMessage = (locale: string, count: number) => {
  i18n.global.locale.value = locale
  return i18n.global.t('document.count', { count })
}

beforeEach(() => {
  i18n.global.locale.value = 'en'
})

describe('DocumentSearchBar — result count pluralisation (#260)', () => {
  it('renders the singular noun for exactly one document', () => {
    expect(countText(1)).toBe('1 document found')
  })

  it('renders the plural noun for more than one document', () => {
    expect(countText(2)).toBe('2 documents found')
    expect(countText(10)).toBe('10 documents found')
  })

  it('never renders the counter at all for an empty result set', () => {
    // `v-if="totalCount"` — 0 is falsy, so the empty state owns that screen. Asserted so the
    // zero-form expectation below is understood as message correctness, not a visible string.
    expect(mountBar(0).find('.doc-count').exists()).toBe(false)
  })

  it('still resolves a correct zero form in English', () => {
    expect(countMessage('en', 0)).toBe('0 documents found')
  })

  // Russian: CLDR one/few/many. The default vue-i18n rule for a three-branch message is
  // zero/one/other, which would put every n>=2 on the "many" form — so these cases fail
  // both when the forms are missing AND when they are present but selected by the stock rule.
  it('applies the Russian one/few/many rule', () => {
    expect(countMessage('ru', 1)).toBe('Найден 1 документ')
    expect(countMessage('ru', 2)).toBe('Найдено 2 документа')
    expect(countMessage('ru', 4)).toBe('Найдено 4 документа')
    expect(countMessage('ru', 5)).toBe('Найдено 5 документов')
    expect(countMessage('ru', 0)).toBe('Найдено 0 документов')
    // The teens are the trap: 11 and 12 take "many" despite ending in 1 and 2.
    expect(countMessage('ru', 11)).toBe('Найдено 11 документов')
    expect(countMessage('ru', 12)).toBe('Найдено 12 документов')
    expect(countMessage('ru', 21)).toBe('Найден 21 документ')
    expect(countMessage('ru', 22)).toBe('Найдено 22 документа')
  })

  // Polish: same three-way split, but "one" is strictly n === 1 — 21 is "many" in Polish
  // where it is "one" in Russian. That divergence is why the two need separate rules.
  it('applies the Polish one/few/many rule', () => {
    expect(countMessage('pl', 1)).toBe('Znaleziono 1 dokument')
    expect(countMessage('pl', 2)).toBe('Znaleziono 2 dokumenty')
    expect(countMessage('pl', 4)).toBe('Znaleziono 4 dokumenty')
    expect(countMessage('pl', 5)).toBe('Znaleziono 5 dokumentów')
    expect(countMessage('pl', 0)).toBe('Znaleziono 0 dokumentów')
    expect(countMessage('pl', 12)).toBe('Znaleziono 12 dokumentów')
    expect(countMessage('pl', 21)).toBe('Znaleziono 21 dokumentów')
    expect(countMessage('pl', 22)).toBe('Znaleziono 22 dokumenty')
  })

  // German has the plain one/other split and must keep working through the shared rule.
  it('applies the German one/other rule', () => {
    expect(countMessage('de', 1)).toBe('1 Dokument gefunden')
    expect(countMessage('de', 5)).toBe('5 Dokumente gefunden')
  })

  // Chinese has a single form: the same string for every count, with no pipe branch at all.
  it('uses one invariant form in Chinese', () => {
    expect(countMessage('zh_CN', 1)).toBe('找到 1 个文档')
    expect(countMessage('zh_CN', 5)).toBe('找到 5 个文档')
    expect(countMessage('zh_CN', 0)).toBe('找到 0 个文档')
  })

  // Guard on the rule's delegation path: the two pre-existing two-branch plural keys must
  // keep their stock behaviour in ru/pl, so adding the Slavic rules is inert for them.
  it('leaves the existing two-branch plural keys unchanged in ru and pl', () => {
    i18n.global.locale.value = 'ru'
    expect(i18n.global.t('ui.n_files', 1)).toBe('1 файл')
    expect(i18n.global.t('ui.n_files', 5)).toBe('5 файлов')
    i18n.global.locale.value = 'pl'
    expect(i18n.global.t('ui.n_files', 1)).toBe('1 plik')
    expect(i18n.global.t('ui.n_files', 5)).toBe('5 plików')
  })

  // The `choicesLength < 3` branch of slavicPlural claims to reproduce vue-i18n's stock
  // one/other split. Prove that against the stock rule ITSELF, re-derived at runtime from an
  // i18n instance carrying no pluralRules, rather than against hardcoded strings: if vue-i18n
  // ever changes its default, this fails loudly instead of drifting out of agreement with the
  // comment. Negative input is the case that motivated it — the stock rule applies Math.abs()
  // before comparing to 1, so a signed count must still take the SINGULAR branch.
  it('reproduces the stock one/other rule exactly, negative input included', () => {
    const stock = createI18n({
      legacy: false,
      locale: 'ru',
      fallbackLocale: 'en',
      messages: { en, de, ru, pl, zh_CN },
    })
    for (const locale of ['ru', 'pl'] as const) {
      i18n.global.locale.value = locale
      stock.global.locale.value = locale
      for (const n of [-11, -5, -2, -1, 0, 1, 2, 5, 11, 21]) {
        // Compared as labelled strings so a failure names the locale and the count.
        expect(`${locale} n=${n} -> ${i18n.global.t('ui.n_files', n)}`).toBe(
          `${locale} n=${n} -> ${stock.global.t('ui.n_files', n)}`,
        )
      }
    }
  })

  it('sends a negative two-branch count to the singular branch', () => {
    // Spelled out as well as diffed, so the intended behaviour is readable and not merely
    // implied by equality with another implementation.
    i18n.global.locale.value = 'ru'
    expect(i18n.global.t('ui.n_files', -1)).toBe('-1 файл')
    i18n.global.locale.value = 'pl'
    expect(i18n.global.t('ui.n_files', -1)).toBe('-1 plik')
  })
})
