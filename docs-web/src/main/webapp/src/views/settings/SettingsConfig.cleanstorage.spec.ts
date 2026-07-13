import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import en from '../../locale/en.json'

// Mock the app api: the clean-storage flow under test must fetch the dry-run preview BEFORE it
// ever calls the real cleanStorage, and only run the real cleanup when the confirm is accepted.
const apiMock = vi.hoisted(() => ({
  cleanStorage: vi.fn(),
  cleanStorageDryRun: vi.fn(),
}))
vi.mock('../../api/app', async (orig) => ({ ...(await orig()), ...apiMock }))

// Capture confirmDanger's options so the test can drive the accept path deterministically.
const confirmMock = vi.hoisted(() => ({ lastOptions: null as unknown, confirmDanger: vi.fn() }))
vi.mock('../../composables/useConfirmDanger', () => ({
  useConfirmDanger: () => ({
    confirmDanger: (opts: unknown) => {
      confirmMock.lastOptions = opts
      confirmMock.confirmDanger(opts)
    },
  }),
}))

// Stub app-info so the view mounts without a real /app fetch. `data` must be a real ref so the
// view's watch(appConfig, ...) accepts it as a watch source.
vi.mock('../../composables/useAppInfo', async () => {
  const { ref } = await import('vue')
  return {
    useAppInfo: () => ({ data: ref({}) }),
    useInvalidateAppInfo: () => vi.fn(),
  }
})

// The SMTP config query would otherwise hit the network; stub the api it uses.
vi.mock('../../api/client', () => ({ default: { get: vi.fn().mockResolvedValue({ data: {} }), post: vi.fn() } }))

beforeAll(() => {
  if (typeof window.matchMedia !== 'function') {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: false, media: query, onchange: null,
        addEventListener: () => {}, removeEventListener: () => {},
        addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
      }),
    })
  }
})

import SettingsConfig from './SettingsConfig.vue'

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(SettingsConfig, {
    global: {
      plugins: [i18n, PrimeVue, ToastService, ConfirmationService, [VueQueryPlugin, { queryClient }]],
    },
  })
}

async function clickCleanup(wrapper: ReturnType<typeof mountView>) {
  const cleanupBtn = wrapper
    .findAll('button')
    .find((b) => b.text().includes('Clean storage'))
  expect(cleanupBtn, 'the Clean storage button must render').toBeTruthy()
  await cleanupBtn!.trigger('click')
  await flushPromises()
}

describe('SettingsConfig clean-storage dry-run confirm (#60)', () => {
  beforeEach(() => {
    apiMock.cleanStorage.mockReset().mockResolvedValue({ status: 'ok', file_count: 2, bytes: 8192 })
    apiMock.cleanStorageDryRun.mockReset().mockResolvedValue({
      total: 2, reclaimed_bytes: 8192, primary_pointer_cleared_count: 1, limit: 100, offset: 0, files: [],
    })
    confirmMock.confirmDanger.mockReset()
    confirmMock.lastOptions = null
  })

  it('fetches the dry-run preview BEFORE running the real cleanup, and shows the count/size summary', async () => {
    const wrapper = mountView()
    await flushPromises()

    await clickCleanup(wrapper)

    // The preview was fetched; the real cleanup has NOT run yet (still awaiting confirm).
    expect(apiMock.cleanStorageDryRun).toHaveBeenCalledTimes(1)
    expect(apiMock.cleanStorage).not.toHaveBeenCalled()

    // The confirm message states the file count and reclaimable size from the dry-run.
    const opts = confirmMock.lastOptions as { message: string; accept: () => Promise<void> }
    expect(opts).toBeTruthy()
    expect(opts.message).toContain('2 file')
    expect(opts.message).toContain('8')
  })

  it('runs the real cleanup only when the confirm is accepted', async () => {
    const wrapper = mountView()
    await flushPromises()
    await clickCleanup(wrapper)

    const opts = confirmMock.lastOptions as { accept: () => Promise<void> }
    await opts.accept()
    await flushPromises()

    expect(apiMock.cleanStorage).toHaveBeenCalledTimes(1)
  })

  it('does NOT fetch the dry-run twice and never runs cleanup if the preview fails', async () => {
    apiMock.cleanStorageDryRun.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()
    await clickCleanup(wrapper)

    // Preview failed → no confirm opened, no real cleanup.
    expect(apiMock.cleanStorage).not.toHaveBeenCalled()
    expect(confirmMock.confirmDanger).not.toHaveBeenCalled()
  })
})
