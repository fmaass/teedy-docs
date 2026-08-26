import { describe, it, expect, beforeAll, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../locale/en.json'
import FileListTable from './FileListTable.vue'

// The file list's per-file access count (#300), and the two contracts that shape WHERE it may live
// (TEEDY-139, after it broke both of them on main):
//
//   * it is a SIBLING of `.file-name-text`, never a child — that span's textContent is the file
//     name, and nullname.spec / file-panel.spec read it with toHaveText. A badge inside it turned
//     "Untitled file" into "Untitled file0".
//   * it adds NO column and NO `button, a` to the action cell — the #170 row-geometry contract
//     pins the table to its container's width and the action cluster to an exact control count.
//     A 5.5rem column of its own pushed the table 10px past its container at 1023px and 1280px.

const FILES = [
  {
    id: 'f1',
    name: 'contract.pdf',
    mimetype: 'application/pdf',
    create_date: 1700000000000,
    size: 1024,
    creator: 'admin',
    version: 0,
  },
  {
    id: 'f2',
    name: 'scan.jpg',
    mimetype: 'image/jpeg',
    create_date: 1700000001000,
    size: 2048,
    creator: 'admin',
    version: 0,
  },
]

beforeAll(() => {
  if (typeof window.matchMedia !== 'function') {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: false, media: query, onchange: null,
        addEventListener: () => {}, removeEventListener: () => {},
        addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
      }),
    })
  }
})

beforeEach(() => {
  localStorage.clear()
})

function mountTable(accessCounts?: Record<string, number>) {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  return mount(FileListTable, {
    props: { files: FILES, writable: true, documentId: 'doc-1', accessCounts },
    global: { plugins: [i18n, PrimeVue, ToastService, ConfirmationService] },
  })
}

describe('FileListTable access count (#300)', () => {
  it('shows each file its own count, inside the name cell', async () => {
    const wrapper = mountTable({ f1: 7, f2: 3 })
    await flushPromises()

    const counts = wrapper.findAll('td.file-col-name .access-count-value').map((n) => n.text())
    expect(counts).toEqual(['7', '3'])
  })

  it('leaves the file-name text node holding the name and nothing else', async () => {
    // The exact regression that reddened main: this is what toHaveText reads.
    const wrapper = mountTable({ f1: 7, f2: 3 })
    await flushPromises()
    expect(wrapper.findAll('.file-name-text').map((n) => n.text())).toEqual([
      'contract.pdf',
      'scan.jpg',
    ])
  })

  it('renders nothing for a file with no recorded access', async () => {
    const wrapper = mountTable({ f1: 4, f2: 0 })
    await flushPromises()
    // One badge, not two - and the silent row is still a row.
    expect(wrapper.findAll('.access-count')).toHaveLength(1)
    expect(wrapper.findAll('.file-name-text').map((n) => n.text())).toEqual([
      'contract.pdf',
      'scan.jpg',
    ])
  })

  it('renders nothing while the counts have not arrived', async () => {
    const wrapper = mountTable(undefined)
    await flushPromises()
    expect(wrapper.findAll('.access-count')).toHaveLength(0)
    expect(wrapper.findAll('.file-name-text').map((n) => n.text())).toEqual([
      'contract.pdf',
      'scan.jpg',
    ])
  })

  it('adds no column of its own — the header row is unchanged by the counts', async () => {
    const withCounts = mountTable({ f1: 7, f2: 3 })
    const withoutCounts = mountTable(undefined)
    await flushPromises()
    expect(withCounts.findAll('thead th')).toHaveLength(withoutCounts.findAll('thead th').length)
    expect(withCounts.findAll('th.file-col-accesses')).toHaveLength(0)
    expect(withCounts.findAll('td.file-col-accesses')).toHaveLength(0)
  })

  it('adds no control to the action cluster the #170 contract counts', async () => {
    const withCounts = mountTable({ f1: 7, f2: 3 })
    const withoutCounts = mountTable(undefined)
    await flushPromises()
    // `button, a` inside td.file-col-actions is exactly what file-list-geometry.spec counts.
    const controls = (w: ReturnType<typeof mountTable>) =>
      w.findAll('td.file-col-actions').map((cell) => cell.findAll('button, a').length)
    expect(controls(withCounts)).toEqual(controls(withoutCounts))
    // And the badge really is elsewhere, so the equality above is not two zeroes.
    expect(withCounts.findAll('td.file-col-name .access-count')).toHaveLength(2)
  })
})
