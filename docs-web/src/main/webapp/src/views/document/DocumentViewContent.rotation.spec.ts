import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ConfirmationService from 'primevue/confirmationservice'
import ToastService from 'primevue/toastservice'
import type { DocumentDetail } from '../../api/document'
import { DocumentKey } from './documentKey'

// The view derives its per-user file-view-mode key from the auth store, so every mount
// needs an active pinia (no user → anonymous username → grid default, where the rotation
// preview cards live).
beforeEach(() => setActivePinia(createPinia()))

// v3.5.2 — PERSISTED, non-destructive IMAGE rotation, per file card. The API modules are
// DEPENDENCIES (mocked); the unit under test is DocumentViewContent's image preview rotation
// behavior: each card persists its own file's rotation via setRotation, computed as the ABSOLUTE
// next value from the persisted rotation (never compounding), and the served _web raster is
// physically rotated server-side so NO CSS transform is applied on the persisted path.

const setRotationMock = vi.fn(() => Promise.resolve({ data: { status: 'ok' } }))
const invalidateMock = vi.fn()
vi.mock('../../api/file', () => ({
  getFileUrl: (id: string, size?: string, _shareId?: string, rotation?: number) => {
    const params = new URLSearchParams()
    if (size) params.set('size', size)
    if ((size === 'web' || size === 'thumb') && rotation) params.set('v', String(rotation))
    const suffix = params.toString()
    return `/api/file/${id}/data${suffix ? `?${suffix}` : ''}`
  },
  setRotation: (...a: unknown[]) => setRotationMock(...a),
  deleteFile: vi.fn(),
  renameFile: vi.fn(),
  uploadFile: vi.fn(),
}))
vi.mock('vue-i18n', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (k: string) => k }),
}))
// PdfViewer pulls in the real pdf.js (needs DOMMatrix/canvas, absent in jsdom).
vi.mock('../../components/PdfViewer.vue', () => ({
  default: { name: 'PdfViewer', render: () => null },
}))
vi.mock('@tanstack/vue-query', () => ({
  useQueryClient: () => ({ invalidateQueries: invalidateMock }),
}))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))

const objectUrls = new Map<string, string>()
vi.stubGlobal('URL', {
  ...URL,
  createObjectURL: (blob: Blob) => {
    const tag = (blob as unknown as { _tag?: string })?._tag ?? 'blob:test'
    return tag
  },
  revokeObjectURL: () => {},
})
vi.mock('../../composables/usePreviewQueue', () => ({
  usePreviewQueue: () => ({
    enqueue: (fileId: string, size?: string, _p?: number, _s?: string, rotation?: number) => {
      const params = new URLSearchParams()
      if (size) params.set('size', size)
      if ((size === 'web' || size === 'thumb') && rotation) params.set('v', String(rotation))
      const suffix = params.toString()
      const url = `/api/file/${fileId}/data${suffix ? `?${suffix}` : ''}`
      const blob = new Blob(['x'])
      ;(blob as unknown as { _tag: string })._tag = url
      return Promise.resolve(blob)
    },
    cancel: () => {},
    reprioritize: () => {},
  }),
}))

import DocumentViewContent from './DocumentViewContent.vue'

function makeDoc(id: string, rotations: [number?, number?] = [undefined, undefined]): DocumentDetail {
  return {
    id,
    title: 'Doc',
    writable: true,
    files: [
      { id: 'img-a', name: 'a.png', mimetype: 'image/png', size: 10, rotation: rotations[0] },
      { id: 'img-b', name: 'b.png', mimetype: 'image/png', size: 20, rotation: rotations[1] },
    ],
  } as unknown as DocumentDetail
}

function mountContent(docRef: ReturnType<typeof ref<DocumentDetail | undefined>>) {
  return mount(DocumentViewContent, {
    global: {
      plugins: [PrimeVue, ConfirmationService, ToastService],
      provide: { [DocumentKey as symbol]: docRef },
      stubs: {
        PdfViewer: true,
        EmptyState: true,
        FileVersionsDialog: true,
        FileUpload: true,
        CameraCaptureButton: true,
        UploadProgressList: true,
        InputText: true,
        // No @click emit: the parent's `@click` handler falls through natively.
        Button: {
          props: ['icon', 'ariaLabel', 'disabled'],
          template: '<button :aria-label="ariaLabel" :disabled="disabled"></button>',
        },
      },
      directives: { tooltip: {} },
    },
  })
}

describe('DocumentViewContent persisted image rotation', () => {
  beforeEach(() => {
    setRotationMock.mockClear().mockResolvedValue({ data: { status: 'ok' } })
    invalidateMock.mockClear()
  })

  it('persists rotation per card without cross-talk between siblings', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc('doc-1'))
    const wrapper = mountContent(docRef)
    await flushPromises()

    const cards = wrapper.findAll('.file-preview-card')
    expect(cards.length).toBe(2)

    // Rotate the FIRST card right (from 0 → 90). Only img-a is persisted.
    await cards[0].get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()
    expect(setRotationMock).toHaveBeenCalledTimes(1)
    expect(setRotationMock).toHaveBeenLastCalledWith('img-a', 90)

    // Rotate the SECOND card left (from 0 → 270). Only img-b is persisted.
    await cards[1].get('[aria-label="ui.rotate_left"]').trigger('click')
    await flushPromises()
    expect(setRotationMock).toHaveBeenLastCalledWith('img-b', 270)
  })

  it('computes the next value as the ABSOLUTE rotation from the persisted value (no compounding)', async () => {
    // The card's persisted rotation is 180; a rotate-right must send 270, not accumulate from 0.
    const docRef = ref<DocumentDetail | undefined>(makeDoc('doc-1', [180, undefined]))
    const wrapper = mountContent(docRef)
    await flushPromises()

    const card = wrapper.findAll('.file-preview-card')[0]
    await card.get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()
    expect(setRotationMock).toHaveBeenLastCalledWith('img-a', 270)
  })

  it('does NOT apply a CSS rotate transform on the persisted path (server bakes the raster)', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc('doc-1', [90, undefined]))
    const wrapper = mountContent(docRef)
    await flushPromises()

    const img = wrapper.findAll('.rotatable-image')[0]
    expect(img.attributes('style') ?? '').not.toContain('rotate')
    // The rotation is carried by the URL cache-bust key instead.
    expect(img.attributes('src')).toBe('/api/file/img-a/data?size=web&v=90')
  })

  it('invalidates the document AND the documents list after persisting a rotation', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc('doc-1'))
    const wrapper = mountContent(docRef)
    await flushPromises()

    await wrapper.findAll('.file-preview-card')[0].get('[aria-label="ui.rotate_right"]').trigger('click')
    await flushPromises()
    const keys = invalidateMock.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey)
    expect(keys).toContainEqual(['document', 'doc-1'])
    // Every list consumer (gallery/table/slide-over) must refetch to pick up the new file_rotation.
    expect(keys).toContainEqual(['documents'])
  })

  it('renders each card at its persisted rotation cache-bust when the document changes', async () => {
    const docRef = ref<DocumentDetail | undefined>(makeDoc('doc-1', [90, undefined]))
    const wrapper = mountContent(docRef)
    await flushPromises()
    expect(wrapper.findAll('.rotatable-image')[0].attributes('src')).toBe(
      '/api/file/img-a/data?size=web&v=90',
    )

    // Switch to a different document whose main file is upright — the served URL follows the new
    // persisted value (no leftover cache-bust from the previous document).
    docRef.value = makeDoc('doc-2', [0, undefined])
    await flushPromises()
    expect(wrapper.findAll('.rotatable-image')[0].attributes('src')).toBe('/api/file/img-a/data?size=web')
  })
})
