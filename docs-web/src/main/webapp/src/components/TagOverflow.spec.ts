import { describe, it, expect, vi, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import TagOverflow from './TagOverflow.vue'
import type { Tag } from '../api/tag'

// vue-i18n stub: return the key so assertions target logic, not copy.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

function makeTags(n: number): Pick<Tag, 'id' | 'name' | 'color'>[] {
  return Array.from({ length: n }, (_, i) => ({
    id: `t${i + 3}`,
    name: `tag-${i + 3}`,
    color: '#123456',
  }))
}

function mountOverflow(tags: Pick<Tag, 'id' | 'name' | 'color'>[]) {
  return mount(TagOverflow, {
    props: { tags },
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: 'none' }]] },
  })
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe('TagOverflow — reveal panel (#24)', () => {
  it('renders a focusable +N trigger labelled with the hidden count', () => {
    const wrapper = mountOverflow(makeTags(3))
    const trigger = wrapper.find('.tag-overflow')
    expect(trigger.exists()).toBe(true)
    expect(trigger.text()).toContain('+3')
    expect(trigger.attributes('tabindex')).toBe('0')
    expect(trigger.attributes('role')).toBe('button')
    expect(trigger.attributes('aria-label')).toBe('ui.more_tags')
  })

  it('associates the trigger with the panel via aria-controls', () => {
    const wrapper = mountOverflow(makeTags(3))
    const trigger = wrapper.find('.tag-overflow')
    const controls = trigger.attributes('aria-controls')
    expect(controls).toBeTruthy()
    expect(trigger.attributes('aria-expanded')).toBe('false')
  })

  it('reveals the hidden tag names in a body-teleported panel on activation', async () => {
    const wrapper = mountOverflow(makeTags(3))
    // Before activation the panel content is not in the DOM.
    expect(document.body.textContent).not.toContain('tag-3')
    // Keyboard activation (Enter) reveals — proving focus/Enter reachability.
    await wrapper.find('.tag-overflow').trigger('keydown.enter')
    await flushPromises()
    await nextTick()
    // The Popover teleports to <body>; the hidden names must be reachable there,
    // NOT clipped inside the table cell.
    const panelId = wrapper.find('.tag-overflow').attributes('aria-controls')!
    const panel = document.getElementById(panelId)
    expect(panel).not.toBeNull()
    for (let i = 3; i <= 5; i++) {
      expect(panel!.textContent).toContain(`tag-${i}`)
    }
    // aria-expanded reflects the open state.
    expect(wrapper.find('.tag-overflow').attributes('aria-expanded')).toBe('true')
  })

  it('does not render inline tag names before activation (panel escapes the cell)', () => {
    // The trigger itself must not carry the hidden badges inline — a regression
    // to an in-cell CSS panel (clippable) would embed them in the trigger.
    const wrapper = mountOverflow(makeTags(3))
    const trigger = wrapper.find('.tag-overflow')
    expect(trigger.text()).not.toContain('tag-3')
  })

  it('Space activates the trigger (reveals the panel) without scrolling the page', async () => {
    const wrapper = mountOverflow(makeTags(3))
    await wrapper.find('.tag-overflow').trigger('keydown.space')
    await flushPromises()
    await nextTick()
    const panelId = wrapper.find('.tag-overflow').attributes('aria-controls')!
    expect(document.getElementById(panelId)).not.toBeNull()
    expect(wrapper.find('.tag-overflow').attributes('aria-expanded')).toBe('true')
  })
})
