import { describe, it, expect, beforeAll, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../locale/en.json'
import FileListTable from './FileListTable.vue'

// The file list's per-file access column (#300). It renders the CALLING user's own counts, handed
// down from the document view's single query — the table itself must never fetch, or an N-row
// panel would become N requests.

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

describe('FileListTable access column (#300)', () => {
  it('shows each file its own count', async () => {
    const wrapper = mountTable({ f1: 7, f2: 0 })
    await flushPromises()

    const cells = wrapper.findAll('td.file-col-accesses .access-count-value').map((n) => n.text())
    expect(cells).toEqual(['7', '0'])
  })

  it('headers the column with the possessive label, so it can never read as a global number', async () => {
    const wrapper = mountTable({ f1: 1, f2: 1 })
    await flushPromises()
    expect(wrapper.find('th.file-col-accesses').text()).toBe(en.ui.access.col_accesses)
  })

  it('renders no number for a file whose count has not arrived', async () => {
    const wrapper = mountTable({ f1: 3 })
    await flushPromises()
    const cells = wrapper.findAll('td.file-col-accesses')
    expect(cells[0].find('.access-count-value').text()).toBe('3')
    expect(cells[1].find('.access-count-value').exists()).toBe(false)
  })

  it('renders the column with no counts at all rather than failing', async () => {
    const wrapper = mountTable(undefined)
    await flushPromises()
    expect(wrapper.findAll('td.file-col-accesses')).toHaveLength(2)
    expect(wrapper.findAll('td.file-col-accesses .access-count-value')).toHaveLength(0)
  })

  it('offers the column in the column chooser and honours turning it off', async () => {
    const wrapper = mountTable({ f1: 7, f2: 2 })
    await flushPromises()
    expect(wrapper.findAll('td.file-col-accesses')).toHaveLength(2)

    // The user's stored choice is what hides it; the persisted shape is the contract.
    localStorage.setItem(
      'teedy_file_columns',
      JSON.stringify({ created: true, size: true, uploader: false, accesses: false }),
    )
    const hidden = mountTable({ f1: 7, f2: 2 })
    await flushPromises()
    expect(hidden.findAll('td.file-col-accesses')).toHaveLength(0)
  })

  it('defaults the column ON for a preference saved before it existed', async () => {
    localStorage.setItem(
      'teedy_file_columns',
      JSON.stringify({ created: true, size: false, uploader: false }),
    )
    const wrapper = mountTable({ f1: 7, f2: 2 })
    await flushPromises()
    expect(wrapper.findAll('td.file-col-accesses')).toHaveLength(2)
    // Control: the same stored preference really was honoured for a column it DOES name.
    expect(wrapper.findAll('td.file-col-size')).toHaveLength(0)
  })
})
