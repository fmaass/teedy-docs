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
  getVocabularyUsage: vi.fn(),
}))
vi.mock('../../api/vocabulary', () => apiMock)

// Capture the options passed into confirmDanger so the reference-warning tests can
// assert on the exact confirm message (count text vs. plain) without driving the
// PrimeVue overlay DOM.
const confirmDangerSpy = vi.hoisted(() => vi.fn())
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: confirmDangerSpy }),
}))

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

describe('SettingsVocabulary — reference-count warning on rename/delete', () => {
  beforeEach(() => {
    confirmDangerSpy.mockReset()
    apiMock.listVocabularyNames.mockReset().mockResolvedValue({ data: { names: ['license'] } })
    apiMock.getVocabulary.mockReset().mockResolvedValue({
      data: {
        entries: [
          { id: 'v1', name: 'license', value: 'Public Domain', order: 0 },
          { id: 'v2', name: 'license', value: 'All rights reserved', order: 1 },
        ],
      },
    })
    apiMock.updateVocabularyEntry.mockReset().mockResolvedValue({ data: {} })
    apiMock.deleteVocabularyEntry.mockReset().mockResolvedValue({ data: { status: 'ok' } })
    apiMock.getVocabularyUsage.mockReset()
  })

  it('DELETE with references: fetches usage then confirms with the count, and proceeds only on accept', async () => {
    apiMock.getVocabularyUsage.mockResolvedValue({ data: { document_count: 3 } })
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      entries: { id: string; value: string; order: number }[]
      confirmDelete: (e: { id: string; value: string; order: number }) => void
    }
    vm.confirmDelete(vm.entries[0])
    await flushPromises()

    // Usage was checked against the entry being deleted.
    expect(apiMock.getVocabularyUsage).toHaveBeenCalledWith('v1')
    // The confirm dialog was opened with a message carrying the true count.
    expect(confirmDangerSpy).toHaveBeenCalledTimes(1)
    const opts = confirmDangerSpy.mock.calls[0][0]
    expect(opts.message).toContain('3')

    // Nothing is deleted until the user accepts.
    expect(apiMock.deleteVocabularyEntry).not.toHaveBeenCalled()
    await opts.accept()
    await flushPromises()
    expect(apiMock.deleteVocabularyEntry).toHaveBeenCalledWith('v1')
  })

  it('DELETE with zero references: keeps the existing plain confirmation (no count text)', async () => {
    apiMock.getVocabularyUsage.mockResolvedValue({ data: { document_count: 0 } })
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      entries: { id: string; value: string; order: number }[]
      confirmDelete: (e: { id: string; value: string; order: number }) => void
    }
    // Delete the SECOND entry (not last) so the plain (non-last) confirm path is used.
    vm.confirmDelete(vm.entries[1])
    await flushPromises()

    expect(apiMock.getVocabularyUsage).toHaveBeenCalledWith('v2')
    const opts = confirmDangerSpy.mock.calls[0][0]
    // Existing plain delete_confirm message quotes the value, not a count.
    expect(opts.message).toContain('All rights reserved')
    expect(opts.message).not.toMatch(/\b\d+\b/)
  })

  it('RENAME (value change) with references: warns with the count before committing', async () => {
    apiMock.getVocabularyUsage.mockResolvedValue({ data: { document_count: 5 } })
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      entries: { id: string; value: string; order: number }[]
      startEdit: (e: { id: string; value: string; order: number }) => void
      editValue: string
      commitEdit: (id: string) => void
    }
    vm.startEdit(vm.entries[0])
    vm.editValue = 'Renamed value'
    vm.commitEdit('v1')
    await flushPromises()

    expect(apiMock.getVocabularyUsage).toHaveBeenCalledWith('v1')
    expect(confirmDangerSpy).toHaveBeenCalledTimes(1)
    const opts = confirmDangerSpy.mock.calls[0][0]
    expect(opts.message).toContain('5')

    // Rename is deferred until confirmation.
    expect(apiMock.updateVocabularyEntry).not.toHaveBeenCalled()
    await opts.accept()
    await flushPromises()
    expect(apiMock.updateVocabularyEntry).toHaveBeenCalledWith('v1', { value: 'Renamed value' })
  })

  it('RENAME (value change) with zero references: commits directly, no confirm dialog', async () => {
    apiMock.getVocabularyUsage.mockResolvedValue({ data: { document_count: 0 } })
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      entries: { id: string; value: string; order: number }[]
      startEdit: (e: { id: string; value: string; order: number }) => void
      editValue: string
      commitEdit: (id: string) => void
    }
    vm.startEdit(vm.entries[0])
    vm.editValue = 'New value'
    vm.commitEdit('v1')
    await flushPromises()

    expect(apiMock.getVocabularyUsage).toHaveBeenCalledWith('v1')
    // Zero references -> exact existing behaviour: rename proceeds with no confirmation.
    expect(confirmDangerSpy).not.toHaveBeenCalled()
    expect(apiMock.updateVocabularyEntry).toHaveBeenCalledWith('v1', { value: 'New value' })
  })

  it('RENAME with an unchanged value: no usage lookup, no confirm, direct no-op commit', async () => {
    apiMock.getVocabularyUsage.mockResolvedValue({ data: { document_count: 9 } })
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      entries: { id: string; value: string; order: number }[]
      startEdit: (e: { id: string; value: string; order: number }) => void
      editValue: string
      commitEdit: (id: string) => void
    }
    vm.startEdit(vm.entries[0])
    // Same value -> not a value change; must not fetch usage or warn.
    vm.editValue = 'Public Domain'
    vm.commitEdit('v1')
    await flushPromises()

    expect(apiMock.getVocabularyUsage).not.toHaveBeenCalled()
    expect(confirmDangerSpy).not.toHaveBeenCalled()
    expect(apiMock.updateVocabularyEntry).toHaveBeenCalledWith('v1', { value: 'Public Domain' })
  })
})
