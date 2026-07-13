import { test, expect } from './fixtures'

// Guest (passwordless) login. The guest button + passwordless login path ARE real UI
// (Login.vue -> auth.login('guest', '')), but a fresh DB defaults GUEST_LOGIN=false
// (dbupdate-010), so the button is hidden until an admin enables it.
//
// This spec:
//   1. Enables guest login via the ADMIN API (POST /api/app/guest_login, form
//      `enabled=true`) using the admin storageState request context.
//   2. Drives the REAL guest button in a CLEAN cookie-less context and asserts a
//      genuine guest session results (user "guest", non-anonymous).
//   3. DISABLES guest login again and verifies both the config read-back
//      (/api/app guest_login:false) AND that the button is gone.
//
// SECURITY-CRITICAL cleanup ordering: the enable happens as the FIRST action INSIDE
// the try, and "disable guest login" is the FIRST action in finally with its own
// assertion — so any failure after enabling still restores GUEST_LOGIN=false, and a
// failed disable actually FAILS the test rather than silently leaving passwordless
// access on (which would poison later runs and the shared image).

test('guest login button works once an admin enables it', async ({ browser, request }) => {
  let guestContext: Awaited<ReturnType<typeof browser.newContext>> | null = null
  try {
    // --- 1. Admin enables guest login (admin cookie from the project storageState) ---
    const enableRes = await request.post('/api/app/guest_login', { form: { enabled: true } })
    expect(enableRes.ok(), 'admin enable of guest_login').toBeTruthy()

    // Confirm the app now advertises guest_login (drives the button's render).
    const appRes = await request.get('/api/app')
    expect((await appRes.json()).guest_login).toBe(true)

    // --- 2. Clean, cookie-less context: a truly logged-out visitor sees + uses it ---
    guestContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const guestPage = await guestContext.newPage()

    // Prove the context starts anonymous.
    const before = await guestContext.request.get('/api/user')
    expect((await before.json()).anonymous).toBe(true)

    await guestPage.goto('/#/login')
    const guestButton = guestPage.getByRole('button', { name: 'Login as guest' })
    await expect(guestButton).toBeVisible()
    await guestButton.click()

    // A successful guest login lands on the documents list and establishes a real,
    // NON-anonymous session named "guest".
    await expect(guestPage).toHaveURL(/#\/document$/)
    const me = await guestContext.request.get('/api/user')
    const body = await me.json()
    expect(body.anonymous).toBeFalsy()
    expect(body.username).toBe('guest')
  } finally {
    // --- 3. Teardown (security-critical, runs even if the above threw): disable guest
    //        login FIRST and verify the config actually flipped back off. ---
    const disableRes = await request.post('/api/app/guest_login', { form: { enabled: false } })
    expect(disableRes.ok(), 'admin disable of guest_login').toBeTruthy()
    const afterDisable = await request.get('/api/app')
    expect((await afterDisable.json()).guest_login, 'guest_login must be OFF after teardown').toBe(false)

    if (guestContext) await guestContext.close()

    // And the button is gone in a fresh clean context.
    const cleanupContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const cleanupPage = await cleanupContext.newPage()
    await cleanupPage.goto('/#/login')
    await expect(cleanupPage.getByRole('button', { name: 'Sign in' })).toBeVisible()
    await expect(cleanupPage.getByRole('button', { name: 'Login as guest' })).toHaveCount(0)
    await cleanupContext.close()
  }
})
