import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

const toastAdd = vi.hoisted(() => vi.fn())
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: toastAdd }) }))

// Capture the API calls; addFavorite is switchable between resolve/reject per test.
const addMock = vi.hoisted(() => vi.fn((_id: string) => Promise.resolve({ data: { status: 'ok' } })))
const removeMock = vi.hoisted(() => vi.fn((_id: string) => Promise.resolve({ data: { status: 'ok' } })))
vi.mock('../api/favorite', () => ({
  addFavorite: (id: string) => addMock(id),
  removeFavorite: (id: string) => removeMock(id),
}))

// Record invalidateQueries calls so a test can assert BOTH the list key and this
// document's detail key are invalidated after a toggle.
const invalidateMock = vi.hoisted(() => vi.fn())
vi.mock('@tanstack/vue-query', () => ({
  useQueryClient: () => ({ invalidateQueries: invalidateMock }),
}))

// Stub PrimeVue Button as a real <button> that forwards the class + click, so the
// optimistic icon flip (bound via :class/:icon) and stopPropagation are observable.
vi.mock('primevue/button', () => ({
  default: defineComponent({
    props: ['icon', 'ariaPressed'],
    emits: ['click'],
    setup(props, { attrs, emit }) {
      return () =>
        h('button', {
          class: attrs.class,
          'data-icon': props.icon,
          'aria-pressed': props.ariaPressed,
          onClick: (e: Event) => emit('click', e),
        })
    },
  }),
}))

import FavoriteStar from './FavoriteStar.vue'
import { queryKeys } from '../api/queryKeys'

function mountStar(favorite: boolean) {
  return mount(FavoriteStar, {
    props: { documentId: 'doc-1', favorite },
    global: { directives: { tooltip: {} } },
  })
}

describe('FavoriteStar', () => {
  beforeEach(() => {
    addMock.mockClear()
    removeMock.mockClear()
    invalidateMock.mockClear()
    toastAdd.mockClear()
    addMock.mockImplementation((_id: string) => Promise.resolve({ data: { status: 'ok' } }))
    removeMock.mockImplementation((_id: string) => Promise.resolve({ data: { status: 'ok' } }))
  })

  it('optimistically flips OFF→ON on click before the request resolves', async () => {
    const wrapper = mountStar(false)
    expect(wrapper.get('button').attributes('data-icon')).toBe('pi pi-star')
    // Click; the optimistic flip is synchronous (before awaiting the mock).
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('button').attributes('data-icon')).toBe('pi pi-star-fill')
    expect(addMock).toHaveBeenCalledWith('doc-1')
  })

  it('invalidates BOTH the document list AND this document detail key on a successful toggle', async () => {
    const wrapper = mountStar(false)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    const invalidatedKeys = invalidateMock.mock.calls.map((c) => JSON.stringify(c[0].queryKey))
    expect(invalidatedKeys).toContain(JSON.stringify(queryKeys.documents()))
    expect(invalidatedKeys).toContain(JSON.stringify(queryKeys.document('doc-1')))
  })

  it('ON→OFF click calls removeFavorite and flips the icon off', async () => {
    const wrapper = mountStar(true)
    expect(wrapper.get('button').attributes('data-icon')).toBe('pi pi-star-fill')
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('button').attributes('data-icon')).toBe('pi pi-star')
    expect(removeMock).toHaveBeenCalledWith('doc-1')
  })

  it('rolls back the optimistic flip and toasts on error', async () => {
    addMock.mockImplementationOnce(() => Promise.reject(new Error('boom')))
    const wrapper = mountStar(false)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    // After the rejection the optimistic flip is rolled back to OFF, with an error toast.
    expect(wrapper.get('button').attributes('data-icon')).toBe('pi pi-star')
    expect(toastAdd).toHaveBeenCalled()
    // A failed toggle must NOT invalidate (nothing changed server-side).
    expect(invalidateMock).not.toHaveBeenCalled()
  })

  it('stops the click from propagating to a surrounding row handler', async () => {
    const rowClick = vi.fn()
    const wrapper = mount(
      defineComponent({
        components: { FavoriteStar },
        setup() {
          return () => h('div', { onClick: rowClick }, [h(FavoriteStar, { documentId: 'doc-1', favorite: false })])
        },
      }),
      { global: { directives: { tooltip: {} } } },
    )
    await wrapper.get('button').trigger('click')
    expect(rowClick).not.toHaveBeenCalled()
  })
})
