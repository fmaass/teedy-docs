import { describe, it, expect } from 'vitest'
import { ref, nextTick } from 'vue'
import { useClampedOffset, type ClampablePage } from './useClampedOffset'

const PAGE_SIZE = 20

function harness(initial?: ClampablePage) {
  const data = ref<ClampablePage | undefined>(initial)
  const isLoading = ref(false)
  const pageOffset = ref(0)
  useClampedOffset(data, isLoading, pageOffset, PAGE_SIZE)
  return { data, isLoading, pageOffset }
}

function rows(n: number): unknown[] {
  return Array.from({ length: n })
}

describe('useClampedOffset', () => {
  it('clamps a stranded deep page down to the last valid page', async () => {
    const h = harness()
    h.pageOffset.value = 40
    // Refetch for offset 40 returns 0 rows but 40 items still exist -> last page is offset 20.
    h.data.value = { documents: rows(0), total: 40 }
    await nextTick()
    expect(h.pageOffset.value).toBe(20)
  })

  it('leaves a mid-page delete untouched (rows still present)', async () => {
    const h = harness()
    h.pageOffset.value = 20
    h.data.value = { documents: rows(19), total: 39 }
    await nextTick()
    expect(h.pageOffset.value).toBe(20)
  })

  it('does not clamp while loading (keep-previous phase)', async () => {
    const h = harness()
    h.pageOffset.value = 40
    h.isLoading.value = true
    h.data.value = { documents: rows(0), total: 40 }
    await nextTick()
    expect(h.pageOffset.value).toBe(40)
  })

  it('never clamps below offset 0', async () => {
    const h = harness()
    h.pageOffset.value = 0
    h.data.value = { documents: rows(0), total: 0 }
    await nextTick()
    expect(h.pageOffset.value).toBe(0)
  })

  it('treats an undefined data emission as zero rows / zero total (no clamp from 0)', async () => {
    const h = harness({ documents: rows(20), total: 100 })
    h.pageOffset.value = 0
    h.data.value = undefined
    await nextTick()
    expect(h.pageOffset.value).toBe(0)
  })
})
