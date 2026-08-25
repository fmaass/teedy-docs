import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import en from '../locale/en.json'
import de from '../locale/de.json'
import AccessCountBadge from './AccessCountBadge.vue'

function mountBadge(count: number | undefined, kind: 'document' | 'file', locale = 'en') {
  return mount(AccessCountBadge, {
    props: { count, kind },
    global: {
      plugins: [createI18n({ legacy: false, locale, fallbackLocale: 'en', messages: { en, de } })],
    },
  })
}

describe('AccessCountBadge (#300)', () => {
  it('renders nothing while the count is still unknown', () => {
    // undefined means "the counts have not arrived yet". Rendering a 0 there would state
    // something false about the user's history for as long as the request is in flight.
    expect(mountBadge(undefined, 'document').find('.access-count').exists()).toBe(false)
  })

  it('renders a zero count once it is genuinely known', () => {
    const badge = mountBadge(0, 'document')
    expect(badge.find('.access-count').exists()).toBe(true)
    expect(badge.find('.access-count-value').text()).toBe('0')
  })

  it('shows the count and an accessible label for a document', () => {
    const badge = mountBadge(3, 'document')
    expect(badge.find('.access-count-value').text()).toBe('3')
    expect(badge.find('.access-count').attributes('aria-label')).toBe('Opened 3 times by you')
  })

  it('uses the singular wording for exactly one access', () => {
    expect(mountBadge(1, 'document').find('.access-count').attributes('aria-label')).toBe(
      'Opened once by you',
    )
  })

  it('words a FILE access differently from a document open', () => {
    const file = mountBadge(2, 'file').find('.access-count').attributes('aria-label')
    const document = mountBadge(2, 'document').find('.access-count').attributes('aria-label')
    expect(file).toBe('Accessed 2 times by you')
    expect(file).not.toBe(document)
  })

  it('translates, and the German wording is du-form', () => {
    const label = mountBadge(4, 'document', 'de').find('.access-count').attributes('aria-label')
    expect(label).toBe('4-mal von dir geöffnet')
  })

  it('carries no username anywhere — the badge is the caller\'s own number only', () => {
    expect(mountBadge(5, 'file').html()).not.toMatch(/user|admin/i)
  })
})
