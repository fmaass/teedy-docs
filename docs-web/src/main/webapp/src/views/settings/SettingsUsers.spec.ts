import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'
import type { UserListItem } from '../../api/user'

// Mock the user api module. The flow under test is the disable/enable toggle:
// it must call updateUser(username, { disabled }) with the correct boolean, and
// it must reflect each user's `disabled` state from GET /user/list.
const apiMock = vi.hoisted(() => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  deleteUser: vi.fn(),
  disableUserTotp: vi.fn(),
}))
vi.mock('../../api/user', () => apiMock)

// Capture the confirmDanger options so the disable (danger-confirmed) path can be
// driven without a real overlay: invoking accept() simulates the user confirming.
const confirmDangerSpy = vi.hoisted(() => vi.fn())
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: confirmDangerSpy }),
}))

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

import SettingsUsers from './SettingsUsers.vue'

const ENABLED_USER: UserListItem = {
  id: 'u1', username: 'alice', email: 'alice@x.com', totp_enabled: false,
  storage_quota: 1000, storage_current: 0, create_date: 1, admin: false, disabled: false,
}
const DISABLED_USER: UserListItem = {
  id: 'u2', username: 'bob', email: 'bob@x.com', totp_enabled: false,
  storage_quota: 1000, storage_current: 0, create_date: 1, admin: false, disabled: true,
}
const ADMIN_USER: UserListItem = {
  id: 'u3', username: 'root', email: 'root@x.com', totp_enabled: false,
  storage_quota: 1000, storage_current: 0, create_date: 1, admin: true, disabled: false,
}
const GUEST_USER: UserListItem = {
  id: 'guest', username: 'guest', email: 'guest@x.com', totp_enabled: false,
  storage_quota: 1000, storage_current: 0, create_date: 1, admin: false, disabled: false,
}

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(SettingsUsers, {
    global: {
      plugins: [i18n, PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
    },
  })
}

describe('SettingsUsers — disable/enable toggle', () => {
  beforeEach(() => {
    apiMock.listUsers.mockReset().mockResolvedValue({ data: { users: [ENABLED_USER, DISABLED_USER] } })
    apiMock.updateUser.mockReset().mockResolvedValue({ data: {} })
    confirmDangerSpy.mockReset()
  })

  it('reflects each user\'s disabled state from /user/list (badge shown only for disabled)', async () => {
    const wrapper = mountView()
    await flushPromises()
    const badges = wrapper.findAll('.badge-disabled')
    // Exactly one disabled user -> exactly one "Disabled" badge.
    expect(badges).toHaveLength(1)
    expect(badges[0].text()).toBe(en.disabled)
  })

  it('enabling a disabled user calls updateUser(username, { disabled: false }) with no danger confirm', async () => {
    const wrapper = mountView()
    await flushPromises()

    // Enable path is not destructive: it must NOT route through confirmDanger.
    ;(wrapper.vm as unknown as { toggleDisabled: (u: UserListItem) => void }).toggleDisabled(DISABLED_USER)
    await flushPromises()

    expect(confirmDangerSpy).not.toHaveBeenCalled()
    expect(apiMock.updateUser).toHaveBeenCalledTimes(1)
    expect(apiMock.updateUser).toHaveBeenCalledWith('bob', { disabled: false })
  })

  it('hides the disable/enable toggle for guest and admin rows, shows it for a normal row', async () => {
    apiMock.listUsers.mockReset().mockResolvedValue({
      data: { users: [ENABLED_USER, ADMIN_USER, GUEST_USER] },
    })
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as { canToggleDisabled: (u: UserListItem) => boolean }
    expect(vm.canToggleDisabled(ENABLED_USER)).toBe(true)
    expect(vm.canToggleDisabled(ADMIN_USER)).toBe(false)
    expect(vm.canToggleDisabled(GUEST_USER)).toBe(false)

    // DOM: exactly one row (the normal user) renders the disable-toggle button.
    const disableButtons = wrapper.findAll(
      `[aria-label="${en.ui.users.disable_user_btn}"], [aria-label="${en.ui.users.enable_user_btn}"]`,
    )
    expect(disableButtons).toHaveLength(1)
  })

  it('disabling an enabled user confirms first, then calls updateUser(username, { disabled: true })', async () => {
    const wrapper = mountView()
    await flushPromises()

    ;(wrapper.vm as unknown as { toggleDisabled: (u: UserListItem) => void }).toggleDisabled(ENABLED_USER)
    await flushPromises()

    // Disable is destructive-ish: it must confirm before mutating.
    expect(confirmDangerSpy).toHaveBeenCalledTimes(1)
    expect(apiMock.updateUser).not.toHaveBeenCalled()

    // Simulate the admin confirming the dialog.
    const opts = confirmDangerSpy.mock.calls[0][0] as { accept: () => void | Promise<void> }
    await opts.accept()
    await flushPromises()

    expect(apiMock.updateUser).toHaveBeenCalledTimes(1)
    expect(apiMock.updateUser).toHaveBeenCalledWith('alice', { disabled: true })
  })
})
