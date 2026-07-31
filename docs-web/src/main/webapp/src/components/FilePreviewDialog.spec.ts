import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

// The in-app file preview (#144). The original-file URL is served by the backend as an
// attachment under a locked-down CSP, so it must NEVER be embedded — only ever used as
// an explicit Download target. This dialog renders a SAFE, derived representation per
// type (image → size=web raster; PDF → pdf.js viewer; text → size=content) and falls
// back to a "preview unavailable" state that still offers Download. t() is stubbed to
// the key; the api/file helpers are dependencies (stubbed to deterministic URLs).
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string, p?: Record<string, unknown>) => (p ? `${k}:${JSON.stringify(p)}` : k) }),
}))
const getFileContentMock = vi.fn()
vi.mock('../api/file', () => ({
  // Mirrors the real signature: a size produces a derived URL; no size is the ORIGINAL.
  getFileUrl: (id: string, size?: string, shareId?: string) => {
    const q = new URLSearchParams()
    if (size) q.set('size', size)
    if (shareId) q.set('share', shareId)
    const s = q.toString()
    return `api/file/${id}/data${s ? `?${s}` : ''}`
  },
  getFileContent: (...args: unknown[]) => getFileContentMock(...args),
}))

import FilePreviewDialog from './FilePreviewDialog.vue'

type PreviewFile = { id: string; name: string | null; mimetype: string; rotation?: number }

function mountDialog(file: PreviewFile | null, shareId?: string) {
  return mount(FilePreviewDialog, {
    props: { visible: true, file, shareId },
    global: {
      stubs: {
        // Dialog teleports to <body>; render its slots inline so the test can query the
        // body + footer without chasing the teleport target. The two slots are kept in
        // SEPARATE wrappers so a test can tell a body-rendered control from a footer one
        // (#181: the surviving Download must be the footer's).
        Dialog: {
          // `maximizable`/`maximizeButtonProps` are declared so the #205 tests can read what
          // the dialog hands PrimeVue; Boolean typing turns the valueless `maximizable`
          // attribute into a real `true` instead of an empty string. Everything else
          // (class, style) stays an ATTRIBUTE and falls through to `.dlg`, which is what
          // those tests measure.
          props: { visible: Boolean, maximizable: Boolean, maximizeButtonProps: Object },
          template:
            '<div class="dlg"><div class="dlg-body"><slot /></div><div class="dlg-footer"><slot name="footer" /></div></div>',
        },
        // pdf.js is heavy and async-loaded; a light stub stands in for the branch check.
        // It exposes `downloadable` (the dialog must pass false so the viewer renders no
        // original-URL control) and can emit `error` to drive the failure-degrade path.
        PdfViewer: {
          props: ['src', 'persistable', 'downloadable'],
          emits: ['error'],
          template: '<div class="pdf-stub" :data-src="src" :data-downloadable="String(downloadable)" />',
        },
      },
      directives: { tooltip: {} },
    },
  })
}

// The Download control is the ONLY thing allowed to point at the original attachment URL.
function downloadLink(wrapper: ReturnType<typeof mountDialog>) {
  return wrapper.find('a.file-preview-download')
}

// #181 — the dialog must offer exactly ONE Download anchor. `find()` returns the first
// match and is blind to duplicates, which is why the original tests passed while the
// unavailable state rendered a second, inline anchor beside the footer one. Every
// assertion about the affordance's uniqueness therefore counts, never finds.
function downloadLinkCount(wrapper: ReturnType<typeof mountDialog>) {
  return wrapper.findAll('a.file-preview-download').length
}

describe('FilePreviewDialog (#144)', () => {
  // NB: the text-fetch mock is (re)configured inside each test that needs it rather than
  // in a beforeEach. A `mockRejectedValue` set in beforeEach trips vitest's
  // unhandled-rejection detector across the hook boundary even though the component's
  // loadText catches the rejection — set per-test, the catch is observed as intended.
  it('never embeds the file in an iframe or embed (the CSP would force a download anyway)', () => {
    for (const mime of ['image/png', 'application/pdf', 'text/plain', 'application/zip']) {
      const wrapper = mountDialog({ id: 'f1', name: 'x', mimetype: mime })
      expect(wrapper.find('iframe').exists()).toBe(false)
      expect(wrapper.find('embed').exists()).toBe(false)
    }
  })

  it('image: previews the size=web raster, never the original; Download targets the original', () => {
    const wrapper = mountDialog({ id: 'img1', name: 'photo.png', mimetype: 'image/png', rotation: 90 })
    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toContain('size=web')
    // The preview must not point at the raw attachment URL.
    expect(img.attributes('src')).not.toBe('api/file/img1/data')
    // Download = the original (no size param).
    expect(downloadLink(wrapper).attributes('href')).toBe('api/file/img1/data')
  })

  it('pdf: renders the pdf.js viewer (no <img>), viewer gets downloadable=false, Download targets the original', () => {
    const wrapper = mountDialog({ id: 'pdf1', name: 'doc.pdf', mimetype: 'application/pdf' })
    expect(wrapper.find('.pdf-stub').exists()).toBe(true)
    expect(wrapper.find('img').exists()).toBe(false)
    // The viewer fetches the original bytes itself (pdf.js → canvas), not an embed.
    expect(wrapper.find('.pdf-stub').attributes('data-src')).toBe('api/file/pdf1/data')
    // The viewer must NOT expose its own original-URL control — this dialog owns Download.
    expect(wrapper.find('.pdf-stub').attributes('data-downloadable')).toBe('false')
    expect(downloadLink(wrapper).attributes('href')).toBe('api/file/pdf1/data')
  })

  it('text: fetches the extracted size=content text and shows it; Download targets the original', async () => {
    getFileContentMock.mockReset()
    getFileContentMock.mockResolvedValue('line one\nline two')
    const wrapper = mountDialog({ id: 'txt1', name: 'notes.txt', mimetype: 'text/plain' })
    await flushPromises()
    expect(getFileContentMock).toHaveBeenCalledWith('txt1', undefined)
    expect(wrapper.find('pre.file-preview-text').text()).toContain('line one')
    expect(downloadLink(wrapper).attributes('href')).toBe('api/file/txt1/data')
  })

  it('unsupported type: shows the preview-unavailable state with a Download button', () => {
    const wrapper = mountDialog({ id: 'bin1', name: 'archive.zip', mimetype: 'application/zip' })
    expect(wrapper.text()).toContain('ui.file_view.preview_unavailable')
    expect(downloadLink(wrapper).attributes('href')).toBe('api/file/bin1/data')
    expect(downloadLinkCount(wrapper)).toBe(1)
  })

  it('text fetch failure falls back to the unavailable state (still offering Download)', async () => {
    getFileContentMock.mockReset()
    getFileContentMock.mockRejectedValue(new Error('403'))
    const wrapper = mountDialog({ id: 'txt2', name: 'secret.txt', mimetype: 'text/plain' })
    await flushPromises()
    expect(wrapper.find('pre.file-preview-text').exists()).toBe(false)
    expect(wrapper.text()).toContain('ui.file_view.preview_unavailable')
    expect(downloadLink(wrapper).attributes('href')).toBe('api/file/txt2/data')
    expect(downloadLinkCount(wrapper)).toBe(1)
  })

  it('threads the share credential through preview and Download URLs', () => {
    const wrapper = mountDialog({ id: 'sh1', name: 'p.png', mimetype: 'image/png' }, 'share-token')
    expect(wrapper.find('img').attributes('src')).toContain('share=share-token')
    expect(downloadLink(wrapper).attributes('href')).toContain('share=share-token')
  })

  it('text: a slower earlier fetch cannot overwrite a newer file (stale-response race)', async () => {
    // Open A (fetch pending) then switch to B (fetch pending); resolve B, THEN the older A.
    // A's late resolution must be dropped, so B's content wins.
    getFileContentMock.mockReset()
    let resolveA!: (v: string) => void
    let resolveB!: (v: string) => void
    getFileContentMock
      .mockImplementationOnce(() => new Promise<string>((r) => (resolveA = r)))
      .mockImplementationOnce(() => new Promise<string>((r) => (resolveB = r)))

    const wrapper = mountDialog({ id: 'A', name: 'a.txt', mimetype: 'text/plain' })
    await wrapper.setProps({ file: { id: 'B', name: 'b.txt', mimetype: 'text/plain' } })

    resolveB('content of B')
    await flushPromises()
    resolveA('content of A')
    await flushPromises()

    expect(wrapper.find('pre.file-preview-text').text()).toContain('content of B')
    expect(wrapper.find('pre.file-preview-text').text()).not.toContain('content of A')
  })

  it('image load failure degrades to preview-unavailable + Download', async () => {
    const wrapper = mountDialog({ id: 'img9', name: 'broken.png', mimetype: 'image/png' })
    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    await img.trigger('error')
    // No broken raster is left on screen — the dialog degrades to the unavailable state.
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('ui.file_view.preview_unavailable')
    expect(downloadLink(wrapper).attributes('href')).toBe('api/file/img9/data')
    expect(downloadLinkCount(wrapper)).toBe(1)
  })

  it('pdf load failure degrades to preview-unavailable + Download (never an unlabelled open control)', async () => {
    const wrapper = mountDialog({ id: 'pdf9', name: 'broken.pdf', mimetype: 'application/pdf' })
    expect(wrapper.find('.pdf-stub').attributes('data-downloadable')).toBe('false')
    await wrapper.findComponent('.pdf-stub').vm.$emit('error')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.pdf-stub').exists()).toBe(false)
    expect(wrapper.text()).toContain('ui.file_view.preview_unavailable')
    expect(downloadLink(wrapper).attributes('href')).toBe('api/file/pdf9/data')
    expect(downloadLinkCount(wrapper)).toBe(1)
  })

  // #181 — the unavailable state used to render its OWN inline Download beside the
  // footer's, so the dialog showed two identical controls. Every route into
  // previewMode === 'unavailable' is walked here and counted: the footer is the single
  // affordance, and the body carries none.
  describe('#181: exactly one Download anchor in every unavailable state', () => {
    async function unsupported() {
      return mountDialog({ id: 'u1', name: 'archive.zip', mimetype: 'application/zip' })
    }

    async function imageError() {
      const wrapper = mountDialog({ id: 'u2', name: 'broken.png', mimetype: 'image/png' })
      await wrapper.find('img').trigger('error')
      return wrapper
    }

    async function pdfError() {
      const wrapper = mountDialog({ id: 'u3', name: 'broken.pdf', mimetype: 'application/pdf' })
      await wrapper.findComponent('.pdf-stub').vm.$emit('error')
      await wrapper.vm.$nextTick()
      return wrapper
    }

    async function textError() {
      getFileContentMock.mockReset()
      getFileContentMock.mockRejectedValue(new Error('403'))
      const wrapper = mountDialog({ id: 'u4', name: 'secret.txt', mimetype: 'text/plain' })
      await flushPromises()
      return wrapper
    }

    for (const [mode, make] of [
      ['unsupported mimetype', unsupported],
      ['image load error', imageError],
      ['pdf load error', pdfError],
      ['text fetch error', textError],
    ] as const) {
      it(`${mode}: one Download, and it is the footer's`, async () => {
        const wrapper = await make()
        // Precondition — this really is the unavailable state, so the count below is
        // not passing vacuously on some other branch.
        expect(wrapper.text()).toContain('ui.file_view.preview_unavailable')
        expect(wrapper.findAll('a.file-preview-download')).toHaveLength(1)
        // The survivor is the footer control; the body offers no Download of its own.
        expect(wrapper.findAll('.dlg-footer a.file-preview-download')).toHaveLength(1)
        expect(wrapper.findAll('.dlg-body a.file-preview-download')).toHaveLength(0)
      })
    }

    it('every previewable mode also renders exactly one Download', async () => {
      getFileContentMock.mockReset()
      getFileContentMock.mockResolvedValue('body text')
      for (const mime of ['image/png', 'application/pdf', 'text/plain']) {
        const wrapper = mountDialog({ id: 'p1', name: 'f', mimetype: mime })
        await flushPromises()
        expect(downloadLinkCount(wrapper), mime).toBe(1)
      }
    })
  })

  // #205 — fullscreen. PrimeVue keeps the maximize state to itself and only announces it via
  // maximize/unmaximize, so the two windowed size constraints this component owns must be
  // released from those events: the inline 960px max-width (the dialog's own style) and the
  // 70vh media caps (released through the --maximized class the scoped CSS keys off). These
  // tests pin the WIRING; the rendered geometry — that the media really outgrows the 70vh cap
  // and the box outgrows 960px — is measured against a real browser in
  // e2e/preview-fullscreen.spec.ts, which jsdom cannot do (it applies no scoped stylesheet).
  describe('#205: fullscreen wiring', () => {
    function dialogStub(wrapper: ReturnType<typeof mountDialog>) {
      return wrapper.findComponent('.dlg')
    }

    it('offers the maximize affordance with a translated accessible name', () => {
      const wrapper = mountDialog({ id: 'f1', name: 'photo.png', mimetype: 'image/png' })
      expect(dialogStub(wrapper).props('maximizable')).toBe(true)
      // Icon-only control: without a label it reaches a screen reader unnamed. The label goes
      // through t(), so it is translatable rather than a hardcoded English string.
      expect(dialogStub(wrapper).props('maximizeButtonProps')).toEqual({
        'aria-label': 'ui.file_view.fullscreen',
      })
    })

    it('windowed: the dialog is width-clamped to 960px and carries no maximized modifier', () => {
      const wrapper = mountDialog({ id: 'f1', name: 'photo.png', mimetype: 'image/png' })
      expect(wrapper.find('.dlg').attributes('style')).toContain('960px')
      expect(wrapper.find('.dlg').classes()).toContain('file-preview-dialog')
      expect(wrapper.find('.dlg').classes()).not.toContain('file-preview-dialog--maximized')
    })

    it('maximize DROPS the 960px clamp and flags the maximized state', async () => {
      const wrapper = mountDialog({ id: 'f1', name: 'photo.png', mimetype: 'image/png' })
      await dialogStub(wrapper).vm.$emit('maximize')
      await wrapper.vm.$nextTick()
      // The clamp is the pre-gate catch: PrimeVue's own maximized CSS forces width:100vw but
      // overrides no max-width, so leaving it here would keep "fullscreen" 960px wide.
      expect(wrapper.find('.dlg').attributes('style') ?? '').not.toContain('960px')
      // …and the class the scoped CSS uses to lift the 70vh media caps.
      expect(wrapper.find('.dlg').classes()).toContain('file-preview-dialog--maximized')
    })

    it('unmaximize restores the windowed clamp — the relaxation does not leak', async () => {
      const wrapper = mountDialog({ id: 'f1', name: 'photo.png', mimetype: 'image/png' })
      await dialogStub(wrapper).vm.$emit('maximize')
      await wrapper.vm.$nextTick()
      await dialogStub(wrapper).vm.$emit('unmaximize')
      await wrapper.vm.$nextTick()
      expect(wrapper.find('.dlg').attributes('style')).toContain('960px')
      expect(wrapper.find('.dlg').classes()).not.toContain('file-preview-dialog--maximized')
    })

    it('every preview mode keeps its single Download while maximized (#181 still holds)', async () => {
      getFileContentMock.mockReset()
      getFileContentMock.mockResolvedValue('body text')
      for (const mime of ['image/png', 'application/pdf', 'text/plain', 'application/zip']) {
        const wrapper = mountDialog({ id: 'p1', name: 'f', mimetype: mime })
        await flushPromises()
        await dialogStub(wrapper).vm.$emit('maximize')
        await wrapper.vm.$nextTick()
        expect(wrapper.find('.dlg').classes(), mime).toContain('file-preview-dialog--maximized')
        expect(downloadLinkCount(wrapper), mime).toBe(1)
      }
    })
  })
})
