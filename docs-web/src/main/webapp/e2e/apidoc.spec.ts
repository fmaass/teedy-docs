import { test, expect, type ConsoleMessage, type Request } from './fixtures'

// /apidoc/ serves the vendored Swagger UI (swagger-ui-dist) + the build-time
// static OpenAPI spec (issue #15). It is a REAL path (not the SPA hash router),
// served anonymously as static assets — no session cookie required — and MUST
// make no external/CDN requests (strict no-external-request posture).
//
// These tests opt out of the project-wide admin storageState to prove anonymous
// access, and drive Swagger UI's own "Try it out" against an anonymous API
// endpoint (GET /app) to prove the relative `servers` URL resolves to the live
// /api base.
test.describe('/apidoc Swagger UI', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('loads Swagger UI anonymously with no external requests or console errors', async ({
    page,
    baseURL,
  }) => {
    const consoleErrors: string[] = []
    page.on('console', (msg: ConsoleMessage) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })
    page.on('pageerror', (err) => consoleErrors.push(err.message))

    // Any request whose origin is not our own baseURL is an external/CDN request.
    const ownOrigin = new URL(baseURL ?? 'http://localhost:8080').origin
    const externalRequests: string[] = []
    page.on('request', (req: Request) => {
      const url = req.url()
      if (url.startsWith('data:') || url.startsWith('blob:')) return
      if (!url.startsWith(ownOrigin)) externalRequests.push(url)
    })

    await page.goto('/apidoc/')

    // Swagger UI has rendered the spec: the API title from openapi.json info.title.
    await expect(page.getByRole('heading', { name: 'Teedy API' })).toBeVisible()

    // The spec itself was served from our own origin.
    const specResp = await page.request.get('/apidoc/openapi.json')
    expect(specResp.ok()).toBeTruthy()
    const spec = await specResp.json()
    expect(spec.openapi).toMatch(/^3\.0/)
    expect(spec.servers[0].url).toBe('../api')

    expect(
      externalRequests,
      `external requests: ${externalRequests.join(' | ')}`,
    ).toEqual([])
    expect(
      consoleErrors,
      `console errors: ${consoleErrors.join(' | ')}`,
    ).toEqual([])
  })

  test('an operation expands and "Try it out" against GET /app hits the live /api base', async ({
    page,
  }) => {
    await page.goto('/apidoc/')
    await expect(page.getByRole('heading', { name: 'Teedy API' })).toBeVisible()

    // Expand the "app" tag group, then the GET /app operation.
    const getApp = page.locator('#operations-app-get_app')
    await getApp.scrollIntoViewIfNeeded()
    await getApp.locator('.opblock-summary').click()
    await expect(getApp.locator('.opblock-body')).toBeVisible()

    // Capture the request Swagger UI issues so we can assert it targets /api/app
    // (the relative ../api server resolved against /apidoc/openapi.json).
    const [apiRequest] = await Promise.all([
      page.waitForRequest((req) => /\/api\/app(\?|$)/.test(req.url())),
      (async () => {
        await getApp.getByRole('button', { name: 'Try it out' }).click()
        await getApp.getByRole('button', { name: 'Execute' }).click()
      })(),
    ])
    expect(apiRequest.url()).toMatch(/\/api\/app(\?|$)/)

    // The live server answered (GET /app is anonymous) — a 200 with the version.
    await expect(getApp.locator('.responses-inner .response .response-col_status').first()).toContainText(
      '200',
    )
  })
})
