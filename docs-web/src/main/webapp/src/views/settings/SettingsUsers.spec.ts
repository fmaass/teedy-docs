import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'
import type { UserListItem } from '../../api/user'
import { formatStorage, BYTES_PER_GB } from '../../utils/formatters'

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

describe('SettingsUsers — delete with document reassignment (#55)', () => {
  beforeEach(() => {
    apiMock.listUsers.mockReset().mockResolvedValue({ data: { users: [ENABLED_USER, DISABLED_USER] } })
    apiMock.deleteUser.mockReset().mockResolvedValue({ data: { status: 'ok' } })
    confirmDangerSpy.mockReset()
  })

  it('deleting no longer routes through the plain danger-confirm (a reassign target is required)', async () => {
    const wrapper = mountView()
    await flushPromises()

    ;(wrapper.vm as unknown as { openDeleteDialog: (u: UserListItem) => void }).openDeleteDialog(ENABLED_USER)
    await flushPromises()

    // The delete flow opens the reassignment dialog, it does NOT fire the old confirmDanger.
    expect(confirmDangerSpy).not.toHaveBeenCalled()
    const vm = wrapper.vm as unknown as { showDeleteDialog: boolean; reassignToUsername: string | null }
    expect(vm.showDeleteDialog).toBe(true)
    expect(vm.reassignToUsername).toBeNull()
  })

  it('offers every active user except the one being deleted as a reassignment target', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openDeleteDialog: (u: UserListItem) => void
      reassignCandidates: UserListItem[]
    }
    vm.openDeleteDialog(ENABLED_USER)
    await flushPromises()

    const candidateNames = vm.reassignCandidates.map((u) => u.username)
    expect(candidateNames).not.toContain(ENABLED_USER.username)
    expect(candidateNames).toContain(DISABLED_USER.username)
  })

  it('deletes with the chosen target: calls deleteUser(username, reassignToUsername)', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openDeleteDialog: (u: UserListItem) => void
      reassignToUsername: string | null
      handleDelete: () => Promise<void>
      showDeleteDialog: boolean
    }
    vm.openDeleteDialog(ENABLED_USER)
    vm.reassignToUsername = 'bob'
    await vm.handleDelete()
    await flushPromises()

    expect(apiMock.deleteUser).toHaveBeenCalledTimes(1)
    expect(apiMock.deleteUser).toHaveBeenCalledWith('alice', 'bob')
    expect(vm.showDeleteDialog).toBe(false)
  })

  it('does NOT call deleteUser when no reassignment target is chosen', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openDeleteDialog: (u: UserListItem) => void
      handleDelete: () => Promise<void>
    }
    vm.openDeleteDialog(ENABLED_USER)
    // reassignToUsername left null.
    await vm.handleDelete()
    await flushPromises()

    expect(apiMock.deleteUser).not.toHaveBeenCalled()
  })
})

// #180: the reassignment prompt is shown only when the server says this account still owns documents
// or tags (`requires_reassign`). The fixtures above deliberately omit the flag, which must keep the
// old, safe behaviour (prompt shown) — those cases are covered by the block above.
describe('SettingsUsers — delete without reassignment (#180)', () => {
  const EMPTY_USER: UserListItem = { ...ENABLED_USER, requires_reassign: false }
  const OWNER_USER: UserListItem = { ...DISABLED_USER, requires_reassign: true }

  type DeleteVm = {
    openDeleteDialog: (u: UserListItem) => void
    handleDelete: () => Promise<void>
    deleteNeedsReassign: boolean
    reassignToUsername: string | null
    showDeleteDialog: boolean
  }

  beforeEach(() => {
    apiMock.listUsers.mockReset().mockResolvedValue({ data: { users: [EMPTY_USER, OWNER_USER] } })
    apiMock.deleteUser.mockReset().mockResolvedValue({ data: { status: 'ok' } })
    confirmDangerSpy.mockReset()
  })

  it('deletes a user that owns nothing with no reassignment target at all', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as DeleteVm
    vm.openDeleteDialog(EMPTY_USER)
    await flushPromises()
    expect(vm.deleteNeedsReassign).toBe(false)

    await vm.handleDelete()
    await flushPromises()

    // No target argument: the API call must omit reassign_to_username entirely.
    expect(apiMock.deleteUser).toHaveBeenCalledTimes(1)
    expect(apiMock.deleteUser).toHaveBeenCalledWith('alice', undefined)
    expect(vm.showDeleteDialog).toBe(false)
  })

  it('still requires a target for a user flagged as owning content', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as DeleteVm
    vm.openDeleteDialog(OWNER_USER)
    await flushPromises()
    expect(vm.deleteNeedsReassign).toBe(true)

    await vm.handleDelete()
    await flushPromises()

    expect(apiMock.deleteUser).not.toHaveBeenCalled()
  })

  it('renders the reassignment select only for a user that owns content', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as DeleteVm
    vm.openDeleteDialog(EMPTY_USER)
    await flushPromises()
    expect(document.querySelector('#reassign-target')).toBeNull()

    vm.showDeleteDialog = false
    await flushPromises()
    vm.openDeleteDialog(OWNER_USER)
    await flushPromises()
    expect(document.querySelector('#reassign-target')).not.toBeNull()
  })

  it('re-reads the user list when the delete dialog opens', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(apiMock.listUsers).toHaveBeenCalledTimes(1)

    ;(wrapper.vm as unknown as DeleteVm).openDeleteDialog(EMPTY_USER)
    await flushPromises()

    expect(apiMock.listUsers).toHaveBeenCalledTimes(2)
  })

  it('shows the reassignment prompt when the flag changed since the list was cached', async () => {
    // The list was cached while the account still owned nothing; by the time the admin opens the
    // delete dialog it owns a document. The re-read on open must flip the dialog to the prompt —
    // otherwise a destructive dialog is shaped by arbitrarily old data.
    apiMock.listUsers
      .mockReset()
      .mockResolvedValueOnce({ data: { users: [EMPTY_USER, OWNER_USER] } })
      .mockResolvedValue({ data: { users: [{ ...EMPTY_USER, requires_reassign: true }, OWNER_USER] } })
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as DeleteVm
    vm.openDeleteDialog(EMPTY_USER)
    await flushPromises()

    expect(vm.deleteNeedsReassign).toBe(true)
    expect(document.querySelector('#reassign-target')).not.toBeNull()
  })

  it('re-prompts for a target when the server refuses with ReassignRequired (race)', async () => {
    // The account acquired a document between the list fetch and the delete: the delete is refused
    // with the typed error, and the dialog must switch to the reassignment prompt rather than close.
    apiMock.deleteUser.mockReset().mockRejectedValue({ response: { data: { type: 'ReassignRequired' } } })
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as DeleteVm
    vm.openDeleteDialog(EMPTY_USER)
    await flushPromises()
    await vm.handleDelete()
    await flushPromises()

    expect(apiMock.deleteUser).toHaveBeenCalledWith('alice', undefined)
    expect(vm.showDeleteDialog).toBe(true)
    expect(vm.deleteNeedsReassign).toBe(true)

    // With a target chosen, the retry sends it.
    apiMock.deleteUser.mockReset().mockResolvedValue({ data: { status: 'ok' } })
    vm.reassignToUsername = 'bob'
    await vm.handleDelete()
    await flushPromises()

    expect(apiMock.deleteUser).toHaveBeenCalledWith('alice', 'bob')
    expect(vm.showDeleteDialog).toBe(false)
  })
})

describe('SettingsUsers — storage quota field', () => {
  beforeEach(() => {
    apiMock.listUsers.mockReset().mockResolvedValue({ data: { users: [ENABLED_USER] } })
    apiMock.createUser.mockReset().mockResolvedValue({ data: {} })
    apiMock.updateUser.mockReset().mockResolvedValue({ data: {} })
    confirmDangerSpy.mockReset()
  })

  it('the create dialog sends storage_quota (defaulting to ~1GB) on create', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openAddDialog: () => void
      addForm: { username: string; email: string; password: string; storage_quota: number }
      handleAdd: () => Promise<void>
    }
    vm.openAddDialog()
    // The create default is the current ~1GB hardcoded value, now surfaced as a field.
    expect(vm.addForm.storage_quota).toBe(1000000000)
    vm.addForm.username = 'carol'
    vm.addForm.email = 'carol@x.com'
    vm.addForm.password = 'Password1'
    vm.addForm.storage_quota = 2000000000
    await vm.handleAdd()
    await flushPromises()

    expect(apiMock.createUser).toHaveBeenCalledTimes(1)
    // createUser(username, password, email, storageQuota)
    expect(apiMock.createUser).toHaveBeenCalledWith('carol', 'Password1', 'carol@x.com', 2000000000)
  })

  it('the edit dialog pre-fills the current quota and sends it on save', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openEditDialog: (u: UserListItem) => void
      editForm: { email: string; password: string; storage_quota: number }
      handleEdit: () => Promise<void>
    }
    vm.openEditDialog(ENABLED_USER)
    // Pre-filled from the user's current storage_quota.
    expect(vm.editForm.storage_quota).toBe(ENABLED_USER.storage_quota)
    vm.editForm.storage_quota = 5000000000
    await vm.handleEdit()
    await flushPromises()

    expect(apiMock.updateUser).toHaveBeenCalledTimes(1)
    expect(apiMock.updateUser).toHaveBeenCalledWith('alice', {
      email: 'alice@x.com',
      storage_quota: 5000000000,
    })
  })

  // Issue #49: the quota field is entered in GB; the API contract stays bytes. The
  // GB<->bytes conversion happens only at the UI boundary, using the SAME binary GB
  // basis (1024^3) that formatStorage displays — so the entered value reads back
  // identically wherever it is shown.
  it('converts a GB entry to bytes on create (5 GB -> 5 * 1024^3 bytes)', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openAddDialog: () => void
      addForm: { username: string; email: string; password: string; storage_quota: number }
      addQuotaGb: number
      handleAdd: () => Promise<void>
    }
    vm.openAddDialog()
    // Default bytes surface as their binary-GB equivalent in the field.
    expect(vm.addQuotaGb).toBeCloseTo(1000000000 / BYTES_PER_GB, 6)
    vm.addForm.username = 'carol'
    vm.addForm.email = 'carol@x.com'
    vm.addForm.password = 'Password1'
    // Admin enters 5 GB; the API must receive the binary byte-equivalent.
    ;(vm as unknown as { addQuotaGb: number }).addQuotaGb = 5
    expect(vm.addForm.storage_quota).toBe(5 * BYTES_PER_GB)
    await vm.handleAdd()
    await flushPromises()

    expect(apiMock.createUser).toHaveBeenCalledWith('carol', 'Password1', 'carol@x.com', 5 * BYTES_PER_GB)
  })

  // The consistency guard: the bytes produced by entering N GB, when passed back
  // through formatStorage (the display path), must render as "N.0 GB". This locks the
  // input basis to the display basis so a decimal/binary mismatch cannot recur.
  it('entered GB reads back as the same GB via formatStorage (input/display consistency)', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openAddDialog: () => void
      addForm: { storage_quota: number }
      addQuotaGb: number
    }
    vm.openAddDialog()
    ;(vm as unknown as { addQuotaGb: number }).addQuotaGb = 5
    expect(formatStorage(vm.addForm.storage_quota)).toBe('5.0 GB')
  })

  it('shows the existing quota in GB when editing and preserves exact bytes on an untouched save', async () => {
    // A non-round byte value that has no exact GB representation: an untouched save
    // must NOT re-multiply it back through GB (which would drift the stored bytes).
    // 5 GB + 243 B displays as 5.00 GB but is not a whole number of GB.
    const oddBytes = 5 * BYTES_PER_GB + 243
    const ODD_USER: UserListItem = { ...ENABLED_USER, storage_quota: oddBytes }
    apiMock.listUsers.mockReset().mockResolvedValue({ data: { users: [ODD_USER] } })
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openEditDialog: (u: UserListItem) => void
      editForm: { email: string; password: string; storage_quota: number }
      editQuotaGb: number
      handleEdit: () => Promise<void>
    }
    vm.openEditDialog(ODD_USER)
    // Field shows the bytes converted to GB for display.
    expect(vm.editQuotaGb).toBeCloseTo(oddBytes / BYTES_PER_GB, 6)
    // InputNumber re-emits the rounded display value (5) without a real change;
    // the exact stored bytes must survive.
    ;(vm as unknown as { editQuotaGb: number }).editQuotaGb = 5
    expect(vm.editForm.storage_quota).toBe(oddBytes)
    await vm.handleEdit()
    await flushPromises()

    expect(apiMock.updateUser).toHaveBeenCalledWith('alice', {
      email: 'alice@x.com',
      storage_quota: oddBytes,
    })
  })

  it('converts a changed GB entry to bytes on edit (2.5 GB -> 2.5 * 1024^3 bytes)', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openEditDialog: (u: UserListItem) => void
      editForm: { email: string; password: string; storage_quota: number }
      editQuotaGb: number
      handleEdit: () => Promise<void>
    }
    vm.openEditDialog(ENABLED_USER)
    ;(vm as unknown as { editQuotaGb: number }).editQuotaGb = 2.5
    expect(vm.editForm.storage_quota).toBe(2.5 * BYTES_PER_GB)
    await vm.handleEdit()
    await flushPromises()

    expect(apiMock.updateUser).toHaveBeenCalledWith('alice', {
      email: 'alice@x.com',
      storage_quota: 2.5 * BYTES_PER_GB,
    })
  })
})
