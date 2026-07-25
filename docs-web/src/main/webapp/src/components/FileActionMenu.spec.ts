import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FileActionMenu from './FileActionMenu.vue'

// The per-file action menu is the reusable surface the file list (and, later,
// #73 "Edit pages" / #117 "Upload new version") mount their per-file actions onto.
// Its load-bearing contract: preview + copy-link + download + version history are always
// available (read actions), while rename + delete + the cover action are gated on `writable`,
// and an `extra` slot lets callers inject more writable-only actions. The cover action toggles
// between "set as cover" (when this file is not the cover) and "remove as cover" (when it is).
// t() is stubbed to the key (with the interpolated name appended) so assertions target the
// stable aria-label keys, not copy. getFileUrl/buildFileLink are dependencies, stubbed
// deterministically.
vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (k: string, p?: Record<string, unknown>) => (p ? `${k}:${p.name}` : k),
  }),
}))
vi.mock('../api/file', () => ({
  getFileUrl: (id: string) => `/api/file/${id}/data`,
  buildFileLink: (documentId: string, fileId: string) => `https://app/#/document/view/${documentId}/content?file=${fileId}`,
}))
const toastAdd = vi.fn()
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: toastAdd }) }))

const file = { id: 'f1', name: 'report.pdf', mimetype: 'application/pdf' }
const PREVIEW_LABEL = 'ui.file_view.open_file:report.pdf'

function mountMenu(
  writable: boolean,
  slots: Record<string, unknown> = {},
  isCover = false,
  target: { id: string; name: string | null; mimetype: string } = file,
) {
  return mount(FileActionMenu, {
    props: { file: target, writable, isCover, documentId: 'doc-7' },
    global: { directives: { tooltip: {} } },
    slots,
  })
}

// A writeText that resolves/rejects on demand, installed on a navigator.clipboard that
// jsdom does not provide at all. `configurable` so each test can replace it — and so the
// "no clipboard API at all" case (an http origin, which is NOT a secure context) can
// delete it again.
function stubClipboard(writeText: ((text: string) => Promise<void>) | null) {
  if (writeText === null) {
    Reflect.deleteProperty(navigator, 'clipboard')
    return
  }
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
    writable: true,
  })
}

async function clickCopyLink(wrapper: ReturnType<typeof mountMenu>) {
  await wrapper
    .findAll('button')
    .find((b) => b.attributes('aria-label') === 'ui.file_view.copy_link')!
    .trigger('click')
  await flushPromises()
}

function labels(wrapper: ReturnType<typeof mountMenu>) {
  return wrapper.findAll('button').map((b) => b.attributes('aria-label'))
}

describe('FileActionMenu', () => {
  it('writable, not the cover: exposes history, preview, copy-link, set-as-cover, move, rename and delete', () => {
    const wrapper = mountMenu(true)
    expect(labels(wrapper)).toEqual([
      'ui.versions.title',
      PREVIEW_LABEL,
      'ui.file_view.copy_link',
      'ui.set_as_cover',
      'ui.move_file',
      'rename',
      'ui.remove_file',
    ])
  })

  it('writable AND the current cover: offers remove-as-cover instead of set-as-cover', () => {
    const wrapper = mountMenu(true, {}, true)
    expect(labels(wrapper)).toEqual([
      'ui.versions.title',
      PREVIEW_LABEL,
      'ui.file_view.copy_link',
      'ui.remove_as_cover',
      'ui.move_file',
      'rename',
      'ui.remove_file',
    ])
  })

  it('read-only: exposes ONLY the read actions — no cover action, no rename, no delete', () => {
    expect(labels(mountMenu(false))).toEqual([
      'ui.versions.title',
      PREVIEW_LABEL,
      'ui.file_view.copy_link',
    ])
    // Even when this file is the cover, a read-only viewer gets no cover mutation.
    expect(labels(mountMenu(false, {}, true))).toEqual([
      'ui.versions.title',
      PREVIEW_LABEL,
      'ui.file_view.copy_link',
    ])
  })

  it('emits versions/rename/delete with the file when the buttons are clicked', async () => {
    const wrapper = mountMenu(true)
    const byLabel = (l: string) =>
      wrapper.findAll('button').find((b) => b.attributes('aria-label') === l)!

    await byLabel('ui.versions.title').trigger('click')
    await byLabel('rename').trigger('click')
    await byLabel('ui.remove_file').trigger('click')

    expect(wrapper.emitted('versions')?.[0]).toEqual([file])
    expect(wrapper.emitted('rename')?.[0]).toEqual([file])
    expect(wrapper.emitted('delete')?.[0]).toEqual([file])
  })

  it('emits setCover when not the cover, clearCover when it is', async () => {
    const notCover = mountMenu(true)
    await notCover.findAll('button').find((b) => b.attributes('aria-label') === 'ui.set_as_cover')!.trigger('click')
    expect(notCover.emitted('setCover')?.[0]).toEqual([file])
    expect(notCover.emitted('clearCover')).toBeUndefined()

    const cover = mountMenu(true, {}, true)
    await cover.findAll('button').find((b) => b.attributes('aria-label') === 'ui.remove_as_cover')!.trigger('click')
    expect(cover.emitted('clearCover')?.[0]).toEqual([file])
    expect(cover.emitted('setCover')).toBeUndefined()
  })

  it('emits move with the file, and only when writable', async () => {
    const wrapper = mountMenu(true)
    await wrapper.findAll('button').find((b) => b.attributes('aria-label') === 'ui.move_file')!.trigger('click')
    expect(wrapper.emitted('move')?.[0]).toEqual([file])
    // A read-only viewer never sees the move action.
    expect(labels(mountMenu(false))).not.toContain('ui.move_file')
  })

  // #178 — preview and download are READ actions: they live above the writable gate, so a
  // read-only viewer (and a share recipient's host view) keeps both.
  it('emits preview with the file, in both writable and read-only mode', async () => {
    const writable = mountMenu(true)
    await writable.findAll('button').find((b) => b.attributes('aria-label') === PREVIEW_LABEL)!.trigger('click')
    expect(writable.emitted('preview')?.[0]).toEqual([file])

    const readOnly = mountMenu(false)
    await readOnly.findAll('button').find((b) => b.attributes('aria-label') === PREVIEW_LABEL)!.trigger('click')
    expect(readOnly.emitted('preview')?.[0]).toEqual([file])
  })

  it('offers exactly one Download anchor to the ORIGINAL file, writable or not', () => {
    for (const writable of [true, false]) {
      const wrapper = mountMenu(writable)
      const anchors = wrapper.findAll('a')
      expect(anchors.length).toBe(1)
      const anchor = anchors[0]
      expect(anchor.attributes('href')).toBe('/api/file/f1/data')
      // No size=… variant: Download must serve the original bytes, not a derived raster.
      expect(anchor.attributes('href')).not.toContain('size=')
      expect(anchor.attributes('download')).toBe('report.pdf')
      // The exact label the relaxed e2e invariant keys on: an unlabelled /data link is a defect.
      expect(anchor.attributes('aria-label')).toBe('download')
    }
  })

  it('a null-named file falls back to the untitled label and an empty download filename', () => {
    const unnamed = { id: 'f9', name: null, mimetype: 'application/octet-stream' }
    const wrapper = mountMenu(true, {}, false, unnamed)
    expect(labels(wrapper)).toContain('ui.file_view.open_file:ui.file_view.untitled')
    const anchor = wrapper.find('a')
    expect(anchor.attributes('download')).toBe('')
    expect(anchor.attributes('href')).toBe('/api/file/f9/data')
  })

  // #192 — copy link. A READ action: it sits above the writable gate, between preview and
  // download, so a read-only viewer can hand the exact file to a colleague. The recipient's
  // authorization is unchanged (the document's own READ grant) — the link carries no token.
  describe('copy link (#192)', () => {
    beforeEach(() => {
      toastAdd.mockReset()
      stubClipboard(() => Promise.resolve())
    })

    it('sits between preview and download, in both writable and read-only mode', () => {
      for (const writable of [true, false]) {
        const wrapper = mountMenu(writable)
        // The whole cluster in DOM order, anchors included — placement is the assertion.
        const order = wrapper
          .findAll('button, a')
          .map((el) => el.attributes('aria-label'))
        const preview = order.indexOf(PREVIEW_LABEL)
        const copy = order.indexOf('ui.file_view.copy_link')
        const download = order.indexOf('download')
        expect(copy, `writable=${writable}: copy link is rendered`).toBeGreaterThan(-1)
        expect(copy, `writable=${writable}: copy link follows preview`).toBeGreaterThan(preview)
        expect(copy, `writable=${writable}: copy link precedes download`).toBeLessThan(download)
      }
    })

    it('writes the buildFileLink URL for THIS document/file pair to the clipboard', async () => {
      const writeText = vi.fn(() => Promise.resolve())
      stubClipboard(writeText)
      await clickCopyLink(mountMenu(true))
      expect(writeText).toHaveBeenCalledTimes(1)
      expect(writeText).toHaveBeenCalledWith('https://app/#/document/view/doc-7/content?file=f1')
    })

    it('toasts success once the clipboard write resolves', async () => {
      await clickCopyLink(mountMenu(true))
      expect(toastAdd).toHaveBeenCalledTimes(1)
      expect(toastAdd.mock.calls[0][0]).toMatchObject({
        severity: 'success',
        summary: 'ui.file_view.link_copied',
      })
    })

    it('toasts an error when the clipboard write rejects (permission denied)', async () => {
      stubClipboard(() => Promise.reject(new Error('denied')))
      await clickCopyLink(mountMenu(false))
      expect(toastAdd).toHaveBeenCalledTimes(1)
      expect(toastAdd.mock.calls[0][0]).toMatchObject({
        severity: 'error',
        summary: 'ui.file_view.link_copy_failed',
      })
    })

    it('toasts an error — never throws — when the browser exposes no clipboard API at all', async () => {
      // An insecure (plain-http, non-localhost) origin has no navigator.clipboard: the
      // property access itself throws, and it must land in the same error toast.
      stubClipboard(null)
      await clickCopyLink(mountMenu(true))
      expect(toastAdd).toHaveBeenCalledTimes(1)
      expect(toastAdd.mock.calls[0][0]).toMatchObject({
        severity: 'error',
        summary: 'ui.file_view.link_copy_failed',
      })
    })
  })

  it('renders the writable-only `extra` slot for callers to mount extra actions (#73/#117)', () => {
    const slot = '<button class="extra-action" aria-label="extra">x</button>'
    expect(mountMenu(true, { extra: slot }).find('.extra-action').exists()).toBe(true)
    // Read-only never surfaces caller-injected write actions.
    expect(mountMenu(false, { extra: slot }).find('.extra-action').exists()).toBe(false)
  })
})
