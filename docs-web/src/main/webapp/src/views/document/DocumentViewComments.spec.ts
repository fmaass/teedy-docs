import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ConfirmationService from 'primevue/confirmationservice'
import ToastService from 'primevue/toastservice'
import en from '../../locale/en.json'
import { DocumentKey } from './documentKey'
import type { DocumentDetail } from '../../api/document'
import type { Comment } from '../../api/comment'

// #285 slice 1 — editing your own comment, with an "edited" marker everyone can see.
//
// The comment API module and the auth store are DEPENDENCIES (mocked). The unit under test is the
// view's own logic: who is offered the edit affordance, what the marker renders from, and what the
// Save/Cancel controls do with the draft.

const listCommentsMock = vi.fn()
const updateCommentMock = vi.fn()
const addCommentMock = vi.fn()
const deleteCommentMock = vi.fn()

vi.mock('../../api/comment', () => ({
  listComments: (...a: unknown[]) => listCommentsMock(...a),
  addComment: (...a: unknown[]) => addCommentMock(...a),
  updateComment: (...a: unknown[]) => updateCommentMock(...a),
  deleteComment: (...a: unknown[]) => deleteCommentMock(...a),
  gravatarUrl: (hash: string) => `https://avatar/${hash}`,
}))

// The view asks the auth store who is signed in to decide whose comments are editable.
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ username: 'alice' }),
}))

vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({ confirmDanger: vi.fn() }),
}))

import DocumentViewComments from './DocumentViewComments.vue'

// Two comments on the same document: one written by the signed-in user (alice) and already edited,
// one written by somebody else and never edited.
const CREATED_2020 = new Date('2020-03-04T10:00:00Z').getTime()
const EDITED_2024 = new Date('2024-09-08T17:30:00Z').getTime()

function ownComment(overrides: Partial<Comment> = {}): Comment {
  return {
    id: 'c-own',
    content: 'My own words',
    creator: 'alice',
    creator_gravatar: 'ha',
    create_date: CREATED_2020,
    ...overrides,
  }
}

function foreignComment(overrides: Partial<Comment> = {}): Comment {
  return {
    id: 'c-foreign',
    content: 'Somebody else wrote this',
    creator: 'bob',
    creator_gravatar: 'hb',
    create_date: CREATED_2020,
    ...overrides,
  }
}

beforeAll(() => {
  if (typeof globalThis.ResizeObserver !== 'function') {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
})

beforeEach(() => {
  listCommentsMock.mockReset().mockResolvedValue({ data: { comments: [] } })
  updateCommentMock.mockReset().mockResolvedValue({ data: { status: 'ok' } })
  addCommentMock.mockReset().mockResolvedValue({ data: {} })
  deleteCommentMock.mockReset().mockResolvedValue({ data: { status: 'ok' } })
})

function mountComments() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const doc = ref({ id: 'doc-1', writable: true } as unknown as DocumentDetail)
  return mount(DocumentViewComments, {
    global: {
      plugins: [i18n, PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
      provide: { [DocumentKey as symbol]: doc },
    },
  })
}

const item = (wrapper: ReturnType<typeof mountComments>, id: string) =>
  wrapper.findAll('.comment-item')[id === 'c-own' ? 0 : 1]

describe('DocumentViewComments — the edit affordance (#285)', () => {
  it('offers the edit button on your own comment and NOT on somebody else’s', async () => {
    listCommentsMock.mockResolvedValue({ data: { comments: [ownComment(), foreignComment()] } })
    const wrapper = mountComments()
    await flushPromises()

    expect(wrapper.findAll('.comment-item').length).toBe(2)
    expect(item(wrapper, 'c-own').find('.comment-edit').exists()).toBe(true)
    expect(item(wrapper, 'c-foreign').find('.comment-edit').exists()).toBe(false)
  })

  it('labels the edit button for screen readers', async () => {
    listCommentsMock.mockResolvedValue({ data: { comments: [ownComment()] } })
    const wrapper = mountComments()
    await flushPromises()

    expect(item(wrapper, 'c-own').find('.comment-edit').attributes('aria-label')).toBe('Edit comment')
  })

  it('opens an editable draft seeded with the current content, and closes it again on cancel', async () => {
    listCommentsMock.mockResolvedValue({ data: { comments: [ownComment()] } })
    const wrapper = mountComments()
    await flushPromises()

    expect(wrapper.find('.comment-edit-input').exists()).toBe(false)
    await item(wrapper, 'c-own').find('.comment-edit').trigger('click')
    await flushPromises()

    const input = wrapper.find('.comment-edit-input')
    expect(input.exists()).toBe(true)
    expect((input.element as HTMLTextAreaElement).value).toBe('My own words')
    // The static content paragraph gives way to the editor while editing.
    expect(wrapper.find('.comment-content').exists()).toBe(false)

    await wrapper.find('.comment-edit-cancel').trigger('click')
    await flushPromises()
    expect(wrapper.find('.comment-edit-input').exists()).toBe(false)
    expect(wrapper.find('.comment-content').text()).toBe('My own words')
    expect(updateCommentMock).not.toHaveBeenCalled()
  })

  it('saving sends the edited content for that comment and re-reads the list', async () => {
    listCommentsMock.mockResolvedValue({ data: { comments: [ownComment()] } })
    const wrapper = mountComments()
    await flushPromises()
    expect(listCommentsMock).toHaveBeenCalledTimes(1)

    await item(wrapper, 'c-own').find('.comment-edit').trigger('click')
    await flushPromises()
    await wrapper.find('.comment-edit-input').setValue('My own words, corrected')
    await wrapper.find('.comment-edit-save').trigger('click')
    await flushPromises()

    expect(updateCommentMock).toHaveBeenCalledTimes(1)
    expect(updateCommentMock).toHaveBeenCalledWith('c-own', 'My own words, corrected')
    // The list is re-read so the server's edit stamp reaches the view.
    expect(listCommentsMock.mock.calls.length).toBeGreaterThan(1)
    // The editor is closed again.
    expect(wrapper.find('.comment-edit-input').exists()).toBe(false)
  })

  it('keeps the editor open and sends nothing when the draft is blank', async () => {
    listCommentsMock.mockResolvedValue({ data: { comments: [ownComment()] } })
    const wrapper = mountComments()
    await flushPromises()

    await item(wrapper, 'c-own').find('.comment-edit').trigger('click')
    await flushPromises()
    await wrapper.find('.comment-edit-input').setValue('   ')
    await wrapper.find('.comment-edit-save').trigger('click')
    await flushPromises()

    expect(updateCommentMock).not.toHaveBeenCalled()
    expect(wrapper.find('.comment-edit-input').exists()).toBe(true)
  })
})

describe('DocumentViewComments — the "edited" marker (#285)', () => {
  it('marks an edited comment for EVERY reader, including on comments they cannot edit', async () => {
    listCommentsMock.mockResolvedValue({
      data: { comments: [ownComment(), foreignComment({ update_date: EDITED_2024 })] },
    })
    const wrapper = mountComments()
    await flushPromises()

    // The foreign comment was edited: the marker is shown even though this reader may not edit it.
    const foreign = item(wrapper, 'c-foreign')
    expect(foreign.find('.comment-edited').exists()).toBe(true)
    expect(foreign.find('.comment-edited').text()).toBe('edited')
    expect(foreign.find('.comment-edit').exists()).toBe(false)

    // The never-edited comment carries no marker.
    expect(item(wrapper, 'c-own').find('.comment-edited').exists()).toBe(false)
  })

  it('exposes the EDIT timestamp — not the creation timestamp — in the marker tooltip', async () => {
    listCommentsMock.mockResolvedValue({
      data: { comments: [ownComment({ update_date: EDITED_2024 })] },
    })
    const wrapper = mountComments()
    await flushPromises()

    const title = item(wrapper, 'c-own').find('.comment-edited').attributes('title') ?? ''
    expect(title).toContain('2024')
    expect(title).not.toContain('2020')
    // The comment's own date line still shows when it was written.
    expect(item(wrapper, 'c-own').find('.comment-date').text()).toContain('2020')
  })
})
