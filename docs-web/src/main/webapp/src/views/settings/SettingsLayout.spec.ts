import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

// useRoute is mocked per-test so we can flip route.meta.wideSettings.
const routeMeta = vi.hoisted(() => ({ value: {} as Record<string, unknown> }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ meta: routeMeta.value }),
  RouterView: defineComponent({ setup: () => () => h('div', { class: 'rv' }) }),
}))

import SettingsLayout from './SettingsLayout.vue'

function mountLayout() {
  return mount(SettingsLayout, {
    global: {
      stubs: { 'router-view': { template: '<div class="rv" />' } },
    },
  })
}

describe('SettingsLayout — wide table views (#11)', () => {
  it('applies the wide modifier class when the active route opts in via meta.wideSettings', () => {
    routeMeta.value = { wideSettings: true }
    const wrapper = mountLayout()
    const content = wrapper.find('.settings-content')
    expect(content.exists()).toBe(true)
    expect(content.classes()).toContain('settings-content--wide')
  })

  it('keeps the narrow readable cap (no wide modifier) for text-form settings views', () => {
    routeMeta.value = {}
    const wrapper = mountLayout()
    const content = wrapper.find('.settings-content')
    expect(content.exists()).toBe(true)
    expect(content.classes()).not.toContain('settings-content--wide')
  })
})
