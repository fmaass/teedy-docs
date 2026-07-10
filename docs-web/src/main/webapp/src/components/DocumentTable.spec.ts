import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import DocumentTable from './DocumentTable.vue'
import type { DocumentListItem } from '../api/document'

// vue-i18n stub: return the key so assertions target logic, not copy.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

// Stub the file API (thumb URLs) — no network in unit tests.
vi.mock('../api/file', () => ({ getFileUrl: (id: string, size: string) => `/file/${id}/${size}` }))

function makeDoc(tagCount: number): DocumentListItem {
  return {
    id: 'doc1',
    title: 'Doc',
    description: '',
    create_date: 0,
    update_date: 0,
    language: 'eng',
    file_id: null,
    file_count: 0,
    tags: Array.from({ length: tagCount }, (_, i) => ({
      id: `t${i}`,
      name: `tag-${i}`,
      color: '#123456',
    })),
    shared: false,
  }
}

// Mount with the real PrimeVue DataTable/Column so scoped column body slots render
// exactly as in production. selectable=false exercises the single-select table.
function mountTable(doc: DocumentListItem) {
  return mount(DocumentTable, {
    props: {
      documents: [doc],
      totalRecords: 1,
      rows: 20,
      first: 0,
    },
    global: {
      plugins: [[PrimeVue, { theme: 'none' }]],
      directives: { tooltip: Tooltip },
    },
  })
}

describe('DocumentTable — tag overflow reveal (#24)', () => {
  it('renders the first three tags inline and delegates the rest to the overflow control', () => {
    const wrapper = mountTable(makeDoc(6))
    const html = wrapper.html()
    // The first three tags render inline as badges.
    for (let i = 0; i < 3; i++) {
      expect(html).toContain(`tag-${i}`)
    }
    // A focusable overflow trigger stands in for the remaining tags (reveal
    // semantics + hidden-name reachability are covered in TagOverflow.spec.ts,
    // since the panel now teleports to <body> to escape the table's clipping).
    const overflow = wrapper.find('.tag-overflow')
    expect(overflow.exists()).toBe(true)
    expect(overflow.attributes('tabindex')).toBe('0')
    expect(overflow.text()).toContain('+3')
  })

  it('shows no overflow control when a doc has 3 or fewer tags', () => {
    const wrapper = mountTable(makeDoc(3))
    expect(wrapper.find('.tag-overflow').exists()).toBe(false)
  })

  it('labels the overflow control with the exact hidden count (4 tags → +1)', () => {
    const wrapper = mountTable(makeDoc(4))
    const overflow = wrapper.find('.tag-overflow')
    expect(overflow.exists()).toBe(true)
    // 4 tags: 3 badges shown + a "+1" control.
    expect(overflow.text()).toContain('+1')
    // aria-label carries the count via the i18n key argument (key stubbed to itself).
    expect(overflow.attributes('aria-label')).toBe('ui.more_tags')
  })

  it('activating the +N control does NOT emit a row click (click is stopped)', async () => {
    const wrapper = mountTable(makeDoc(6))
    const overflow = wrapper.find('.tag-overflow')
    await overflow.trigger('click')
    // The stopped handler must keep the click from bubbling to the DataTable row.
    expect(wrapper.emitted('rowClick')).toBeUndefined()
    expect(wrapper.emitted('rowDblclick')).toBeUndefined()
  })
})

describe('DocumentTable — double-click to open full view (#25)', () => {
  it('emits rowDblclick with the document on a native row double-click', async () => {
    const doc = makeDoc(1)
    const wrapper = mountTable(doc)
    const row = wrapper.find('tbody tr')
    expect(row.exists()).toBe(true)
    await row.trigger('dblclick')
    const emitted = wrapper.emitted('rowDblclick')
    expect(emitted).toBeTruthy()
    expect((emitted as unknown[][])[0][0]).toMatchObject({ id: 'doc1' })
  })
})
