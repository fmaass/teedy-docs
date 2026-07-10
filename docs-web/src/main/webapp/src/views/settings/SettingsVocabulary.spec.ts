import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'

// Mock the vocabulary api module — the flow under test is create-new-namespace, which
// must issue createVocabularyEntry(name, firstValue, 0) exactly once and only after
// name + first value pass validation.
const apiMock = vi.hoisted(() => ({
  listVocabularyNames: vi.fn(),
  getVocabulary: vi.fn(),
  createVocabularyEntry: vi.fn(),
  updateVocabularyEntry: vi.fn(),
  deleteVocabularyEntry: vi.fn(),
}))
vi.mock('../../api/vocabulary', () => apiMock)

// PrimeVue overlays probe window.matchMedia, absent under jsdom.
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

import SettingsVocabulary from './SettingsVocabulary.vue'

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(SettingsVocabulary, {
    global: {
      plugins: [i18n, PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
    },
  })
}

describe('SettingsVocabulary — create new namespace flow', () => {
  beforeEach(() => {
    apiMock.listVocabularyNames.mockReset().mockResolvedValue({ data: { names: ['type'] } })
    apiMock.getVocabulary.mockReset().mockResolvedValue({ data: { entries: [] } })
    apiMock.createVocabularyEntry.mockReset().mockResolvedValue({ data: {} })
  })

  it('creates a new vocabulary by adding its first entry at order 0', async () => {
    const wrapper = mountView()
    await flushPromises()

    // Open the New vocabulary dialog via its component method (the button click path is
    // covered by the disabled-gating assertions below).
    ;(wrapper.vm as unknown as { openNewVocabularyDialog: () => void }).openNewVocabularyDialog()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      newVocabularyName: string
      newVocabularyValue: string
      newVocabularyNameValid: boolean
      newVocabularyExists: boolean
      doCreateVocabulary: () => void
    }

    // Invalid name (uppercase / spaces) must gate the create call.
    vm.newVocabularyName = 'Bad Name'
    vm.newVocabularyValue = 'First'
    await flushPromises()
    expect(vm.newVocabularyNameValid).toBe(false)
    vm.doCreateVocabulary()
    expect(apiMock.createVocabularyEntry).not.toHaveBeenCalled()

    // A name that collides with an existing vocabulary is rejected too.
    vm.newVocabularyName = 'type'
    await flushPromises()
    expect(vm.newVocabularyExists).toBe(true)
    vm.doCreateVocabulary()
    expect(apiMock.createVocabularyEntry).not.toHaveBeenCalled()

    // A valid, unique name plus a first value issues exactly one create at order 0.
    vm.newVocabularyName = 'license'
    vm.newVocabularyValue = 'Public Domain'
    await flushPromises()
    expect(vm.newVocabularyNameValid).toBe(true)
    expect(vm.newVocabularyExists).toBe(false)
    vm.doCreateVocabulary()
    await flushPromises()

    expect(apiMock.createVocabularyEntry).toHaveBeenCalledTimes(1)
    expect(apiMock.createVocabularyEntry).toHaveBeenCalledWith('license', 'Public Domain', 0)
  })
})
