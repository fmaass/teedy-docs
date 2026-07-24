import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import FileActionMenu from './FileActionMenu.vue'

// The per-file action menu is the reusable surface the file list (and, later,
// #73 "Edit pages" / #117 "Upload new version") mount their per-file actions onto.
// Its load-bearing contract: version history is always available (read-only), while
// rename + delete + the cover action are gated on `writable`, and an `extra` slot lets
// callers inject more writable-only actions. The cover action toggles between
// "set as cover" (when this file is not the cover) and "remove as cover" (when it is).
// t() is stubbed to the key so assertions target the stable aria-label keys, not copy.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

const file = { id: 'f1', name: 'report.pdf', mimetype: 'application/pdf' }

function mountMenu(
  writable: boolean,
  slots: Record<string, unknown> = {},
  isCover = false,
) {
  return mount(FileActionMenu, {
    props: { file, writable, isCover },
    global: { directives: { tooltip: {} } },
    slots,
  })
}

function labels(wrapper: ReturnType<typeof mountMenu>) {
  return wrapper.findAll('button').map((b) => b.attributes('aria-label'))
}

describe('FileActionMenu', () => {
  it('writable, not the cover: exposes history, set-as-cover, rename and delete', () => {
    const wrapper = mountMenu(true)
    expect(labels(wrapper)).toEqual([
      'ui.versions.title',
      'ui.set_as_cover',
      'rename',
      'ui.remove_file',
    ])
  })

  it('writable AND the current cover: offers remove-as-cover instead of set-as-cover', () => {
    const wrapper = mountMenu(true, {}, true)
    expect(labels(wrapper)).toEqual([
      'ui.versions.title',
      'ui.remove_as_cover',
      'rename',
      'ui.remove_file',
    ])
  })

  it('read-only: exposes ONLY version history — no cover action, no rename, no delete', () => {
    expect(labels(mountMenu(false))).toEqual(['ui.versions.title'])
    // Even when this file is the cover, a read-only viewer gets no cover mutation.
    expect(labels(mountMenu(false, {}, true))).toEqual(['ui.versions.title'])
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

  it('renders the writable-only `extra` slot for callers to mount extra actions (#73/#117)', () => {
    const slot = '<button class="extra-action" aria-label="extra">x</button>'
    expect(mountMenu(true, { extra: slot }).find('.extra-action').exists()).toBe(true)
    // Read-only never surfaces caller-injected write actions.
    expect(mountMenu(false, { extra: slot }).find('.extra-action').exists()).toBe(false)
  })
})
