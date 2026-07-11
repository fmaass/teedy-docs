import { test, expect, type APIRequestContext } from '@playwright/test'

// Configurable footer/imprint links (issue #43). Two admin-configured links must
// render BOTH in the authenticated app shell AND on the logged-out login screen —
// the login-screen reachability is the EU-imprint requirement (GET /app is anonymous).
// Every link carries rel="noopener noreferrer" and target="_blank".
//
// The links are set/cleared through the admin API; this spec proves the RENDER
// surfaces end-to-end in a real browser (the AppLayout/Login render logic and the
// api/app helper are additionally unit-tested in *.spec.ts). The config is global,
// so the test always clears it again in teardown.

const IMPRINT = { label: 'E2E Imprint', url: 'https://example.com/e2e-imprint' }
const PRIVACY = { label: 'E2E Privacy', url: 'https://example.com/e2e-privacy' }

async function setFooterLinks(
  request: APIRequestContext,
  links: Array<{ label: string; url: string }>,
): Promise<void> {
  const res = await request.post('/api/app/footer_links', {
    form: { links: JSON.stringify(links) },
  })
  expect(res.ok()).toBeTruthy()
}

test.describe('configurable footer links', () => {
  test.afterEach(async ({ request }) => {
    // Global config — always clear so a later run/spec sees the default (no links).
    await setFooterLinks(request, [])
  })

  test('render in the app shell and on the logged-out login screen with safe rel', async ({
    page,
    request,
  }) => {
    await setFooterLinks(request, [IMPRINT, PRIVACY])

    // --- App shell (authenticated; storageState = admin) ---
    // Reload so the app-info query refetches the just-set links (refresh barrier).
    await page.goto('/#/document')
    await page.reload()

    const shellImprint = page.getByRole('link', { name: IMPRINT.label })
    const shellPrivacy = page.getByRole('link', { name: PRIVACY.label })
    await expect(shellImprint).toBeVisible()
    await expect(shellPrivacy).toBeVisible()
    await expect(shellImprint).toHaveAttribute('href', IMPRINT.url)
    await expect(shellImprint).toHaveAttribute('rel', 'noopener noreferrer')
    await expect(shellImprint).toHaveAttribute('target', '_blank')
    await expect(shellPrivacy).toHaveAttribute('href', PRIVACY.url)
    await expect(shellPrivacy).toHaveAttribute('rel', 'noopener noreferrer')

    // --- Logged-out login screen (fresh, unauthenticated context) ---
    const context = await page.context().browser()!.newContext({
      storageState: { cookies: [], origins: [] },
      baseURL: page.url().split('/#')[0],
    })
    const anon = await context.newPage()
    try {
      await anon.goto('/#/login?local=1')
      const loginImprint = anon.getByRole('link', { name: IMPRINT.label })
      const loginPrivacy = anon.getByRole('link', { name: PRIVACY.label })
      await expect(loginImprint).toBeVisible()
      await expect(loginPrivacy).toBeVisible()
      await expect(loginImprint).toHaveAttribute('href', IMPRINT.url)
      await expect(loginImprint).toHaveAttribute('rel', 'noopener noreferrer')
      await expect(loginImprint).toHaveAttribute('target', '_blank')
      await expect(loginPrivacy).toHaveAttribute('href', PRIVACY.url)
    } finally {
      await context.close()
    }
  })
})
