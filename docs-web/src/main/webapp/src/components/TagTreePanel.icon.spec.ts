import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import TagTreePanel from './TagTreePanel.vue'
import { TAG_ICONS_STORAGE_KEY, resetTagIconsVisibility } from '../composables/useTagIcons'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (k: string, params?: Record<string, unknown>) =>
      params && 'count' in params ? `${k}:${params.count}` : k,
  }),
}))

/**
 * The sidebar tag tree's half of the #287 no-icon guarantee.
 *
 * This panel is INSIDE the document-list screenshot the standing visual gate compares — it is the
 * left panel of that page — and none of the gate's fixtures carries an icon. So the rule the chip
 * is held to holds here too: a tag with no icon must contribute no node.
 */

const modeOptions = [
  { label: 'AND', value: 'and' as const },
  { label: 'OR', value: 'or' as const },
]

function node(id: string, name: string, icon?: string) {
  return {
    key: id,
    label: name,
    data: { id, name, color: '#123456', parent: null, ...(icon ? { icon } : {}) },
    children: [],
  }
}

function mountPanel(nodes: unknown[]) {
  return mount(TagTreePanel, {
    props: {
      tagMode: 'and',
      modeOptions,
      tagTreeNodes: nodes,
      expandedKeys: {},
      selectedTagIds: new Set<string>(),
      excludedTagIds: new Set<string>(),
      tagCounts: {},
    } as never,
    attachTo: document.body,
    global: { plugins: [[PrimeVue, { theme: 'none' }]] },
  })
}

describe('TagTreePanel — tag icons (#287)', () => {
  beforeEach(() => {
    localStorage.clear()
    resetTagIconsVisibility()
  })

  it('adds NOTHING to a node whose tag has no icon', () => {
    const wrapper = mountPanel([node('t1', 'plain')])
    const row = wrapper.find('.tag-tree-node')
    expect(row.exists()).toBe(true)
    expect(row.findAll('.tag-icon')).toHaveLength(0)
    // The node's markup is the colour dot and the name, exactly as before icons existed — no
    // wrapper, no placeholder, nothing between them.
    expect(row.find('.tag-dot').exists()).toBe(true)
    expect(row.text()).toBe('plain')
  })

  it('draws an emoji between the colour dot and the name when the tag has one', () => {
    const wrapper = mountPanel([node('t1', 'medal', 'emoji:\u{1F396}\u{FE0F}')])
    const row = wrapper.find('.tag-tree-node')
    expect(row.find('.tag-icon-emoji').text()).toBe('\u{1F396}\u{FE0F}')
    expect(row.text()).toBe('\u{1F396}\u{FE0F}medal')
  })

  it('draws an uploaded icon as an image pointing at the icon endpoint', () => {
    const wrapper = mountPanel([node('t1', 'vendor', 'set:icon-a')])
    const img = wrapper.find('.tag-tree-node img.tag-icon')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('api/tag/icon/icon-a/data')
  })

  it('renders no icon at all once the hide-icons preference is set', () => {
    localStorage.setItem(TAG_ICONS_STORAGE_KEY, 'hidden')
    resetTagIconsVisibility()
    const wrapper = mountPanel([node('t1', 'medal', 'emoji:\u{1F396}\u{FE0F}')])
    const row = wrapper.find('.tag-tree-node')
    expect(row.findAll('.tag-icon')).toHaveLength(0)
    expect(row.text()).toBe('medal')
  })

  it('leaves a mixed tree s icon-less nodes untouched', () => {
    const wrapper = mountPanel([
      node('t1', 'plain'),
      node('t2', 'medal', 'emoji:\u{1F396}\u{FE0F}'),
      node('t3', 'other'),
    ])
    const rows = wrapper.findAll('.tag-tree-node')
    expect(rows).toHaveLength(3)
    expect(rows[0].findAll('.tag-icon')).toHaveLength(0)
    expect(rows[1].findAll('.tag-icon')).toHaveLength(1)
    expect(rows[2].findAll('.tag-icon')).toHaveLength(0)
  })
})
