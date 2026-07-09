import { describe, it, expect, beforeEach, vi } from 'vitest'

// --- Dependency mock (NOT the unit under test) ---
//
// useConfirmDanger wraps PrimeVue's useConfirm. We mock useConfirm and assert the
// options object our composable hands to confirm.require — that it always applies
// the shared danger/secondary props, defaults the icon to trash, and passes
// header/message/accept (and an icon override) straight through.

const requireMock = vi.fn()
vi.mock('primevue/useconfirm', () => ({
  useConfirm: () => ({ require: requireMock }),
}))

import { useConfirmDanger } from './useConfirmDanger'

beforeEach(() => requireMock.mockReset())

describe('useConfirmDanger', () => {
  it('applies the shared danger + secondary-outlined props and default trash icon', () => {
    const { confirmDanger } = useConfirmDanger()
    const accept = () => {}
    confirmDanger({ header: 'Delete', message: 'Are you sure?', accept })

    expect(requireMock).toHaveBeenCalledTimes(1)
    const opts = requireMock.mock.calls[0][0]
    expect(opts.header).toBe('Delete')
    expect(opts.message).toBe('Are you sure?')
    expect(opts.icon).toBe('pi pi-trash')
    expect(opts.acceptProps).toEqual({ severity: 'danger' })
    expect(opts.rejectProps).toEqual({ severity: 'secondary', outlined: true })
    expect(opts.accept).toBe(accept)
  })

  it('honours an icon override for non-delete destructive actions', () => {
    const { confirmDanger } = useConfirmDanger()
    confirmDanger({ header: 'Disable 2FA', message: 'x', icon: 'pi pi-shield', accept: () => {} })

    expect(requireMock.mock.calls[0][0].icon).toBe('pi pi-shield')
  })

  it('forwards a reject handler only when provided', () => {
    const { confirmDanger } = useConfirmDanger()
    const reject = () => {}
    confirmDanger({ header: 'h', message: 'm', accept: () => {}, reject })
    expect(requireMock.mock.calls[0][0].reject).toBe(reject)

    requireMock.mockReset()
    confirmDanger({ header: 'h', message: 'm', accept: () => {} })
    expect('reject' in requireMock.mock.calls[0][0]).toBe(false)
  })
})
