import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// Vitest config is kept separate from vite.config.ts so the production build
// (vue-tsc + vite build) never pulls in test-only tooling or jsdom.
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    // `e2e/**/*.check.ts` are PURE unit checks over e2e helper logic (no browser, no
    // server): Playwright collects `*.spec.ts`/`*.test.ts` only, so a `.check.ts` file
    // belongs to vitest alone and cannot be run twice or block on a running app.
    include: ['src/**/*.spec.ts', 'e2e/**/*.check.ts'],
  },
})
