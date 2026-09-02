import { describe, it, expect, beforeAll, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import { VueQueryPlugin } from '@tanstack/vue-query'
import en from '../locale/en.json'
import AclEditor from './AclEditor.vue'
import TagForm from './TagForm.vue'

// #288 — the tag form is ONE implementation with TWO hosts: the tag management edit page
// (TagEdit.vue) and the document editor's create-tag side panel (TagCreatePanel.vue). Before
// this, the form existed only inside TagEdit's template, which is why the reporter's ask
// ("the tag edit functionality could be a reusable component") was an extraction and not a
// second form. These assertions pin the extracted contract: the fields, the id prefixing both
// hosts depend on, the parent Select's type-to-filter (#14), the permissions section, and the
// two slots the panel needs but the management page does not.

vi.mock('../api/acl', () => ({
  addAcl: vi.fn(),
  deleteAcl: vi.fn(),
  searchAclTargets: vi.fn().mockResolvedValue({ data: { users: [], groups: [] } }),
}))
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))
// The icon field (#287) reads the instance's uploaded icon set. Nothing in this file is about
// that list, so it answers empty — what a fresh installation has.
vi.mock('../api/tag', () => ({
  listTagIcons: vi.fn().mockResolvedValue({ data: { icons: [] } }),
}))
vi.mock('../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))

beforeAll(() => {
  if (typeof window.matchMedia !== 'function') {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }),
    })
  }
})

const PARENT_OPTIONS = [
  { label: '(none — root level)', value: null },
  { label: 'Alpha', value: 'a' },
]

function mountForm(props: Record<string, unknown> = {}, slots: Record<string, string> = {}) {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  return mount(TagForm, {
    props: {
      name: 'Bravo',
      color: '222222',
      parent: null,
      parentOptions: PARENT_OPTIONS,
      idPrefix: 'tag',
      acl: { sourceId: 'b', entries: [], writable: true },
      ...props,
    } as never,
    slots,
    global: {
      plugins: [i18n, PrimeVue, VueQueryPlugin],
      directives: { tooltip: {} },
    },
  })
}

describe('TagForm — the fields both hosts share', () => {
  it('renders name, colour and parent, prefixing the ids the host asked for', () => {
    // `#tag-name` and `#tag-parent` are e2e selectors on the tag management page
    // (e2e/tags.spec.ts). The panel takes a different prefix so the two forms can never
    // collide on one page.
    const wrapper = mountForm()
    expect(wrapper.find('input#tag-name').exists()).toBe(true)
    expect(wrapper.find('label[for="tag-name"]').text()).toBe('Name')
    expect(wrapper.find('#tag-color-label').text()).toBe('Color')
    expect(wrapper.find('label[for="tag-parent"]').text()).toBe('Parent tag')

    const panel = mountForm({ idPrefix: 'tag-create' })
    expect(panel.find('input#tag-create-name').exists()).toBe(true)
    expect(panel.find('input#tag-name').exists()).toBe(false)
  })

  it('keeps type-to-filter on the parent Select (#14, critical at ~350 tags)', () => {
    const select = mountForm().findComponent({ name: 'Select' })
    expect(select.props('filter')).toBe(true)
    expect(select.props('options')).toEqual(PARENT_OPTIONS)
  })

  it('reports every field edit to its host rather than owning the value', async () => {
    const wrapper = mountForm()
    await wrapper.find('input#tag-name').setValue('Charlie')
    expect(wrapper.emitted('update:name')?.at(-1)).toEqual(['Charlie'])

    wrapper.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', 'a')
    expect(wrapper.emitted('update:parent')?.at(-1)).toEqual(['a'])

    wrapper.findComponent({ name: 'ColorPicker' }).vm.$emit('update:modelValue', 'ff0000')
    expect(wrapper.emitted('update:color')?.at(-1)).toEqual(['ff0000'])
  })

  it('previews the tag in its colour, falling back to a placeholder while unnamed', async () => {
    const wrapper = mountForm({ name: '' })
    expect(wrapper.find('.color-preview').text()).toBe('Preview')
    await wrapper.setProps({ name: 'Bravo' })
    expect(wrapper.find('.color-preview').text()).toBe('Bravo')
    // jsdom normalises an inline hex background to its rgb() form.
    expect(wrapper.find('.color-preview').attributes('style')).toContain('rgb(34, 34, 34)')
  })
})

// #303 — the colour could only be POINTED AT: the swatch picker has no text entry, so a user
// holding a brand's hex code had no way to enter it. These assertions pin the field that takes
// one, and the two properties that make it trustworthy — an invalid code never reaches the tag,
// and whatever does reach it is in the picker's own canonical form (six LOWERCASE hex digits,
// no '#', which is what `Number.prototype.toString(16)` inside PrimeVue's RGBtoHEX produces and
// what both hosts have always stored).
describe('TagForm — the manual hex code field (#303)', () => {
  const hexField = (wrapper: ReturnType<typeof mountForm>) => wrapper.find('input#tag-color-hex')
  const errorText = (wrapper: ReturnType<typeof mountForm>) => wrapper.find('.field-error')

  it('shows the tag\'s current colour as an editable #rrggbb code, named for a screen reader', () => {
    const wrapper = mountForm()
    const field = hexField(wrapper)
    expect(field.exists()).toBe(true)
    expect((field.element as HTMLInputElement).value).toBe('#222222')
    // The group label (`#tag-color-label`) names the PICKER; the text field needs a name of
    // its own or a screen reader announces two controls with one label between them.
    expect(field.attributes('aria-label')).toBe('Hex color code')
    expect(field.attributes('id')).toBe('tag-color-hex')
    expect(mountForm({ idPrefix: 'tag-create' }).find('input#tag-create-color-hex').exists()).toBe(true)
  })

  it('reports a typed #RRGGBB in the picker\'s canonical form — lowercase, no leading #', async () => {
    const wrapper = mountForm()
    await hexField(wrapper).setValue('#FF00AA')
    expect(wrapper.emitted('update:color')?.at(-1)).toEqual(['ff00aa'])
    expect(errorText(wrapper).exists()).toBe(false)
  })

  it('takes a bare RRGGBB, once it is complete', async () => {
    const wrapper = mountForm()
    await hexField(wrapper).setValue('336699')
    expect(wrapper.emitted('update:color')?.at(-1)).toEqual(['336699'])
  })

  it('never mistakes a half-typed code for a 3-digit shorthand', async () => {
    // The trap: '336' on the way to '336699' is ITSELF a valid CSS shorthand. Reading shorthand
    // while the user is still typing therefore propagated #333366 three characters in — and a
    // pause, a tab away or a Save at that instant persisted it, silently and wrongly. So a
    // KEYSTROKE propagates a COMPLETE six-digit code and nothing else.
    for (const prefix of ['', '#']) {
      const wrapper = mountForm()
      const field = hexField(wrapper)
      for (const partial of ['3', '33', '336', '3366', '33669']) {
        await field.setValue(prefix + partial)
        expect(
          wrapper.emitted('update:color'),
          `"${prefix}${partial}" is a code being typed, not a colour the user has chosen`,
        ).toBeUndefined()
        expect(errorText(wrapper).exists()).toBe(false)
      }
      await field.setValue(prefix + '336699')
      expect(wrapper.emitted('update:color')).toEqual([['336699']])
    }
  })

  it('expands a #RGB shorthand when the field is left, never while it is being typed', async () => {
    const wrapper = mountForm()
    const field = hexField(wrapper)
    await field.setValue('#f0a')
    expect(wrapper.emitted('update:color'), 'this may still be the start of #f0a123').toBeUndefined()

    await field.trigger('blur')
    expect(wrapper.emitted('update:color')).toEqual([['ff00aa']])
    expect((field.element as HTMLInputElement).value).toBe('#ff00aa')
    expect(errorText(wrapper).exists()).toBe(false)
  })

  it('refuses a bare 3-digit code — not one of the three forms it offers', async () => {
    // '#RGB' is the CSS shorthand; 'F0A' on its own is an unfinished six-digit code. Guessing
    // between them is exactly how '336' turned into #333366, so it is not guessed at.
    const wrapper = mountForm()
    const field = hexField(wrapper)
    await field.setValue('F0A')
    expect(errorText(wrapper).exists()).toBe(false)

    await field.trigger('blur')
    expect(wrapper.emitted('update:color')).toBeUndefined()
    expect(errorText(wrapper).exists()).toBe(true)
    expect((field.element as HTMLInputElement).value).toBe('F0A')
    expect(wrapper.findComponent({ name: 'ColorPicker' }).props('modelValue')).toBe('222222')
  })

  it('refuses a code that is not a colour, leaving the tag on its last valid one', async () => {
    const wrapper = mountForm()
    await hexField(wrapper).setValue('#12345')
    await hexField(wrapper).trigger('blur')

    expect(wrapper.emitted('update:color'), 'an invalid code must never reach the host').toBeUndefined()
    expect(wrapper.findComponent({ name: 'ColorPicker' }).props('modelValue')).toBe('222222')
    expect(errorText(wrapper).text()).toContain('#336699')
    // The message has to be reachable from the field it is about, not just visible near it.
    expect(hexField(wrapper).attributes('aria-invalid')).toBe('true')
    expect(hexField(wrapper).attributes('aria-describedby')).toBe('tag-color-hex-error')
    expect(wrapper.find('#tag-color-hex-error').exists()).toBe(true)
  })

  it('holds its judgement while the code is still half-typed, and gives it on blur', async () => {
    // Six keystrokes make a colour; scolding after the first four is noise, not feedback.
    const wrapper = mountForm()
    await hexField(wrapper).setValue('#33')
    expect(errorText(wrapper).exists()).toBe(false)
    expect(wrapper.emitted('update:color')).toBeUndefined()

    // Leaving the field is the point at which a half-typed code IS wrong — otherwise a Save
    // click would quietly keep the old colour with nothing on screen to explain it.
    await hexField(wrapper).trigger('blur')
    expect(errorText(wrapper).exists()).toBe(true)

    // A character that can never be part of a colour is wrong immediately.
    const bad = mountForm()
    await hexField(bad).setValue('#33zz')
    expect(errorText(bad).exists()).toBe(true)
  })

  it('follows the picker: a colour chosen there rewrites the code shown here', async () => {
    const wrapper = mountForm()
    wrapper.findComponent({ name: 'ColorPicker' }).vm.$emit('update:modelValue', '00ff00')
    expect(wrapper.emitted('update:color')?.at(-1)).toEqual(['00ff00'])

    // The host owns the value, so the round trip back through the prop is what the field
    // actually reacts to — the same path a host-side reset or a freshly loaded tag takes.
    await wrapper.setProps({ color: '00ff00' })
    expect((hexField(wrapper).element as HTMLInputElement).value).toBe('#00ff00')
  })

  it('does not fight the typist: the prop echoing back a code does not rewrite it mid-edit', async () => {
    const wrapper = mountForm()
    await hexField(wrapper).setValue('#FF00AA')
    // What a host does with the emit: writes it back as the prop.
    await wrapper.setProps({ color: 'ff00aa' })
    expect((hexField(wrapper).element as HTMLInputElement).value).toBe('#FF00AA')
  })

  it('settles a complete code into canonical form once the field is left', async () => {
    const wrapper = mountForm()
    await hexField(wrapper).setValue('FF00AA')
    await hexField(wrapper).trigger('blur')
    expect((hexField(wrapper).element as HTMLInputElement).value).toBe('#ff00aa')
    expect(errorText(wrapper).exists()).toBe(false)
  })

  it('puts the current colour back when the field is left empty', async () => {
    // Clearing the box is not "the tag has no colour" — it has one, and it is still on screen
    // in the picker and the preview chip.
    const wrapper = mountForm()
    await hexField(wrapper).setValue('')
    await hexField(wrapper).trigger('blur')
    expect((hexField(wrapper).element as HTMLInputElement).value).toBe('#222222')
    expect(errorText(wrapper).exists()).toBe(false)
    expect(wrapper.emitted('update:color')).toBeUndefined()
  })
})

describe('TagForm — the permissions section', () => {
  it('hands the host-owned ACL state straight to the shared AclEditor', () => {
    const immutable = () => true
    const beforeAdd = () => true
    const entries = [{ perm: 'READ' as const, id: 'u1', name: 'bob', type: 'USER' as const }]
    const wrapper = mountForm({
      acl: { sourceId: 'b', entries, writable: true, immutable, beforeAdd },
    })

    expect(wrapper.find('.acl-heading').text()).toBe('Permissions')
    expect(wrapper.find('.acl-desc').text()).toContain('every document that carries this tag')

    const editor = wrapper.findComponent(AclEditor)
    expect(editor.props('sourceId')).toBe('b')
    expect(editor.props('acls')).toEqual(entries)
    expect(editor.props('writable')).toBe(true)
    expect(editor.props('immutable')).toBe(immutable)
    expect(editor.props('beforeAdd')).toBe(beforeAdd)
    expect(editor.props('deferred')).toBeFalsy()
  })

  it('forwards the deferred flag and re-emits what an unsaved tag collects', () => {
    const wrapper = mountForm({
      acl: { sourceId: '', entries: [], writable: true, deferred: true },
    })
    const editor = wrapper.findComponent(AclEditor)
    expect(editor.props('deferred')).toBe(true)

    const grant = { perm: 'READ' as const, id: 'u9', name: 'bob', type: 'USER' as const }
    editor.vm.$emit('add', grant)
    editor.vm.$emit('remove', grant)
    editor.vm.$emit('changed')
    expect(wrapper.emitted('acl-add')).toEqual([[grant]])
    expect(wrapper.emitted('acl-remove')).toEqual([[grant]])
    expect(wrapper.emitted('acl-changed')).toEqual([[]])
  })
})

describe('TagForm — the slots that let one form serve two very different hosts', () => {
  it('renders nothing extra when a host supplies no slot content (the management page)', () => {
    const wrapper = mountForm()
    expect(wrapper.find('.host-lead').exists()).toBe(false)
    expect(wrapper.find('.host-hint').exists()).toBe(false)
    expect(wrapper.find('.host-actions').exists()).toBe(false)
  })

  it('places the lead above the fields, the hint above the ACL editor, actions below them', () => {
    const wrapper = mountForm(
      {},
      {
        lead: '<p class="host-lead">lead</p>',
        'permissions-hint': '<div class="host-hint">hint</div>',
        actions: '<div class="host-actions">actions</div>',
      },
    )
    const html = wrapper.html()
    expect(html.indexOf('host-lead')).toBeLessThan(html.indexOf('tag-name'))
    expect(html.indexOf('host-actions')).toBeGreaterThan(html.indexOf('tag-parent'))
    // The reminder sits between the section's description and the editor it is about (#288
    // mockup), never below the editor where it would read as a result rather than a warning.
    expect(html.indexOf('acl-desc')).toBeLessThan(html.indexOf('host-hint'))
    expect(html.indexOf('host-hint')).toBeLessThan(html.indexOf('acl-editor'))
  })

  it('drops the card chrome in flat mode, and keeps it by default', () => {
    // The management page has always shown the form as two cards; the side panel is already
    // a surface of its own, so the same markup renders flat inside it.
    expect(mountForm().findAll('.tag-form-card.flat')).toHaveLength(0)
    expect(mountForm().findAll('.tag-form-card')).toHaveLength(2)
    expect(mountForm({ flat: true }).findAll('.tag-form-card.flat')).toHaveLength(2)
  })
})

// #280 — the synonyms chips editor. It renders ONLY for a host that manages synonyms: the
// approved design puts them on the tag edit page, and the two create hosts must keep rendering
// the form exactly as they did (the side panel's zero-DOM-while-closed invariant, and every
// captured baseline that screenshots a surface holding this form).
describe('TagForm — synonyms (#280)', () => {
  const OTHER_TAGS = [
    { id: 'a', name: 'Alpha', color: '#111', parent: null, synonyms: ['Erste'] },
    { id: 'b', name: 'Bravo', color: '#222', parent: null },
    { id: 'c', name: 'Alphabet', color: '#333', parent: null },
  ]

  function mountSynonyms(props: Record<string, unknown> = {}) {
    return mountForm({ synonyms: [], synonymTags: OTHER_TAGS, synonymTagId: 'b', ...props })
  }

  async function type(wrapper: ReturnType<typeof mountForm>, text: string) {
    await wrapper.find('#tag-synonym').setValue(text)
  }

  it('renders nothing for a host that does not manage synonyms', () => {
    const wrapper = mountForm()
    expect(wrapper.find('#tag-synonym').exists()).toBe(false)
    expect(wrapper.find('.synonym-chips').exists()).toBe(false)
  })

  it('renders one removable chip per synonym, plus the add field', () => {
    const wrapper = mountSynonyms({ synonyms: ['Rechnung', 'Quittung'] })
    expect(wrapper.findAll('.synonym-chip').map((chip) => chip.text())).toEqual([
      'Rechnung',
      'Quittung',
    ])
    expect(wrapper.find('#tag-synonym').exists()).toBe(true)
  })

  it('adds the typed name on the Add button and on Enter, and clears the field', async () => {
    const wrapper = mountSynonyms({ synonyms: ['Rechnung'] })
    await type(wrapper, 'Quittung')
    await wrapper.find('.synonym-row button').trigger('click')

    expect(wrapper.emitted('update:synonyms')?.at(-1)).toEqual([['Rechnung', 'Quittung']])
    expect((wrapper.find('#tag-synonym').element as HTMLInputElement).value).toBe('')

    await type(wrapper, 'Faktura')
    await wrapper.find('#tag-synonym').trigger('keydown.enter')
    expect(wrapper.emitted('update:synonyms')?.at(-1)).toEqual([['Rechnung', 'Faktura']])
  })

  it('trims the typed name', async () => {
    const wrapper = mountSynonyms()
    await type(wrapper, '  Quittung  ')
    await wrapper.find('.synonym-row button').trigger('click')
    expect(wrapper.emitted('update:synonyms')?.at(-1)).toEqual([['Quittung']])
  })

  it('removes a chip', async () => {
    const wrapper = mountSynonyms({ synonyms: ['Rechnung', 'Quittung'] })
    await wrapper.findAll('.synonym-remove')[0].trigger('click')
    expect(wrapper.emitted('update:synonyms')?.at(-1)).toEqual([['Quittung']])
  })

  it('refuses a name already on this tag, and says so', async () => {
    const wrapper = mountSynonyms({ synonyms: ['Rechnung'] })
    await type(wrapper, 'rechnung')

    expect(wrapper.find('.synonym-notice').text()).toContain('already on this tag')
    expect(wrapper.find('.synonym-row button').attributes('disabled')).toBeDefined()
    await wrapper.find('#tag-synonym').trigger('keydown.enter')
    expect(wrapper.emitted('update:synonyms')).toBeUndefined()
  })

  it('refuses the tag\'s own name for the same reason', async () => {
    // `name` is 'Bravo' in the shared fixture.
    const wrapper = mountSynonyms()
    await type(wrapper, 'bravo')
    expect(wrapper.find('.synonym-notice').text()).toContain('already on this tag')
  })

  /**
   * The reporter's ask: see the word is taken BEFORE saving. It is a warning, not a block —
   * the server owns the verdict, and the chip may still be added so the refusal comes from the
   * one place that can name the conflict.
   */
  it('warns while typing that another visible tag already uses the word', async () => {
    const wrapper = mountSynonyms()
    await type(wrapper, 'Alpha')

    const notice = wrapper.find('.synonym-notice')
    expect(notice.classes()).toContain('warn')
    expect(notice.text()).toContain('Alpha')
    expect(wrapper.find('.synonym-row button').attributes('disabled')).toBeUndefined()
  })

  it('warns when the word is another tag\'s SYNONYM, naming that tag', async () => {
    const wrapper = mountSynonyms()
    await type(wrapper, 'Erste')

    const notice = wrapper.find('.synonym-notice')
    expect(notice.classes()).toContain('warn')
    expect(notice.text()).toContain('Alpha')
  })

  it('lists merely SIMILAR names as information rather than a warning', async () => {
    const wrapper = mountSynonyms()
    await type(wrapper, 'Alph')

    const notice = wrapper.find('.synonym-notice')
    expect(notice.classes()).toContain('info')
    expect(notice.text()).toContain('Alpha')
    expect(notice.text()).toContain('Alphabet')
  })

  it('ignores the tag being edited when it looks for a conflict', async () => {
    // Bravo is in the tag list AND is the tag under edit: its own name must not be reported as
    // somebody else's, or every re-save of an unchanged form would look like a collision.
    const wrapper = mountSynonyms({ name: 'Bravo', synonymTagId: 'b' })
    await type(wrapper, 'Charlie')
    expect(wrapper.find('.synonym-notice').exists()).toBe(false)
  })
})

// TEEDY-153 — the swap the synonyms feature deferred: one action makes a synonym the tag's main
// name and the old name a synonym of it. It happens entirely in the form's own state and is
// persisted by the page's ordinary Save, which sends name and synonyms in the ONE request the
// server already accepts — so the tag keeps its id, its documents and its ACLs, and every word
// that resolved before resolves after.
describe('TagForm — promoting a synonym to the main name (TEEDY-153)', () => {
  const OTHER_TAGS = [{ id: 'a', name: 'Alpha', color: '#111', parent: null, synonyms: ['Erste'] }]

  function mountSwap(props: Record<string, unknown> = {}) {
    return mountForm({
      name: 'Rechnung',
      synonyms: ['Quittung', 'Beleg'],
      synonymTags: OTHER_TAGS,
      synonymTagId: 'b',
      ...props,
    })
  }

  it('offers the action on every chip', () => {
    const wrapper = mountSwap()
    expect(wrapper.findAll('.synonym-promote')).toHaveLength(2)
    // The chip still reads as the word it holds: the action is an icon button, so the text the
    // list of chips shows is unchanged.
    expect(wrapper.findAll('.synonym-chip').map((chip) => chip.text())).toEqual([
      'Quittung',
      'Beleg',
    ])
  })

  it('reports the swap as one name change and one synonym-list change', async () => {
    const wrapper = mountSwap()
    await wrapper.findAll('.synonym-promote')[0].trigger('click')

    expect(wrapper.emitted('update:name')?.at(-1)).toEqual(['Quittung'])
    // The demoted name leads the list, and the synonym the swap did not touch keeps its place.
    expect(wrapper.emitted('update:synonyms')?.at(-1)).toEqual([['Rechnung', 'Beleg']])
  })

  it('shows the swapped pair once the host applies it', async () => {
    const wrapper = mountSwap()
    await wrapper.findAll('.synonym-promote')[0].trigger('click')
    // What `v-model:name` / `v-model:synonyms` do on the page.
    await wrapper.setProps({ name: 'Quittung', synonyms: ['Rechnung', 'Beleg'] })

    expect(wrapper.findAll('.synonym-chip').map((chip) => chip.text())).toEqual([
      'Rechnung',
      'Beleg',
    ])
    expect(wrapper.find('.synonym-swap-notice').text()).toContain('Quittung')
    expect(wrapper.find('.synonym-swap-notice').text()).toContain('Rechnung')
  })

  it('drops the notice as soon as the pair it announces is superseded', async () => {
    const wrapper = mountSwap()
    await wrapper.findAll('.synonym-promote')[0].trigger('click')
    await wrapper.setProps({ name: 'Quittung', synonyms: ['Rechnung', 'Beleg'] })
    expect(wrapper.find('.synonym-swap-notice').exists()).toBe(true)

    // A saved form is re-seeded from the server, and any later chip edit replaces the list too:
    // either way the message is about a state the form no longer holds.
    await wrapper.setProps({ synonyms: ['Beleg', 'Rechnung'] })
    expect(wrapper.find('.synonym-swap-notice').exists()).toBe(false)
  })

  it('does not demote an empty name into a chip', async () => {
    const wrapper = mountSwap({ name: '' })
    await wrapper.findAll('.synonym-promote')[0].trigger('click')

    expect(wrapper.emitted('update:name')?.at(-1)).toEqual(['Quittung'])
    expect(wrapper.emitted('update:synonyms')?.at(-1)).toEqual([['Beleg']])
  })

  it('never leaves the promoted word behind as a synonym of itself', async () => {
    // The server refuses a tag whose name is also its own synonym, so the promoted word has to
    // leave the list however it is spelled — the fold is the same case-insensitive one the rest
    // of the form uses.
    const wrapper = mountSwap({ synonyms: ['quittung', 'Beleg'] })
    await wrapper.findAll('.synonym-promote')[0].trigger('click')

    expect(wrapper.emitted('update:name')?.at(-1)).toEqual(['quittung'])
    expect(wrapper.emitted('update:synonyms')?.at(-1)).toEqual([['Rechnung', 'Beleg']])
  })

  it('renders no action for a host that does not manage synonyms', () => {
    expect(mountForm().find('.synonym-promote').exists()).toBe(false)
  })
})
