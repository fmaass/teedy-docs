#!/usr/bin/env node
// Exports representative Quill 2 editor HTML — one fixture per enabled format — so the
// Java DescriptionSanitizer allowlist is proven against the REAL serializer output the
// browser produces (quill.getSemanticHTML(), the same call PrimeVue's Editor emits),
// not against hand-guessed markup. The fixtures are checked into
// docs-core/src/test/resources/description-fixtures/ and round-tripped by
// TestDescriptionSanitizer: every permitted construct must survive sanitization intact.
//
// Regenerate after a Quill upgrade or an allowlist change:
//   node scripts/export-quill-fixtures.mjs
//
// Run from docs-web/src/main/webapp.

import { JSDOM } from 'jsdom'
import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const webappDir = resolve(scriptDir, '..')
const fixtureDir = resolve(
  webappDir,
  '../../../../docs-core/src/test/resources/description-fixtures',
)

// Quill 2 requires a browser-like global environment; jsdom provides it.
const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  url: 'http://localhost/',
  pretendToBeVisual: true,
})
globalThis.window = dom.window
globalThis.document = dom.window.document
// Quill/parchment reference these DOM constructors as bare globals.
for (const name of [
  'HTMLElement',
  'Element',
  'Node',
  'Text',
  'Document',
  'DocumentFragment',
  'getComputedStyle',
  'MutationObserver',
  'DOMParser',
  'Range',
]) {
  if (dom.window[name] !== undefined) {
    globalThis[name] = dom.window[name]
  }
}

// jsdom does not implement HTMLElement.innerText (it depends on layout). Quill's
// code-block serializer reads child.domNode.innerText to recover each source line
// (formats/code.js), so without this shim the code-block fixture loses its text. In a
// real browser innerText === the rendered text; for the flat single-line-per-child
// structure Quill builds inside <pre>, textContent is that exact string. This shim makes
// jsdom's getSemanticHTML() match real-browser output for the code-block fixture.
Object.defineProperty(dom.window.HTMLElement.prototype, 'innerText', {
  configurable: true,
  get() {
    return this.textContent
  },
})

const { default: Quill } = await import('quill')

// The enabled-format allowlist — MUST stay in lockstep with the Editor component's
// `formats` prop (DocumentEdit.vue). Each entry produces one fixture file whose content
// is the getSemanticHTML() serialization of a Delta exercising that format.
const cases = {
  heading: [
    { insert: 'Heading one' },
    { insert: '\n', attributes: { header: 1 } },
    { insert: 'Heading two' },
    { insert: '\n', attributes: { header: 2 } },
    { insert: 'Heading three' },
    { insert: '\n', attributes: { header: 3 } },
  ],
  bold: [{ insert: 'bold text', attributes: { bold: true } }, { insert: '\n' }],
  italic: [{ insert: 'italic text', attributes: { italic: true } }, { insert: '\n' }],
  underline: [{ insert: 'underlined', attributes: { underline: true } }, { insert: '\n' }],
  strike: [{ insert: 'struck', attributes: { strike: true } }, { insert: '\n' }],
  'list-ordered': [
    { insert: 'first' },
    { insert: '\n', attributes: { list: 'ordered' } },
    { insert: 'second' },
    { insert: '\n', attributes: { list: 'ordered' } },
  ],
  'list-bullet': [
    { insert: 'alpha' },
    { insert: '\n', attributes: { list: 'bullet' } },
    { insert: 'beta' },
    { insert: '\n', attributes: { list: 'bullet' } },
  ],
  link: [
    {
      insert: 'Teedy site',
      attributes: { link: 'https://teedy.example.com/path' },
    },
    { insert: '\n' },
  ],
  blockquote: [
    { insert: 'a quoted line' },
    { insert: '\n', attributes: { blockquote: true } },
  ],
  'code-block': [
    { insert: 'const x = 1;' },
    { insert: '\n', attributes: { 'code-block': true } },
    { insert: 'return x < 2 && x > 0;' },
    { insert: '\n', attributes: { 'code-block': true } },
  ],
  paragraph: [{ insert: 'a plain paragraph\nsecond paragraph\n' }],
}

mkdirSync(fixtureDir, { recursive: true })

for (const [name, ops] of Object.entries(cases)) {
  const container = document.createElement('div')
  document.body.appendChild(container)
  const quill = new Quill(container)
  quill.setContents(ops)
  const html = quill.getSemanticHTML()
  writeFileSync(join(fixtureDir, `${name}.html`), html + '\n', 'utf8')
  container.remove()
  console.log(`${name} -> ${html}`)
}

console.log(`\nWrote ${Object.keys(cases).length} fixtures to ${fixtureDir}`)
