import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import en from '../../locale/en.json'
import type { InboxConfig } from '../../api/app'

// Mock the app api module: the render + save flow under test must reflect the GET config and send the
// form back, without a real HTTP client.
const apiMock = vi.hoisted(() => ({
  getInboxConfig: vi.fn(),
  saveInboxConfig: vi.fn(),
  testInbox: vi.fn(),
}))
vi.mock('../../api/app', async (orig) => ({ ...(await orig()), ...apiMock }))

beforeAll(() => {
  if (typeof window.matchMedia !== 'function') {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: false, media: query, onchange: null,
        addEventListener: () => {}, removeEventListener: () => {},
        addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
      }),
    })
  }
})

import SettingsInbox from './SettingsInbox.vue'

const ENABLED_CONFIG: InboxConfig = {
  enabled: true,
  autoTagsEnabled: false,
  deleteImported: false,
  emlAttach: true,
  starttls: true,
  hostname: 'imap.example',
  port: 993,
  username: 'capture@example',
  folder: 'INBOX',
  tag: '',
}

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(SettingsInbox, {
    global: { plugins: [i18n, PrimeVue, ToastService, [VueQueryPlugin, { queryClient }]] },
  })
}

describe('SettingsInbox raw .eml toggle (#197)', () => {
  beforeEach(() => {
    apiMock.getInboxConfig.mockReset().mockResolvedValue(ENABLED_CONFIG)
    apiMock.saveInboxConfig.mockReset().mockResolvedValue({ data: {} })
    apiMock.testInbox.mockReset().mockResolvedValue({ count: 0 })
  })

  it('renders the toggle with its label and help line when scanning is enabled', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('#inbox-eml-attach').exists()).toBe(true)
    expect(wrapper.text()).toContain(en.ui.inbox.eml_attach)
    expect(wrapper.text()).toContain(en.ui.inbox.eml_attach_hint)
  })

  it('seeds the toggle from the GET', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect((wrapper.vm as unknown as { form: InboxConfig }).form.emlAttach).toBe(true)

    apiMock.getInboxConfig.mockResolvedValue({ ...ENABLED_CONFIG, emlAttach: false })
    const off = mountView()
    await flushPromises()
    expect((off.vm as unknown as { form: InboxConfig }).form.emlAttach).toBe(false)
  })

  /** A server that predates the field returns no value: the form must read that as OFF, never undefined. */
  it('treats a missing emlAttach in the GET as off', async () => {
    const { emlAttach: _omitted, ...withoutField } = ENABLED_CONFIG
    apiMock.getInboxConfig.mockResolvedValue(withoutField as InboxConfig)
    const wrapper = mountView()
    await flushPromises()
    expect((wrapper.vm as unknown as { form: InboxConfig }).form.emlAttach).toBe(false)
  })

  it('sends the toggle on save', async () => {
    const wrapper = mountView()
    await flushPromises()
    const form = (wrapper.vm as unknown as { form: InboxConfig }).form
    form.emlAttach = false
    ;(wrapper.vm as unknown as { onSave: () => void }).onSave()
    await flushPromises()
    expect(apiMock.saveInboxConfig).toHaveBeenCalledTimes(1)
    expect((apiMock.saveInboxConfig.mock.calls[0][0] as InboxConfig).emlAttach).toBe(false)

    form.emlAttach = true
    ;(wrapper.vm as unknown as { onSave: () => void }).onSave()
    await flushPromises()
    expect((apiMock.saveInboxConfig.mock.calls[1][0] as InboxConfig).emlAttach).toBe(true)
  })
})
