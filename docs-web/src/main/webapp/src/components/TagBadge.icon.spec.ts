import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import en from '../locale/en.json'
import TagBadge from './TagBadge.vue'
import { TAG_ICONS_STORAGE_KEY, resetTagIconsVisibility } from '../composables/useTagIcons'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })

function mountBadge(props: Record<string, unknown>) {
  return mount(TagBadge, { props: props as never, global: { plugins: [i18n] } })
}

/**
 * The DOM a chip WITHOUT an icon produces, frozen as a literal.
 *
 * These two strings were captured from TagBadge as it stood BEFORE tag icons existed
 * (commit cbbdb639) and are the whole point of the test: 28 Playwright visual baselines
 * screenshot tag chips in the document list, the gallery and the slide-over, and none of
 * their fixtures carries an icon. An icon feature that added so much as an empty wrapper,
 * a placeholder comment or a class to the no-icon chip would move every one of those PNGs
 * — and would do it silently, because a component test that only asked "is there no icon?"
 * would still pass.
 *
 * A `v-if` on the icon is exactly the trap: Vue leaves a `<!--v-if-->` comment anchor where
 * the element would be, so every chip in the app would gain a node. That is why the icon is
 * rendered through a `v-for` over a 0-or-1 array instead — a fragment's anchors are empty
 * TEXT nodes, which serialize to nothing.
 *
 * The trailing `<!--v-if-->` in each literal is the REMOVE BUTTON's own anchor, which the
 * chip has always had; it is part of the frozen shape, not something icons introduced.
 */
const NO_ICON_INERT_HTML =
  '<span class="teedy-tag" style="background-color: rgb(18, 52, 86); color: rgb(255, 255, 255);">alpha<!--v-if--></span>'
const NO_ICON_CLICKABLE_HTML =
  '<button type="button" class="teedy-tag tag-clickable" style="background-color: rgb(18, 52, 86); color: rgb(255, 255, 255);" aria-label="Filter by tag alpha">alpha</button>'

/**
 * The rendered HTML with the SFC's scoped-style marker removed. The marker is a hash of the
 * component's path, not of its DOM, so pinning it would turn a file move into a false failure
 * while proving nothing about the chip's shape — which is what these literals are guarding.
 */
function frozenHtml(el: Element): string {
  return el.outerHTML.replace(/ data-v-[0-9a-f]+=""/g, '')
}

describe('TagBadge — a chip without an icon is byte-identical to the pre-icon chip', () => {
  beforeEach(() => {
    localStorage.clear()
    resetTagIconsVisibility()
  })

  it('renders the frozen inert-chip DOM when no icon prop is passed at all', () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456' })
    expect(frozenHtml(wrapper.element)).toBe(NO_ICON_INERT_HTML)
  })

  it('renders the frozen inert-chip DOM when the icon prop is explicitly null', () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', icon: null })
    expect(frozenHtml(wrapper.element)).toBe(NO_ICON_INERT_HTML)
  })

  it('renders the frozen inert-chip DOM when the icon prop is an empty string', () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', icon: '' })
    expect(frozenHtml(wrapper.element)).toBe(NO_ICON_INERT_HTML)
  })

  it('renders the frozen clickable-chip DOM when no icon is set', () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', clickable: true })
    expect(frozenHtml(wrapper.element)).toBe(NO_ICON_CLICKABLE_HTML)
  })

  it('falls back to the frozen no-icon DOM for an unparseable icon reference', () => {
    // A value neither `emoji:` nor `set:` is not a reason to draw a broken box: the chip
    // renders exactly as it would with no icon at all.
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', icon: 'fontawesome:star' })
    expect(frozenHtml(wrapper.element)).toBe(NO_ICON_INERT_HTML)
  })

  it('falls back to the frozen no-icon DOM for an `emoji:` reference with an empty payload', () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', icon: 'emoji:' })
    expect(frozenHtml(wrapper.element)).toBe(NO_ICON_INERT_HTML)
  })
})

describe('TagBadge — a chip WITH an icon', () => {
  beforeEach(() => {
    localStorage.clear()
    resetTagIconsVisibility()
  })

  it('renders an emoji icon as text ahead of the name, hidden from assistive tech', () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', icon: 'emoji:\u{1F396}\u{FE0F}' })
    const icon = wrapper.find('.tag-icon')
    expect(icon.exists()).toBe(true)
    expect(icon.text()).toBe('\u{1F396}\u{FE0F}')
    // The name already carries the meaning; the icon is decoration and must not be announced.
    expect(icon.attributes('aria-hidden')).toBe('true')
    // Icon first, then the name.
    expect(wrapper.element.textContent).toBe('\u{1F396}\u{FE0F}alpha')
  })

  it('renders a set icon as an <img> pointing at the icon data endpoint', () => {
    const wrapper = mountBadge({
      name: 'alpha',
      color: '#123456',
      icon: 'set:8b1e4f22-0000-4000-8000-000000000001',
    })
    const img = wrapper.find('img.tag-icon')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('api/tag/icon/8b1e4f22-0000-4000-8000-000000000001/data')
    // Decorative: an empty alt keeps it out of the accessible name, which is the tag name.
    expect(img.attributes('alt')).toBe('')
  })

  it('drops the <img> when the icon data 404s, rather than showing a broken image', () => {
    // The authoritative fallback is the server nulling TAG_ICON_C when an icon is deleted.
    // This is the belt for a tag list a client is still holding from before that happened.
    const wrapper = mountBadge({
      name: 'alpha',
      color: '#123456',
      icon: 'set:8b1e4f22-0000-4000-8000-000000000001',
    })
    return wrapper
      .find('img.tag-icon')
      .trigger('error')
      .then(() => {
        expect(wrapper.find('img.tag-icon').exists()).toBe(false)
        expect(frozenHtml(wrapper.element)).toBe(NO_ICON_INERT_HTML)
      })
  })

  it('keeps the removable close button when an icon is set', () => {
    const wrapper = mountBadge({
      name: 'alpha',
      color: '#123456',
      icon: 'emoji:\u{1F396}\u{FE0F}',
      removable: true,
    })
    expect(wrapper.find('.tag-icon').exists()).toBe(true)
    expect(wrapper.find('.tag-remove-btn').exists()).toBe(true)
  })

  it('renders the icon on a clickable chip too', () => {
    const wrapper = mountBadge({
      name: 'alpha',
      color: '#123456',
      icon: 'emoji:\u{1F396}\u{FE0F}',
      clickable: true,
    })
    expect(wrapper.find('button.tag-clickable .tag-icon').exists()).toBe(true)
  })
})

describe('TagBadge — the hide-icons preference', () => {
  beforeEach(() => {
    localStorage.clear()
    resetTagIconsVisibility()
  })

  it('shows icons by default (nothing stored)', () => {
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', icon: 'emoji:\u{1F396}\u{FE0F}' })
    expect(wrapper.find('.tag-icon').exists()).toBe(true)
  })

  it('renders the frozen no-icon DOM once icons are hidden', () => {
    localStorage.setItem(TAG_ICONS_STORAGE_KEY, 'hidden')
    resetTagIconsVisibility()
    const wrapper = mountBadge({ name: 'alpha', color: '#123456', icon: 'emoji:\u{1F396}\u{FE0F}' })
    // Hiding icons must land on exactly the DOM a tag with no icon produces — otherwise the
    // preference would itself be a layout change.
    expect(frozenHtml(wrapper.element)).toBe(NO_ICON_INERT_HTML)
  })
})
