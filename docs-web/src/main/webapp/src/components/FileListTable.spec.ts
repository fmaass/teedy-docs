import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import DataTable from 'primevue/datatable'
import FileListTable, { type FilePanelFile } from './FileListTable.vue'

// The enriched authenticated file list. t() is stubbed to the key so assertions target
// stable header/aria keys. getFileUrl is a dependency (stubbed to a deterministic URL).
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string, p?: Record<string, unknown>) => (p ? `${k}:${JSON.stringify(p)}` : k) }) }))
vi.mock('../api/file', () => ({ getFileUrl: (id: string) => `/api/file/${id}/data` }))

// jsdom lacks ResizeObserver, which PrimeVue's VirtualScroller (mounted above the
// ~100-file threshold) requires. Stub it so the virtualized path can mount.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub

function makeFile(over: Partial<FilePanelFile> = {}): FilePanelFile {
  return {
    id: 'f1',
    name: 'alpha.txt',
    mimetype: 'text/plain',
    size: 1024,
    create_date: 1_700_000_000_000,
    creator: 'admin',
    version: 0,
    ...over,
  }
}

const twoFiles: FilePanelFile[] = [
  makeFile({ id: 'f1', name: 'alpha.txt', mimetype: 'text/plain' }),
  makeFile({ id: 'f2', name: 'beta.pdf', mimetype: 'application/pdf' }),
]

function mountTable(files: FilePanelFile[], writable = true, slots: Record<string, string> = {}) {
  return mount(FileListTable, {
    props: { files, writable },
    global: {
      plugins: [[PrimeVue, { theme: 'none' }]],
      directives: { tooltip: {} },
    },
    slots,
    attachTo: document.body,
  })
}

function headerTexts(wrapper: ReturnType<typeof mountTable>) {
  return wrapper.findAll('thead th').map((h) => h.text()).filter((s) => s.length > 0)
}

describe('FileListTable', () => {
  beforeEach(() => localStorage.clear())

  it('renders the default columns (Name + Created + Size), Uploader hidden by default', () => {
    const heads = headerTexts(mountTable(twoFiles))
    expect(heads).toContain('ui.file_view.col_name')
    expect(heads).toContain('ui.file_view.col_created')
    expect(heads).toContain('ui.file_view.col_size')
    expect(heads).not.toContain('ui.file_view.col_uploader')
  })

  it('shows the Uploader column once it is enabled (optional columns)', async () => {
    const wrapper = mountTable(twoFiles)
    ;(wrapper.vm as unknown as { columns: Record<string, boolean> }).columns.uploader = true
    await wrapper.vm.$nextTick()
    expect(headerTexts(wrapper)).toContain('ui.file_view.col_uploader')
  })

  it('quick-filters rows by name/mimetype (client-side contains-match)', async () => {
    const wrapper = mountTable(twoFiles)
    expect(wrapper.findAll('tbody tr').length).toBe(2)
    await wrapper.find('input.file-filter-input').setValue('beta')
    const rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(1)
    expect(rows[0].text()).toContain('beta.pdf')
    // mimetype match too.
    await wrapper.find('input.file-filter-input').setValue('pdf')
    expect(wrapper.findAll('tbody tr').length).toBe(1)
  })

  it('filters a list containing a null-name file without throwing (shown unfiltered, excluded by a name query)', async () => {
    // A legacy/inbox file can arrive with a null name; the quick filter must not crash on it.
    const files: FilePanelFile[] = [
      makeFile({ id: 'f1', name: 'alpha.txt', mimetype: 'text/plain' }),
      makeFile({ id: 'f2', name: null, mimetype: 'application/pdf' }),
    ]
    const wrapper = mountTable(files)
    // Unfiltered: both rows show, including the null-name one.
    expect(wrapper.findAll('tbody tr').length).toBe(2)
    // A non-empty NAME query must not throw and must exclude the null-name row (no name to match).
    await wrapper.find('input.file-filter-input').setValue('alpha')
    const rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(1)
    expect(rows[0].text()).toContain('alpha.txt')
    // The null-name row is still matchable on its mimetype (its only remaining searchable field).
    await wrapper.find('input.file-filter-input').setValue('pdf')
    const pdfRows = wrapper.findAll('tbody tr')
    expect(pdfRows.length).toBe(1)
    expect(pdfRows[0].text()).toContain('ui.file_view.untitled')
  })

  it('renames via double-click on the name cell (Enter commits, emits fileId + new name)', async () => {
    const wrapper = mountTable(twoFiles)
    const name = wrapper.findAll('.file-name-text')[0]
    await name.trigger('dblclick')
    const input = wrapper.find('input.rename-input')
    expect(input.exists()).toBe(true)
    await input.setValue('renamed.txt')
    await input.trigger('keyup.enter')
    expect(wrapper.emitted('rename')?.[0]).toEqual(['f1', 'renamed.txt'])
  })

  it('renames via F2 on the focused name cell', async () => {
    const wrapper = mountTable(twoFiles)
    await wrapper.findAll('.file-name-text')[0].trigger('keydown', { key: 'F2' })
    expect(wrapper.find('input.rename-input').exists()).toBe(true)
  })

  it('renames via the action-menu pencil', async () => {
    const wrapper = mountTable(twoFiles)
    const pencil = wrapper.findAll('button').find((b) => b.attributes('aria-label') === 'rename')!
    await pencil.trigger('click')
    expect(wrapper.find('input.rename-input').exists()).toBe(true)
  })

  it('read-only: no rename/delete affordances; neither double-click nor F2 opens a rename editor', async () => {
    const wrapper = mountTable(twoFiles, false)
    const ariaLabels = wrapper.findAll('button').map((b) => b.attributes('aria-label'))
    expect(ariaLabels).not.toContain('rename')
    expect(ariaLabels).not.toContain('ui.remove_file')
    await wrapper.findAll('.file-name-text')[0].trigger('dblclick')
    expect(wrapper.find('input.rename-input').exists()).toBe(false)
    // F2 is also gated — a read-only viewer cannot start a rename by keyboard.
    await wrapper.findAll('.file-name-text')[0].trigger('keydown', { key: 'F2' })
    expect(wrapper.find('input.rename-input').exists()).toBe(false)
    // Version history stays available read-only.
    expect(ariaLabels).toContain('ui.versions.title')
  })

  it('forwards the #file-extra slot into each writable row action menu (absent when read-only)', () => {
    const marker = '<span class="pe">P</span>'
    const writable = mountTable(twoFiles, true, { 'file-extra': marker })
    // One injected action per row, inside the writable action menu.
    expect(writable.findAll('.file-action-menu .pe').length).toBe(2)
    // Read-only never surfaces the writable-only extra slot.
    expect(mountTable(twoFiles, false, { 'file-extra': marker }).findAll('.pe').length).toBe(0)
  })

  it('rolls back the optimistic order and flips the indicator to not-saved when a reorder fails', async () => {
    const wrapper = mountTable(twoFiles)
    const names = () => wrapper.findAll('.file-data-table tbody tr .file-name-text').map((n) => n.text())
    expect(names()).toEqual(['alpha.txt', 'beta.pdf'])

    // Optimistic apply (drag).
    wrapper.findComponent(DataTable).vm.$emit('row-reorder', {
      originalEvent: new Event('drop'),
      dragIndex: 1,
      dropIndex: 0,
      value: [twoFiles[1], twoFiles[0]],
    })
    await wrapper.vm.$nextTick()
    expect(names()).toEqual(['beta.pdf', 'alpha.txt'])
    expect(wrapper.find('.file-order-indicator').text()).toContain('ui.file_view.order_custom')

    // Persist rejected → parent triggers rollback: order reverts and the indicator no
    // longer reads as the saved custom order.
    ;(wrapper.vm as unknown as { rollbackReorder: () => void }).rollbackReorder()
    await wrapper.vm.$nextTick()
    expect(names()).toEqual(['alpha.txt', 'beta.pdf'])
    const indicator = wrapper.find('.file-order-indicator').text()
    expect(indicator).toContain('ui.file_view.order_failed')
    expect(indicator).not.toContain('ui.file_view.order_custom')
  })

  it('serializes reorders — a second drag is blocked while a persist is pending, then re-enables', async () => {
    const wrapper = mountTable(twoFiles)
    const vm = wrapper.vm as unknown as { reorderEnabled: boolean; confirmReorder: () => void }
    const dt = wrapper.findComponent(DataTable)
    expect(vm.reorderEnabled).toBe(true)

    // First reorder → pending → drag disabled (only one in flight).
    dt.vm.$emit('row-reorder', { originalEvent: new Event('drop'), dragIndex: 1, dropIndex: 0, value: [twoFiles[1], twoFiles[0]] })
    await wrapper.vm.$nextTick()
    expect(vm.reorderEnabled).toBe(false)

    // A second reorder WHILE pending is ignored: the pre-drag snapshot is not overwritten
    // and no second persist is emitted.
    dt.vm.$emit('row-reorder', { originalEvent: new Event('drop'), dragIndex: 0, dropIndex: 1, value: [twoFiles[0], twoFiles[1]] })
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('reorder')?.length).toBe(1)

    // Once the parent confirms the persist, reorder re-enables.
    vm.confirmReorder()
    await wrapper.vm.$nextTick()
    expect(vm.reorderEnabled).toBe(true)
  })

  it('persists an explicit drag reorder by emitting the full new id order', async () => {
    const wrapper = mountTable(twoFiles)
    const reordered = [twoFiles[1], twoFiles[0]]
    wrapper.findComponent(DataTable).vm.$emit('row-reorder', {
      originalEvent: new Event('drop'),
      dragIndex: 1,
      dropIndex: 0,
      value: reordered,
    })
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('reorder')?.[0]).toEqual([['f2', 'f1']])
  })

  it('reorder is enabled only in the unfiltered/unsorted custom-order view, and only when writable', async () => {
    const wrapper = mountTable(twoFiles)
    const vm = wrapper.vm as unknown as { reorderEnabled: boolean }
    expect(vm.reorderEnabled).toBe(true)
    // A transient sort disables reorder and flips the order indicator.
    wrapper.findComponent(DataTable).vm.$emit('sort', { sortField: 'name', sortOrder: 1 })
    await wrapper.vm.$nextTick()
    expect(vm.reorderEnabled).toBe(false)
    expect(wrapper.find('.file-order-indicator').text()).toContain('ui.file_view.order_sorted')
    // Read-only never reorders.
    expect((mountTable(twoFiles, false).vm as unknown as { reorderEnabled: boolean }).reorderEnabled).toBe(false)
  })

  it('shows the custom-order indicator by default (persisted order, not a transient sort)', () => {
    const wrapper = mountTable(twoFiles)
    expect(wrapper.find('.file-order-indicator').text()).toContain('ui.file_view.order_custom')
  })

  it('double-clicking a row (not the name cell) emits open', async () => {
    const wrapper = mountTable(twoFiles)
    wrapper.findComponent(DataTable).vm.$emit('row-dblclick', { originalEvent: new Event('dblclick'), data: twoFiles[0], index: 0 })
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('open')?.[0]).toEqual([twoFiles[0]])
  })

  it('the icon open control previews (emits open) and never links to the original /data URL (#144)', async () => {
    const wrapper = mountTable(twoFiles)
    // No affordance in the list may navigate to the raw attachment URL — "open" must
    // route to an in-app preview, not a browser download.
    const dataAnchors = wrapper.findAll('a').filter((a) => (a.attributes('href') ?? '').includes('/data'))
    expect(dataAnchors.length).toBe(0)
    const opener = wrapper.find('.file-open-link')
    expect(opener.exists()).toBe(true)
    await opener.trigger('click')
    expect(wrapper.emitted('open')?.[0]).toEqual([twoFiles[0]])
  })

  it('virtual-scrolls only above the ~100-file threshold', () => {
    const small = mountTable(twoFiles)
    expect((small.vm as unknown as { virtualize: boolean }).virtualize).toBe(false)
    expect(small.findComponent(DataTable).props('virtualScrollerOptions')).toBeFalsy()
    expect(small.findComponent(DataTable).props('scrollable')).toBe(false)

    const many = Array.from({ length: 101 }, (_, i) => makeFile({ id: `f${i}`, name: `file-${i}.txt` }))
    const big = mountTable(many)
    expect((big.vm as unknown as { virtualize: boolean }).virtualize).toBe(true)
    expect(big.findComponent(DataTable).props('virtualScrollerOptions')).toBeTruthy()
    expect(big.findComponent(DataTable).props('scrollable')).toBe(true)
  })
})
