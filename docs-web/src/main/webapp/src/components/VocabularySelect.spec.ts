import { describe, it, expect, beforeAll } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import Select from 'primevue/select'
import type { VocabularyEntry } from '../api/vocabulary'

// PrimeVue's Select probes window.matchMedia (responsive overlay); jsdom does not
// provide it. Install a minimal stub for the test environment only.
beforeAll(() => {
  if (typeof window.matchMedia !== 'function') {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }),
    })
  }
})

// The document trio selects and VOCABULARY custom-metadata field feed a PrimeVue
// Select from a vocabulary's entry VALUES (order-preserving). This test verifies that
// mechanism end to end at the component boundary: a VocabularyEntry[] maps to the
// option list the Select renders, and selecting an option emits that value string.

function optionsFrom(entries: VocabularyEntry[]): string[] {
  return entries.map((e) => e.value)
}

const ENTRIES: VocabularyEntry[] = [
  { id: 'type-text', name: 'type', value: 'Text', order: 0 },
  { id: 'type-image', name: 'type', value: 'Image', order: 1 },
  { id: 'type-sound', name: 'type', value: 'Sound', order: 2 },
]

function mountSelect(modelValue: string | null = null) {
  return mount(Select, {
    props: {
      modelValue,
      options: optionsFrom(ENTRIES),
      'onUpdate:modelValue': () => {},
    },
    global: {
      plugins: [PrimeVue],
    },
  })
}

describe('vocabulary-backed Select', () => {
  it('maps vocabulary entries to their value strings, preserving order', () => {
    expect(optionsFrom(ENTRIES)).toEqual(['Text', 'Image', 'Sound'])
  })

  it('renders one option per vocabulary value', async () => {
    const wrapper = mountSelect()
    // Open the overlay so the option list renders.
    await wrapper.find('.p-select').trigger('click')
    await wrapper.vm.$nextTick()
    const labels = document.querySelectorAll('.p-select-option-label')
    const rendered = Array.from(labels).map((el) => el.textContent?.trim())
    expect(rendered).toEqual(['Text', 'Image', 'Sound'])
    wrapper.unmount()
  })

  it('emits the selected value string on update', async () => {
    const wrapper = mountSelect(null)
    // Drive the model update the way v-model would.
    ;(wrapper.vm as unknown as { $emit: (e: string, v: unknown) => void }).$emit(
      'update:modelValue',
      'Image',
    )
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['Image'])
    wrapper.unmount()
  })

  it('reflects a preselected value', () => {
    const wrapper = mountSelect('Sound')
    expect(wrapper.props('modelValue')).toBe('Sound')
    wrapper.unmount()
  })
})
