// jsdom under this Node/Vitest combination does not expose a working
// `localStorage`, and Node's own experimental bare `localStorage` global is
// undefined. Application code (src/stores/tagFilter.ts) reads `localStorage`
// directly, so install a minimal in-memory Web Storage polyfill on both the
// global and window for the test environment only.
class MemoryStorage implements Storage {
  private store = new Map<string, string>()
  get length() {
    return this.store.size
  }
  clear() {
    this.store.clear()
  }
  getItem(key: string) {
    return this.store.has(key) ? this.store.get(key)! : null
  }
  key(index: number) {
    return Array.from(this.store.keys())[index] ?? null
  }
  removeItem(key: string) {
    this.store.delete(key)
  }
  setItem(key: string, value: string) {
    this.store.set(key, String(value))
  }
}

function installStorage(target: typeof globalThis | Window) {
  const descriptor = Object.getOwnPropertyDescriptor(target, 'localStorage')
  if (!descriptor?.value) {
    Object.defineProperty(target, 'localStorage', {
      configurable: true,
      value: new MemoryStorage(),
    })
  }
}

installStorage(globalThis)
if (typeof window !== 'undefined') installStorage(window)

// PrimeVue's `v-tooltip` directive is registered globally in src/main.ts, but component
// tests mount in isolation without the full app plugin. Register a no-op tooltip stub on
// the Vue Test Utils global config so components using `v-tooltip` don't emit
// "Failed to resolve directive: tooltip" warnings. A no-op is sufficient: no test asserts
// tooltip behaviour, and specs that need the real directive still override it locally.
import { config, enableAutoUnmount } from '@vue/test-utils'
import { afterEach } from 'vitest'

config.global.directives = {
  ...config.global.directives,
  tooltip: {},
}

// Unmount every mounted wrapper after each test, inside the live jsdom environment. This lets
// each component's beforeUnmount hooks run (and Vue null its template refs) BEFORE the environment
// is torn down. Without it, specs that mount but never unmount (e.g. DocumentSlideOver.spec.ts,
// which mounts the PrimeVue Tabs stack) leave the component alive at teardown; PrimeVue's
// TabList schedules an ink-bar `setTimeout(updateInkBar, 150)` at mount and does NOT clear it on
// unmount, so on a slow/rescheduled CI run that timer can fire AFTER teardown and reach
// `getOuterWidth(activeTab)` -> `ReferenceError: HTMLElement is not defined` (an unhandled error
// that fails the run even with every test green). Unmounting first nulls TabList's `inkbar` ref,
// so the still-pending callback bails at its `if (!inkbar) return;` guard and never touches the
// missing global. (Verified: TabList.vue beforeUnmount only unbinds its ResizeObserver.)
enableAutoUnmount(afterEach)
