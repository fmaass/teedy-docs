import { describe, it, expect, vi } from 'vitest'
import { isSaveAndTestDisabled, runSaveThenTest, SaveThenTestError } from './inboxTestFlow'

describe('isSaveAndTestDisabled', () => {
  it('is enabled when idle and inbox is enabled', () => {
    expect(isSaveAndTestDisabled({ saving: false, testing: false, enabled: true })).toBe(false)
  })

  it('is disabled while saving', () => {
    expect(isSaveAndTestDisabled({ saving: true, testing: false, enabled: true })).toBe(true)
  })

  it('is disabled while testing', () => {
    expect(isSaveAndTestDisabled({ saving: false, testing: true, enabled: true })).toBe(true)
  })

  it('is disabled when the inbox is disabled in the form', () => {
    expect(isSaveAndTestDisabled({ saving: false, testing: false, enabled: false })).toBe(true)
  })
})

describe('runSaveThenTest', () => {
  it('saves BEFORE testing (save-then-test order)', async () => {
    const order: string[] = []
    const save = vi.fn(async () => { order.push('save') })
    const test = vi.fn(async () => { order.push('test'); return { count: 3 } })
    const result = await runSaveThenTest(save, test)
    expect(order).toEqual(['save', 'test'])
    expect(result).toEqual({ count: 3 })
  })

  it('does NOT test when the save fails', async () => {
    const save = vi.fn(async () => { throw new Error('save failed') })
    const test = vi.fn(async () => ({ count: 0 }))
    await expect(runSaveThenTest(save, test)).rejects.toThrow()
    expect(test).not.toHaveBeenCalled()
  })

  // The caller must be able to tell WHICH step failed: a save failure means the test
  // never ran, and the UI must not report it as a failed connection test.
  it('labels a save failure with step "save" — NEVER as a test failure', async () => {
    const saveCause = new Error('validation rejected')
    const save = vi.fn(async () => { throw saveCause })
    const test = vi.fn(async () => ({ count: 0 }))
    let caught: unknown
    try {
      await runSaveThenTest(save, test)
    } catch (e) {
      caught = e
    }
    expect(caught).toBeInstanceOf(SaveThenTestError)
    const err = caught as SaveThenTestError
    expect(err.step).toBe('save')
    expect(err.step).not.toBe('test')
    expect(err.cause).toBe(saveCause)
  })

  it('labels a test failure (after a successful save) with step "test"', async () => {
    const testCause = new Error('imap unreachable')
    const save = vi.fn(async () => undefined)
    const test = vi.fn(async () => { throw testCause })
    let caught: unknown
    try {
      await runSaveThenTest(save, test)
    } catch (e) {
      caught = e
    }
    expect(caught).toBeInstanceOf(SaveThenTestError)
    const err = caught as SaveThenTestError
    expect(err.step).toBe('test')
    expect(err.cause).toBe(testCause)
    expect(save).toHaveBeenCalledOnce()
  })
})
