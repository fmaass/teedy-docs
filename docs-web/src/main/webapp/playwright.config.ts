import { defineConfig, devices } from '@playwright/test'

// End-to-end tests drive a REAL running Teedy instance (the production Docker
// image on port 8080, context path "/") via its native form login — NOT Authelia
// (Authelia only fronts production). scripts/e2e-run.sh boots the container and
// waits for /api/user before invoking `npx playwright test`.
const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:8080'

export default defineConfig({
  testDir: './e2e',
  // A storageState produced by global-setup logs in as admin/admin once; specs
  // that need the login form itself opt out via `test.use({ storageState: {…} })`.
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL,
    trace: 'on-first-retry',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/admin.json',
      },
    },
  ],
})
