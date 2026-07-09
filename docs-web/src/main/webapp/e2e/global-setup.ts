import { chromium, request, type FullConfig } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

// Logs in once as the default admin (admin/admin) through the real native login
// form and persists the authenticated storageState so every spec starts logged
// in. Specs that exercise the login form itself override storageState to empty.
//
// The first-run default-password banner (DefaultPasswordBanner.vue) is
// non-dismissible and non-blocking — it renders in the content flow and does not
// gate interaction — so no onboarding click-through is required here.
const STORAGE_STATE = 'e2e/.auth/admin.json'
const ADMIN_USER = 'admin'
const ADMIN_PASS = 'admin'

async function globalSetup(config: FullConfig) {
  const baseURL =
    config.projects[0]?.use?.baseURL ??
    process.env.PLAYWRIGHT_BASE_URL ??
    'http://localhost:8080'

  // Fail fast with a clear message if the app is not actually up — the harness
  // (scripts/e2e-run.sh / CI) is responsible for booting and readiness-polling.
  const probe = await request.newContext({ baseURL })
  const res = await probe.get('/api/user')
  if (!res.ok()) {
    await probe.dispose()
    throw new Error(
      `Teedy not reachable at ${baseURL}/api/user (HTTP ${res.status()}). ` +
        `Boot the app first — see scripts/e2e-run.sh.`,
    )
  }
  await probe.dispose()

  mkdirSync(dirname(STORAGE_STATE), { recursive: true })

  const browser = await chromium.launch()
  const page = await browser.newPage({ baseURL })
  try {
    await page.goto('/#/login')
    await page.getByLabel('Username').fill(ADMIN_USER)
    // The PrimeVue Password renders an <input> labelled by #login-pass.
    await page.locator('#login-pass').fill(ADMIN_PASS)
    await page.getByRole('button', { name: 'Sign in' }).click()

    // Land on the documents list: the left-panel "teedy" brand link only mounts
    // once authenticated (AppLayout gates on !auth.isAnonymous).
    await page.waitForURL('**/#/document')
    await page.getByRole('link', { name: 'teedy' }).first().waitFor()

    await page.context().storageState({ path: STORAGE_STATE })
  } finally {
    await browser.close()
  }
}

export default globalSetup
