import { describe, it, expect } from 'vitest'
import { reactive, nextTick } from 'vue'
import { useInboxTestResult } from './useInboxTestResult'

// The inbox test result must always describe the CURRENTLY-SAVED config: any form
// edit (dirty) and any plain save must clear a previously shown count/error.
// Programmatic mutations that are part of the save flow itself (clearing the
// password field after a successful save) must NOT wipe the result just set.

function makeForm() {
  return reactive({ hostname: 'imap.x', port: 993, password: '' })
}

describe('useInboxTestResult', () => {
  it('starts with no result', () => {
    const { testCount, testError } = useInboxTestResult(makeForm())
    expect(testCount.value).toBeNull()
    expect(testError.value).toBeNull()
  })

  it('setCount shows a success count and clears any error', () => {
    const r = useInboxTestResult(makeForm())
    r.setError('test')
    r.setCount(5)
    expect(r.testCount.value).toBe(5)
    expect(r.testError.value).toBeNull()
  })

  it('setError records WHICH step failed and clears any count', () => {
    const r = useInboxTestResult(makeForm())
    r.setCount(5)
    r.setError('save')
    expect(r.testError.value).toBe('save')
    expect(r.testCount.value).toBeNull()
  })

  it('clears a shown result when the form becomes dirty', async () => {
    const form = makeForm()
    const r = useInboxTestResult(form)
    r.setCount(5)
    form.hostname = 'imap.other'
    await nextTick()
    expect(r.testCount.value).toBeNull()
    expect(r.testError.value).toBeNull()
  })

  it('clears a shown error when the form becomes dirty', async () => {
    const form = makeForm()
    const r = useInboxTestResult(form)
    r.setError('test')
    form.port = 143
    await nextTick()
    expect(r.testError.value).toBeNull()
  })

  it('clear() wipes the result (plain-save path)', () => {
    const r = useInboxTestResult(makeForm())
    r.setCount(2)
    r.clear()
    expect(r.testCount.value).toBeNull()
    expect(r.testError.value).toBeNull()
  })

  it('mutateSilently does NOT clear a result set right after it (password wipe on save)', async () => {
    const form = makeForm()
    form.password = 'secret'
    const r = useInboxTestResult(form)
    await r.mutateSilently(() => { form.password = '' })
    r.setCount(4)
    await nextTick()
    expect(r.testCount.value).toBe(4)
  })

  it('a USER edit after mutateSilently still clears the result', async () => {
    const form = makeForm()
    form.password = 'secret'
    const r = useInboxTestResult(form)
    await r.mutateSilently(() => { form.password = '' })
    r.setCount(4)
    form.hostname = 'changed'
    await nextTick()
    expect(r.testCount.value).toBeNull()
  })
})
