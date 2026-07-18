import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import FileActionMenu from './FileActionMenu.vue'

// The per-file action menu is the reusable surface the file list (and, later,
// #73 "Edit pages" / #117 "Upload new version") mount their per-file actions onto.
// Its load-bearing contract: version history is always available (read-only), while
// rename + delete are gated on `writable`, and an `extra` slot lets callers inject
// more writable-only actions. t() is stubbed to the key so assertions target the
// stable aria-label keys, not copy.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

const file = { id: 'f1', name: 'report.pdf', mimetype: 'application/pdf' }

function mountMenu(writable: boolean, slots: Record<string, unknown> = {}) {
  return mount(FileActionMenu, {
    props: { file, writable },
    global: { directives: { tooltip: {} } },
    slots,
  })
}

function labels(wrapper: ReturnType<typeof mountMenu>) {
  return wrapper.findAll('button').map((b) => b.attributes('aria-label'))
}

describe('FileActionMenu', () => {
  it('writable: exposes version history, rename and delete', () => {
    const wrapper = mountMenu(true)
    expect(labels(wrapper)).toEqual(['ui.versions.title', 'rename', 'ui.remove_file'])
  })

  it('read-only: exposes ONLY version history — no rename, no delete', () => {
    const wrapper = mountMenu(false)
    expect(labels(wrapper)).toEqual(['ui.versions.title'])
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

  it('renders the writable-only `extra` slot for callers to mount extra actions (#73/#117)', () => {
    const slot = '<button class="extra-action" aria-label="extra">x</button>'
    expect(mountMenu(true, { extra: slot }).find('.extra-action').exists()).toBe(true)
    // Read-only never surfaces caller-injected write actions.
    expect(mountMenu(false, { extra: slot }).find('.extra-action').exists()).toBe(false)
  })
})
