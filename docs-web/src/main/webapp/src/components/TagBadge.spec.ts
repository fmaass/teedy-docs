import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import en from '../locale/en.json'
import TagBadge from './TagBadge.vue'

// Real vue-i18n with the shipped English messages: the aria-label assertions must
// prove the rendered accessible name INCLUDES the interpolated tag name (a
// key-echo stub would pass even if the {name} parameter were dropped).
const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })

function mountBadge(props: Record<string, unknown>) {
  return mount(TagBadge, { props: props as never, global: { plugins: [i18n] } })
}

describe('TagBadge — default inert mode', () => {
  it('renders a plain, non-interactive span (no button, no select emit path)', async () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456' })
    // The chip is a span, NOT a button — the 7 existing render sites stay inert.
    expect(wrapper.element.tagName).toBe('SPAN')
    expect(wrapper.find('button.tag-clickable').exists()).toBe(false)
    // Clicking an inert chip emits nothing.
    await wrapper.trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('still renders the removable close button when removable (unchanged)', () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', removable: true })
    const removeBtn = wrapper.find('.tag-remove-btn')
    expect(removeBtn.exists()).toBe(true)
    // The remove button's accessible name carries the interpolated tag name too.
    expect(removeBtn.attributes('aria-label')).toBe('Remove tag alpha')
  })
})

describe('TagBadge — clickable mode (#34)', () => {
  it('renders as a button whose accessible name is "Filter by tag <name>"', () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', clickable: true })
    const btn = wrapper.find('button.tag-clickable')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('type')).toBe('button')
    // The rendered accessible name must contain the interpolated tag name — this
    // is what a screen reader announces. A stateless action, so NO aria-pressed.
    expect(btn.attributes('aria-label')).toBe('Filter by tag alpha')
    expect(btn.attributes('aria-pressed')).toBeUndefined()
  })

  it('emits select on click', async () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', clickable: true })
    await wrapper.find('button.tag-clickable').trigger('click')
    expect(wrapper.emitted('select')).toHaveLength(1)
  })

  it('carries the tag color as its background (contrast/color logic unchanged)', () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', clickable: true })
    const btn = wrapper.find('button.tag-clickable')
    expect(btn.attributes('style')).toContain('background-color')
  })
})
