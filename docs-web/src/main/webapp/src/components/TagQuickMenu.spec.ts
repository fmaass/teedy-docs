import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import TagQuickMenu from './TagQuickMenu.vue'
import { type Tag } from '../api/tag'
import { type DocumentListItem } from '../api/document'

// vue-i18n stub: echo the key so assertions target logic, not copy.
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

function tag(id: string, name: string): Tag {
  return { id, name, color: '#123456', parent: null }
}

const allTags = [
  tag('t1', 'Invoice'),
  tag('t2', 'Receipt'),
  tag('t3', 'Bank'),
  tag('t4', 'Archive'),
  tag('t5', 'Contract'),
  tag('t6', 'Draft'),
]

function makeDoc(tagIds: string[]): DocumentListItem {
  return {
    id: 'doc1',
    title: 'Doc',
    tags: tagIds.map((id) => allTags.find((t) => t.id === id)!),
  } as DocumentListItem
}

function mountMenu(props: Partial<InstanceType<typeof TagQuickMenu>['$props']> = {}) {
  return mount(TagQuickMenu, {
    props: {
      document: makeDoc(['t1']),
      allTags,
      tagCounts: { t2: 30, t3: 10, t4: 5, t5: 2, t6: 1 },
      ...props,
    },
    global: {
      plugins: [PrimeVue],
      stubs: {
        // Stub Popover so its content renders inline (no teleport/overlay in jsdom),
        // and expose show/hide so the component's defineExpose contract still works.
        Popover: {
          template: '<div class="popover-stub"><slot /></div>',
          methods: { show() {}, hide() {}, toggle() {} },
        },
        // Stub Select so we can read the `options` it is handed without booting the
        // full overlay; expose an update button to simulate a selection.
        Select: {
          props: ['options', 'modelValue'],
          emits: ['update:modelValue'],
          template:
            '<div class="select-stub" :data-count="options.length"><button v-for="o in options" :key="o.id" class="opt" :data-id="o.id" @click="$emit(\'update:modelValue\', o.id)">{{ o.name }}</button></div>',
        },
      },
    },
  })
}

describe('TagQuickMenu', () => {
  it('offers only assignable (not-yet-assigned) tags in the search select', () => {
    const wrapper = mountMenu({ document: makeDoc(['t1', 't3']) })
    const select = wrapper.find('.select-stub')
    // 6 total - 2 assigned = 4 assignable.
    expect(select.attributes('data-count')).toBe('4')
    const ids = select.findAll('.opt').map((b) => b.attributes('data-id'))
    expect(ids).not.toContain('t1')
    expect(ids).not.toContain('t3')
  })

  it('renders the top-5 most-used assignable tags as quick-add chips, most-used first', () => {
    // Assigned t1 → assignable: t2..t6. Counts: t2:30 t3:10 t4:5 t5:2 t6:1.
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    const chips = wrapper.findAll('.tqm-chip')
    expect(chips).toHaveLength(5)
    expect(chips.map((c) => c.text())).toEqual([
      'Receipt',
      'Bank',
      'Archive',
      'Contract',
      'Draft',
    ])
  })

  it('emits addTag when a quick-add chip is clicked', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    await wrapper.findAll('.tqm-chip')[0].trigger('click')
    expect(wrapper.emitted('addTag')).toEqual([['t2']])
  })

  it('emits addTag when a tag is chosen from the search select', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1']) })
    await wrapper.find('.opt[data-id="t5"]').trigger('click')
    expect(wrapper.emitted('addTag')).toEqual([['t5']])
  })

  it('shows the assigned tags with a remove affordance and emits removeTag', async () => {
    const wrapper = mountMenu({ document: makeDoc(['t1', 't3']) })
    const removeBtns = wrapper.findAll('.tqm-assigned .tag-remove-btn')
    expect(removeBtns).toHaveLength(2)
    await removeBtns[0].trigger('click')
    expect(wrapper.emitted('removeTag')).toEqual([['t1']])
  })

  it('shows an all-assigned notice and no chips when every tag is already on the doc', () => {
    const wrapper = mountMenu({ document: makeDoc(['t1', 't2', 't3', 't4', 't5', 't6']) })
    expect(wrapper.find('.select-stub').exists()).toBe(false)
    expect(wrapper.findAll('.tqm-chip')).toHaveLength(0)
    expect(wrapper.text()).toContain('ui.tag_menu.all_assigned')
  })
})
