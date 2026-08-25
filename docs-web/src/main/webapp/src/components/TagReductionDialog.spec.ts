import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import en from '../locale/en.json'
import type { TagReductionReport } from '../api/tag'

// #293 — the tag-reduction run over the document-list selection.
//
// The reporter's condition on the whole feature was "some preview/dry-run would be good, to not
// destroy". These tests pin that order end to end: opening previews and cannot remove anything,
// the removal only happens on the explicit confirm, and both calls carry document IDs alone — the
// server re-derives what is redundant, so a preview this screen holds can never be replayed as a
// removal list.

const tagApiMock = vi.hoisted(() => ({ reduceDocumentTags: vi.fn() }))
vi.mock('../api/tag', () => tagApiMock)

import TagReductionDialog from './TagReductionDialog.vue'

const DOCUMENTS = [
  { id: 'doc-1', title: 'ACME insurance 2026' },
  { id: 'doc-2', title: 'Car service invoice' },
  { id: 'doc-3', title: 'Nothing redundant here' },
]

const PREVIEW: TagReductionReport = {
  status: 'ok',
  dryRun: true,
  count: 3,
  documents: [
    {
      id: 'doc-1',
      tags: [
        { id: 'insurance', name: 'Insurance', path: 'Insurance' },
        { id: 'car', name: 'Car', path: 'Insurance / Car' },
      ],
    },
    { id: 'doc-2', tags: [{ id: 'insurance', name: 'Insurance', path: 'Insurance' }] },
  ],
  skipped: [],
}

const EXECUTED: TagReductionReport = { ...PREVIEW, dryRun: false }

function mountDialog(documents = DOCUMENTS) {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  return mount(TagReductionDialog, {
    attachTo: document.body,
    props: { documents },
    global: { plugins: [i18n, PrimeVue] },
  })
}

function text(selector: string): string {
  return (document.body.querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim()
}

function confirmButton(): HTMLButtonElement | null {
  return document.body.querySelector('.reduction-confirm-btn') as HTMLButtonElement | null
}

/** The rendered rows, as "title: path, path". */
function rows(): string[] {
  return Array.from(document.body.querySelectorAll('.reduction-doc')).map((row) => {
    const title = row.querySelector('.reduction-doc-title')?.textContent ?? ''
    const tags = Array.from(row.querySelectorAll('.reduction-tag')).map((tag) => tag.textContent ?? '')
    return `${title}: ${tags.join(', ')}`
  })
}

describe('TagReductionDialog — preview first, then the run (#293)', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    tagApiMock.reduceDocumentTags.mockReset().mockResolvedValue({ data: PREVIEW })
  })

  it('previews with a dry run on open, and removes nothing', async () => {
    mountDialog()
    await flushPromises()

    expect(tagApiMock.reduceDocumentTags).toHaveBeenCalledTimes(1)
    expect(tagApiMock.reduceDocumentTags).toHaveBeenCalledWith(['doc-1', 'doc-2', 'doc-3'], true)
  })

  it('spells out that redundancy is transitive before anything is confirmed', async () => {
    mountDialog()
    await flushPromises()

    // The nesting rule is the part nobody can infer from a button label: a document tagged
    // Insurance / Car / 2026 in full keeps only 2026.
    expect(text('.reduction-intro')).toBe(en.ui.tag_reduction.intro)
  })

  it('names each document by its own title with the tags that would go', async () => {
    mountDialog()
    await flushPromises()

    expect(rows()).toEqual([
      'ACME insurance 2026: Insurance, Insurance / Car',
      'Car service invoice: Insurance',
    ])
    // A document with nothing redundant on it is not listed at all.
    expect(rows().join(' ')).not.toContain('Nothing redundant here')
  })

  it('runs for real only on the confirm, sending document IDs alone', async () => {
    mountDialog()
    await flushPromises()
    expect(tagApiMock.reduceDocumentTags).toHaveBeenCalledTimes(1)

    tagApiMock.reduceDocumentTags.mockResolvedValueOnce({ data: EXECUTED })
    confirmButton()!.click()
    await flushPromises()

    expect(tagApiMock.reduceDocumentTags).toHaveBeenCalledTimes(2)
    expect(tagApiMock.reduceDocumentTags).toHaveBeenLastCalledWith(['doc-1', 'doc-2', 'doc-3'], false)
    // Only IDs and the flag: no removal list travels from this screen to the server.
    expect(tagApiMock.reduceDocumentTags.mock.calls[1]).toHaveLength(2)
  })

  it('reports what went, stops offering the run and tells the list to refresh', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    tagApiMock.reduceDocumentTags.mockResolvedValueOnce({ data: EXECUTED })
    confirmButton()!.click()
    await flushPromises()

    expect(text('.reduction-result')).toContain('3')
    expect(text('.reduction-result')).toContain('2')
    expect(confirmButton(), 'a completed run cannot be run again from the report').toBeNull()
    expect(wrapper.emitted('reduced')?.[0]?.[0]).toEqual(EXECUTED)
  })

  it('says there is nothing to reduce, and refuses the run, when the preview is empty', async () => {
    tagApiMock.reduceDocumentTags.mockResolvedValue({
      data: { status: 'ok', dryRun: true, count: 0, documents: [], skipped: [] },
    })
    mountDialog()
    await flushPromises()

    expect(text('.reduction-none')).toBe(en.ui.tag_reduction.none)
    expect(confirmButton()!.disabled).toBe(true)
  })

  it('counts the documents it could not touch without saying which reason applies', async () => {
    tagApiMock.reduceDocumentTags.mockResolvedValue({
      data: { ...PREVIEW, skipped: ['doc-3'] },
    })
    mountDialog()
    await flushPromises()

    const skipped = text('.reduction-skipped')
    expect(skipped).toContain('1')
    // The server deliberately does not distinguish "you cannot write it" from "it is gone" —
    // that would be an existence oracle — so the screen must not name one of them either.
    expect(skipped).toBe(
      en.ui.tag_reduction.skipped.replace('{count}', '1'),
    )
  })

  it('surfaces a failed preview instead of reading as "nothing was redundant"', async () => {
    tagApiMock.reduceDocumentTags.mockRejectedValue(new Error('boom'))
    mountDialog()
    await flushPromises()

    expect(text('.reduction-error')).toBe(en.ui.tag_reduction.failed)
    expect(document.body.querySelector('.reduction-none'), 'an error is not an empty result').toBeNull()
    expect(confirmButton()!.disabled).toBe(true)
  })
})
