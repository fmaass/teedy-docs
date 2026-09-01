import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'
import type { Tag, TagMaintenanceItem } from '../../api/tag'

// #298 parts 1 and 2 — deleting tags from the tag-management tree.
//
// The whole feature is one safety property: nothing that is still on a document may be
// deleted, and nothing is ever un-assigned to make a tag deletable ("as long as tags are
// sticking to any doc, do not delete them generally" — the reporter). The server decides
// that; these tests pin that the SCREEN never offers a delete the server would refuse,
// always asks before deleting, and reports what it removed.

const toastAdd = vi.hoisted(() => vi.fn())
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: toastAdd }) }))

// Captures confirmDanger's options so a test can drive the accept path deterministically —
// and, just as importantly, can assert the delete has NOT happened before it does.
const confirmMock = vi.hoisted(() => ({
  lastOptions: null as null | { header: string; message: string; accept: () => void | Promise<void> },
  confirmDanger: vi.fn(),
}))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({
    confirmDanger: (opts: unknown) => {
      confirmMock.lastOptions = opts as typeof confirmMock.lastOptions
      confirmMock.confirmDanger(opts)
    },
  }),
}))

const tagApiMock = vi.hoisted(() => ({
  listTags: vi.fn(),
  createTag: vi.fn(),
  getTagStats: vi.fn(),
  getTagMaintenance: vi.fn(),
  deleteTagSubtree: vi.fn(),
  deleteUnusedTags: vi.fn(),
  // Since #324 the compact create row hosts the icon field, so every mount of this page reads
  // the uploaded icon set.
  listTagIcons: vi.fn().mockResolvedValue({ data: { icons: [] } }),
}))
vi.mock('../../api/tag', () => tagApiMock)

import TagList from './TagList.vue'

// Keep / KeptChild carry a document; Gone / GoneChild and Orphan carry none; Ruled is empty
// but an auto-tagging rule points at it.
const TAGS: Tag[] = [
  { id: 'keep', name: 'Keep', color: '#111111', parent: null },
  { id: 'kept-child', name: 'KeptChild', color: '#222222', parent: 'keep' },
  { id: 'gone', name: 'Gone', color: '#333333', parent: null },
  { id: 'gone-child', name: 'GoneChild', color: '#444444', parent: 'gone' },
  { id: 'orphan', name: 'Orphan', color: '#555555', parent: null },
  { id: 'ruled', name: 'Ruled', color: '#666666', parent: null },
  { id: 'trashed', name: 'Trashed', color: '#777777', parent: null },
  { id: 'opaque', name: 'Opaque', color: '#888888', parent: null },
]

const STATS: Record<string, number> = { 'kept-child': 3 }

const MAINTENANCE: TagMaintenanceItem[] = [
  { id: 'keep', name: 'Keep', path: 'Keep', deletable: false, root: false, subtreeDocuments: 3, reason: 'documents' },
  {
    id: 'kept-child', name: 'KeptChild', path: 'Keep / KeptChild',
    deletable: false, root: false, subtreeDocuments: 3, reason: 'documents',
  },
  { id: 'gone', name: 'Gone', path: 'Gone', deletable: true, root: true, subtreeDocuments: 0 },
  { id: 'gone-child', name: 'GoneChild', path: 'Gone / GoneChild', deletable: true, root: false, subtreeDocuments: 0 },
  { id: 'orphan', name: 'Orphan', path: 'Orphan', deletable: true, root: true, subtreeDocuments: 0 },
  { id: 'ruled', name: 'Ruled', path: 'Ruled', deletable: false, root: false, subtreeDocuments: 0, reason: 'rule' },
  { id: 'trashed', name: 'Trashed', path: 'Trashed', deletable: false, root: false, subtreeDocuments: 0, reason: 'trash' },
  { id: 'opaque', name: 'Opaque', path: 'Opaque', deletable: false, root: false, subtreeDocuments: 0, reason: 'other' },
]

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: { template: '<div/>' } },
    { path: '/tags', name: 'tags', component: { template: '<div/>' } },
    { path: '/tags/:id', name: 'tag-edit', component: { template: '<div/>' }, props: true },
  ],
})

async function mountList() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router.push('/tags')
  await router.isReady()
  const wrapper = mount(TagList, {
    attachTo: document.body,
    global: {
      // Pinia: the create card reads the signed-in username from the auth store (#306) to show
      // the owner grant the server will create. A real, empty store answers "nobody", which
      // leaves this spec's delete affordances untouched.
      plugins: [i18n, router, createPinia(), PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
    },
  })
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountList>>

/** The per-node delete button of the node labelled `label`, or null when the node renders none. */
function deleteButton(wrapper: Wrapper, label: string): HTMLButtonElement | null {
  for (const node of wrapper.findAll('.tag-node')) {
    if (node.find('.tag-label').text() === label) {
      const button = node.find('.tag-delete-btn')
      return button.exists() ? (button.element as HTMLButtonElement) : null
    }
  }
  return null
}

/** The rows the cleanup dialog previews, as rendered text. */
function cleanupRows(): string[] {
  return Array.from(document.body.querySelectorAll('.cleanup-row')).map(
    (row) => (row.textContent ?? '').replace(/\s+/g, ' ').trim(),
  )
}

async function openCleanup(wrapper: Wrapper): Promise<void> {
  await wrapper.find('.tag-cleanup-btn').trigger('click')
  await flushPromises()
}

describe('TagList — deleting unused tags from the management tree (#298 parts 1 and 2)', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    toastAdd.mockReset()
    confirmMock.confirmDanger.mockReset()
    confirmMock.lastOptions = null
    tagApiMock.listTags.mockReset().mockResolvedValue({ data: { tags: TAGS } })
    tagApiMock.createTag.mockReset().mockResolvedValue({ data: { id: 'new' } })
    tagApiMock.getTagStats.mockReset().mockResolvedValue({ data: { stats: STATS } })
    tagApiMock.getTagMaintenance.mockReset().mockResolvedValue({ data: { tags: MAINTENANCE } })
    tagApiMock.deleteTagSubtree.mockReset().mockResolvedValue({
      data: {
        status: 'ok',
        count: 2,
        tags: [
          { id: 'gone', name: 'Gone', path: 'Gone' },
          { id: 'gone-child', name: 'GoneChild', path: 'Gone / GoneChild' },
        ],
        blocked: [],
      },
    })
    tagApiMock.deleteUnusedTags.mockReset().mockResolvedValue({
      data: {
        status: 'ok',
        count: 3,
        tags: [
          { id: 'gone', name: 'Gone', path: 'Gone' },
          { id: 'gone-child', name: 'GoneChild', path: 'Gone / GoneChild' },
          { id: 'orphan', name: 'Orphan', path: 'Orphan' },
        ],
        blocked: [],
      },
    })
  })

  it('offers the delete on a tag whose whole subtree is unused', async () => {
    const wrapper = await mountList()
    const button = deleteButton(wrapper, 'Gone')
    expect(button, 'the tree renders a delete action per node').not.toBeNull()
    expect(button!.disabled).toBe(false)
  })

  it('refuses the delete on a tag whose subtree carries documents, and says how many', async () => {
    const wrapper = await mountList()
    // Keep itself carries nothing — its CHILD does. The whole point of the subtree rule is
    // that this still blocks the parent, and the screen must say why.
    const button = deleteButton(wrapper, 'Keep')
    expect(button!.disabled).toBe(true)
    expect(button!.getAttribute('title')).toContain('3')
  })

  it('refuses the delete on a tag an auto-tagging rule points at', async () => {
    const wrapper = await mountList()
    const button = deleteButton(wrapper, 'Ruled')
    expect(button!.disabled).toBe(true)
    // Not the document message: this tag really carries zero documents, so quoting a count
    // would tell the user to go looking for documents that do not exist.
    expect(button!.getAttribute('title')).toBe(en.ui.tags_page.blocked_rule)
  })

  it('offers no delete at all until the maintenance verdict has arrived', async () => {
    // A verdict that never arrives must not read as "nothing is in use". This is the
    // fail-safe direction: no data, no delete.
    tagApiMock.getTagMaintenance.mockRejectedValue(new Error('offline'))
    const wrapper = await mountList()
    expect(deleteButton(wrapper, 'Gone')!.disabled).toBe(true)
    expect(deleteButton(wrapper, 'Orphan')!.disabled).toBe(true)
  })

  it('asks before deleting a subtree, and deletes nothing until the confirm is accepted', async () => {
    const wrapper = await mountList()
    await wrapper.find('.tag-delete-btn:not([disabled])').trigger('click')
    await flushPromises()

    expect(confirmMock.confirmDanger).toHaveBeenCalledTimes(1)
    expect(tagApiMock.deleteTagSubtree, 'nothing is deleted by asking').not.toHaveBeenCalled()
    // The prompt names the tag and how much goes with it — Gone takes GoneChild along.
    expect(confirmMock.lastOptions!.message).toContain('Gone')
    expect(confirmMock.lastOptions!.message).toContain('2')

    await confirmMock.lastOptions!.accept()
    await flushPromises()
    expect(tagApiMock.deleteTagSubtree).toHaveBeenCalledWith('gone')
  })

  it('reports what a subtree delete removed', async () => {
    const wrapper = await mountList()
    await wrapper.find('.tag-delete-btn:not([disabled])').trigger('click')
    await flushPromises()
    await confirmMock.lastOptions!.accept()
    await flushPromises()

    const reported = toastAdd.mock.calls.map((call) => JSON.stringify(call[0])).join('\n')
    expect(reported, 'the report names the tags that went').toContain('GoneChild')
    expect(reported).toContain('Gone')
  })

  it('previews the unused subtree roots and deletes nothing by previewing', async () => {
    const wrapper = await mountList()
    await openCleanup(wrapper)

    const rows = cleanupRows()
    expect(rows.some((row) => row.includes('Gone')), 'the unused root Gone is previewed').toBe(true)
    expect(rows.some((row) => row.includes('Orphan')), 'the unused root Orphan is previewed').toBe(true)
    expect(rows.some((row) => row.includes('Keep')), 'a used branch is never previewed').toBe(false)
    expect(rows.some((row) => row.includes('Ruled')), "a rule's target tag is never previewed").toBe(false)
    // A root stands for its whole branch, so GoneChild is not a row of its own.
    expect(rows.filter((row) => row.includes('GoneChild')).length).toBe(0)
    expect(tagApiMock.deleteUnusedTags, 'the preview deletes nothing').not.toHaveBeenCalled()
  })

  it('deletes the unused tags only on an explicit confirm, and reports exactly what went', async () => {
    const wrapper = await mountList()
    await openCleanup(wrapper)
    expect(tagApiMock.deleteUnusedTags).not.toHaveBeenCalled()

    const confirmButton = document.body.querySelector('.cleanup-confirm-btn') as HTMLButtonElement
    expect(confirmButton, 'the dialog carries its own confirm button').not.toBeNull()
    // It says how many tags go, so the confirm is informed rather than blind.
    expect(confirmButton.textContent).toContain('3')

    confirmButton.click()
    await flushPromises()

    expect(tagApiMock.deleteUnusedTags).toHaveBeenCalledTimes(1)
    const report = (document.body.querySelector('.cleanup-result')?.textContent ?? '')
      .replace(/\s+/g, ' ')
    expect(report, 'the result names every deleted tag').toContain('Gone')
    expect(report).toContain('GoneChild')
    expect(report).toContain('Orphan')
    expect(report, 'and states the count').toContain('3')
  })

  it('refuses a tag only a trashed document still holds, without quoting a zero count', async () => {
    const wrapper = await mountList()
    const button = deleteButton(wrapper, 'Trashed')
    expect(button!.disabled).toBe(true)
    // The node's own count reads 0 — the tree counts ACTIVE documents — so the reason must not be
    // the documents one, which would send the user hunting for a document that is in the trash.
    expect(button!.getAttribute('title')).toBe(en.ui.tags_page.blocked_trash)
  })

  it('refuses a branch it will not explain without hinting at hidden tags', async () => {
    const wrapper = await mountList()
    const title = deleteButton(wrapper, 'Opaque')!.getAttribute('title') ?? ''
    expect(title).toBe(en.ui.tags_page.blocked_other)
    // The whole point of the generic reason: it must not tell the caller that a tag they cannot
    // see exists under one they own.
    expect(title.toLowerCase()).not.toContain('permission')
    expect(title.toLowerCase()).not.toContain('sub-tag')
    expect(title.toLowerCase()).not.toContain('edit')
  })

  it('reports the tags the server kept because they became used mid-sweep', async () => {
    tagApiMock.deleteUnusedTags.mockResolvedValue({
      data: {
        status: 'ok',
        count: 1,
        tags: [{ id: 'orphan', name: 'Orphan', path: 'Orphan' }],
        blocked: [{ id: 'gone', name: 'Gone', path: 'Gone' }],
      },
    })
    const wrapper = await mountList()
    await openCleanup(wrapper)
    ;(document.body.querySelector('.cleanup-confirm-btn') as HTMLButtonElement).click()
    await flushPromises()

    // Reporting only the successes would make a kept tag indistinguishable from one that was
    // never in the run — which is exactly what the pre-delete re-check must not be allowed to hide.
    const skipped = document.body.querySelector('.cleanup-skipped')?.textContent ?? ''
    expect(skipped).toContain('Gone')
    const result = document.body.querySelector('.cleanup-result')?.textContent ?? ''
    expect(result).toContain('Orphan')
  })

  it('says there is nothing to clean up when no subtree is unused', async () => {
    tagApiMock.getTagMaintenance.mockResolvedValue({
      data: {
        tags: MAINTENANCE.map((item) => ({
          ...item, deletable: false, root: false, reason: 'documents' as const, subtreeDocuments: 1,
        })),
      },
    })
    const wrapper = await mountList()
    await openCleanup(wrapper)

    expect(cleanupRows().length).toBe(0)
    expect(document.body.querySelector('.cleanup-empty')?.textContent).toContain(
      en.ui.tags_page.cleanup_none,
    )
    const confirmButton = document.body.querySelector('.cleanup-confirm-btn') as HTMLButtonElement
    expect(confirmButton.disabled, 'nothing to delete, nothing to confirm').toBe(true)
  })
})
