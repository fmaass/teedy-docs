import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// Stub the quill CSS import (jsdom cannot parse it and it is irrelevant to the contract).
vi.mock('quill/dist/quill.snow.css', () => ({}))

// Stub PrimeVue's Editor so the test never boots Quill (which needs a real browser). The
// stub records the `formats` prop and renders the #toolbar slot, so the two restriction
// axes this component owns — the constrained Quill content model and the constrained
// toolbar — are both observable.
const capturedFormats = vi.hoisted(() => ({ value: null as string[] | null }))
const stubHolder = vi.hoisted(() => ({ component: null as unknown }))
vi.mock('primevue/editor', async () => {
  const { defineComponent: dc, h: hh } = await import('vue')
  const EditorStub = dc({
    name: 'EditorStub',
    props: ['modelValue', 'formats'],
    emits: ['update:modelValue'],
    setup(props: { formats?: string[] }, { slots }: { slots: Record<string, () => unknown> }) {
      capturedFormats.value = props.formats ?? null
      return () => hh('div', { class: 'editor-stub' }, slots.toolbar ? slots.toolbar() : [])
    },
  })
  stubHolder.component = EditorStub
  return { default: EditorStub }
})

import RichDescriptionEditor from './RichDescriptionEditor.vue'

function mountEditor() {
  return mount(RichDescriptionEditor, {
    props: { modelValue: '<p>hi</p>', ariaLabel: 'Description' },
  })
}

describe('RichDescriptionEditor', () => {
  it('constrains the Quill content model to the sanitizer allowlist', () => {
    mountEditor()
    const formats = capturedFormats.value
    expect(formats).not.toBeNull()
    // Exactly the formats the server-side DescriptionSanitizer allows.
    expect(new Set(formats)).toEqual(
      new Set([
        'header',
        'bold',
        'italic',
        'underline',
        'strike',
        'list',
        'link',
        'blockquote',
        'code-block',
      ]),
    )
    // Formats that must NOT be reachable (they have no sanitizer allowance).
    for (const banned of ['color', 'background', 'align', 'image', 'video', 'font', 'size']) {
      expect(formats).not.toContain(banned)
    }
  })

  it('renders only the allowlisted toolbar controls', () => {
    const wrapper = mountEditor()
    const html = wrapper.html()
    // Allowed controls present.
    for (const cls of [
      'ql-header',
      'ql-bold',
      'ql-italic',
      'ql-underline',
      'ql-strike',
      'ql-list',
      'ql-blockquote',
      'ql-code-block',
      'ql-link',
    ]) {
      expect(html).toContain(cls)
    }
    // Disallowed controls absent — a toolbar button for these would let a user apply a
    // format the sanitizer strips (a confusing silent-loss UX and a defense gap).
    for (const cls of ['ql-color', 'ql-background', 'ql-align', 'ql-image', 'ql-video', 'ql-font']) {
      expect(html).not.toContain(cls)
    }
  })

  // Double list-marker fix (#70). PrimeVue's Editor ships Quill-1-era list CSS that
  // draws the marker on `.ql-editor ol/ul > li::before`, while the Quill 2 runtime
  // draws its own marker on `.ql-ui::before`; both then render (e.g. a bullet item
  // shows "1." AND "•"). The fix is an unlayered scoped rule zeroing the PrimeVue
  // `li::before` marker so only Quill 2's `.ql-ui::before` remains. jsdom cannot
  // resolve scoped-CSS computed styles, so this is a source-contract pin: it fails if
  // the suppression rule is removed. The runtime effect was verified in Chromium.
  it('suppresses PrimeVue\'s duplicate list marker so only Quill 2\'s marker renders (#70)', () => {
    // Vitest runs with cwd at the webapp root; resolve the SFC from there.
    const src = readFileSync(resolve('src/components/RichDescriptionEditor.vue'), 'utf8')
    // The style block must neutralise the li's own ::before for both list wrappers.
    expect(src).toMatch(/\.ql-editor ol > li::before/)
    expect(src).toMatch(/\.ql-editor ul > li::before/)
    expect(src).toMatch(/content:\s*none/)
    // It must NOT touch the .ql-ui pseudo (that IS the Quill 2 marker we keep).
    expect(src).not.toMatch(/\.ql-ui::before\s*\{\s*content:\s*none/)
  })

  it('re-emits update:modelValue when the underlying editor changes', async () => {
    const wrapper = mountEditor()
    // Simulate the Editor (Quill) emitting new HTML through v-model.
    await wrapper.getComponent(stubHolder.component as never).vm.$emit(
      'update:modelValue',
      '<p>changed</p>',
    )
    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![0]).toEqual(['<p>changed</p>'])
  })
})
