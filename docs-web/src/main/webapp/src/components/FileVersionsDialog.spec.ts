import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { VueQueryPlugin, QueryClient } from '@tanstack/vue-query'
import Skeleton from 'primevue/skeleton'
import { DocumentKey } from '../views/document/documentKey'

// #32: the version-history load state must render CONTENT-SHAPED skeleton rows, not
// a spinner. We hold the load open (a never-resolving getFileVersions) so
// the dialog stays in its `loading` branch, then assert the row skeletons render.
//
// The API is a dependency, not the unit under test — it is mocked. The unit under
// test is the dialog's loading-state TEMPLATE.

const getFileVersionsMock = vi.fn()
vi.mock('../api/file', () => ({
  getFileVersions: (...args: unknown[]) => getFileVersionsMock(...args),
  getFileUrl: (id: string) => `/api/file/${id}/data`,
  // uploadFile is pulled in transitively by the version-upload composable; it is
  // never invoked in the loading-state test, but must exist as an export.
  uploadFile: vi.fn(),
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))

import FileVersionsDialog from './FileVersionsDialog.vue'

function mountDialog() {
  return mount(FileVersionsDialog, {
    props: { fileId: 'file-1', visible: true },
    global: {
      // The version-upload composable (footer action) resolves toast, query client
      // and the injected document at setup, so provide all three even though the
      // loading-state branch under test never renders the upload button.
      plugins: [PrimeVue, ToastService, [VueQueryPlugin, { queryClient: new QueryClient() }]],
      provide: { [DocumentKey as symbol]: ref({ id: 'doc-1' }) },
      stubs: {
        // Dialog teleports to <body>; render its default slot inline so the test
        // can query the loading region without chasing the teleport target.
        Dialog: { props: ['visible'], template: '<div class="dlg"><slot /><slot name="footer" /></div>' },
        DataTable: true,
        Column: true,
        Button: true,
        EmptyState: true,
        ErrorState: true,
      },
      directives: { tooltip: {} },
    },
  })
}

describe('FileVersionsDialog — loading state (#32)', () => {
  beforeEach(() => {
    getFileVersionsMock.mockReset()
    // Never resolves: the dialog stays in the `loading` branch.
    getFileVersionsMock.mockReturnValue(new Promise(() => {}))
  })

  it('renders several content-shaped skeleton rows while loading (no spinner)', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    const region = wrapper.find('.versions-loading')
    expect(region.exists()).toBe(true)
    // Row-shaped: MORE THAN ONE skeleton (a placeholder list, not a single blob).
    const skeletons = wrapper.findAllComponents(Skeleton)
    expect(skeletons.length).toBeGreaterThan(1)
    // Accessible status label for the loading region.
    expect(region.attributes('role')).toBe('status')
  })
})
