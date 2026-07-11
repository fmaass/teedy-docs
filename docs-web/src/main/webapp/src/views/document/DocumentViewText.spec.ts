import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import PrimeVue from 'primevue/config'
import Skeleton from 'primevue/skeleton'
import { DocumentKey } from './documentKey'

// #32: the per-file PROCESSING indicator (OCR / text extraction running) must render
// content-shaped skeletons WITH a preserved, accessible processing label — not a
// spinner. The APIs are dependencies (mocked); the unit under test is the
// processing-state template.

const getFileContentMock = vi.fn()
const getFileListMock = vi.fn()
vi.mock('../../api/file', () => ({
  getFileContent: (...a: unknown[]) => getFileContentMock(...a),
  getFileList: (...a: unknown[]) => getFileListMock(...a),
  reprocessFile: vi.fn(),
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))

import DocumentViewText from './DocumentViewText.vue'

function makeDocWithProcessingFile() {
  return {
    id: 'doc-1',
    files: [
      { id: 'file-1', name: 'scan.pdf', mimetype: 'application/pdf', processing: true },
    ],
  } as unknown as import('../../api/document').DocumentDetail
}

function mountView(docValue: ReturnType<typeof makeDocWithProcessingFile>) {
  return mount(DocumentViewText, {
    global: {
      plugins: [PrimeVue],
      provide: { [DocumentKey as symbol]: ref(docValue) },
      stubs: { Button: true, EmptyState: true },
      directives: { tooltip: {} },
    },
  })
}

describe('DocumentViewText — processing indicator (#32)', () => {
  beforeEach(() => {
    getFileContentMock.mockReset()
    getFileListMock.mockReset()
    // Content load never resolves — keep the file in a stable processing state.
    getFileContentMock.mockReturnValue(new Promise(() => {}))
    getFileListMock.mockResolvedValue([
      { id: 'file-1', name: 'scan.pdf', mimetype: 'application/pdf', processing: true },
    ])
  })

  it('renders content-shaped skeletons for a processing file (no spinner)', async () => {
    const wrapper = mountView(makeDocWithProcessingFile())
    await flushPromises()
    const block = wrapper.find('.file-text-processing')
    expect(block.exists()).toBe(true)
    // Content-shaped: multiple skeleton lines inside the processing block.
    const skeletons = block.findAllComponents(Skeleton)
    expect(skeletons.length).toBeGreaterThan(1)
  })

  it('preserves an accessible, visible processing label beside the skeletons', async () => {
    const wrapper = mountView(makeDocWithProcessingFile())
    await flushPromises()
    const block = wrapper.find('.file-text-processing')
    // Visible label text (the i18n key, since t() echoes the key in this harness).
    expect(block.text()).toContain('ui.processing')
    // aria-live status so screen readers announce the processing state.
    expect(block.attributes('role')).toBe('status')
    expect(block.attributes('aria-live')).toBe('polite')
  })

  it('renders the HEADER processing badge (status chip) with its accessible label while processing', async () => {
    // The header badge is a status chip on already-rendered content (the file-name
    // row), distinct from the content-area placeholder block — a Skeleton would be
    // semantically wrong there. It must announce the processing state accessibly
    // (role=status + aria-live) and show the visible label plus the spin icon.
    const wrapper = mountView(makeDocWithProcessingFile())
    await flushPromises()
    const badge = wrapper.find('.status-badge.status-processing')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('ui.processing')
    expect(badge.attributes('role')).toBe('status')
    expect(badge.attributes('aria-live')).toBe('polite')
    // The animated icon is decorative (aria-hidden) beside the visible text.
    const icon = badge.find('.pi-spin')
    expect(icon.exists()).toBe(true)
    expect(icon.attributes('aria-hidden')).toBe('true')
  })
})
