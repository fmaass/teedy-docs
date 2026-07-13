import { test, expect } from './fixtures'
import { unique, createDocument, confirmDanger } from './helpers'

// Share-by-URL: create a public link on a document, open it in a SEPARATE
// unauthenticated browser context (no storageState), confirm the doc is viewable
// read-only, then revoke and confirm the link stops working.

test('create a public share link, view it anonymously, then revoke it', async ({ page, browser }) => {
  const title = unique('share')
  const { id } = await createDocument(page, title, { description: 'Shared read-only content.' })

  // Open the Permissions tab and create a share link.
  await page.goto(`/#/document/view/${id}/permissions`)
  await expect(page.getByRole('heading', { name: 'Share links' })).toBeVisible()
  await expect(page.getByText('No active share links.')).toBeVisible()

  await page.getByPlaceholder('Link name (optional)').fill('e2e-link')
  await page.getByRole('button', { name: 'Create link' }).click()
  await expect(page.getByText('Share link created')).toBeVisible()

  // The created share row shows the public URL. Read it from the DOM.
  const shareUrl = await page.locator('.share-url').first().innerText()
  expect(shareUrl).toContain(`#/share/${id}/`)

  // --- Anonymous context: no storageState, so a truly logged-out visitor. ---
  const anonContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const anonPage = await anonContext.newPage()
  // Confirm this context is genuinely anonymous.
  const meRes = await anonContext.request.get('/api/user')
  expect((await meRes.json()).anonymous).toBe(true)

  await anonPage.goto(shareUrl.substring(shareUrl.indexOf('#')))
  await expect(anonPage.getByRole('heading', { name: title })).toBeVisible()
  await expect(anonPage.getByText('Shared via Teedy')).toBeVisible()

  // --- READ-ONLY property (explicit, not implicit) ---
  // 1. No write affordances an owner would see: the public ShareView renders none
  //    of the Edit / Delete / Add-tag / Upload controls present in the real doc
  //    view. Their absence is the visible read-only guarantee.
  await expect(anonPage.getByRole('button', { name: 'Edit' })).toHaveCount(0)
  await expect(anonPage.getByRole('button', { name: 'Delete' })).toHaveCount(0)
  await expect(anonPage.getByRole('button', { name: /Add tag|Add document/ })).toHaveCount(0)
  await expect(anonPage.locator('input[type="file"]')).toHaveCount(0)

  // 2. Enforced server-side, not just hidden in the UI: an anonymous WRITE attempt
  //    against the document (even threading the share token) is rejected. A share
  //    grants READ only, so the mutation must be denied (401/403), never 200.
  const mutate = await anonContext.request.post(`/api/document/${id}`, {
    form: { title: `hacked ${title}`, language: 'eng' },
    params: { share: shareUrl.split('/share/')[1].split(/[/?#]/)[1] },
  })
  expect(mutate.status(), 'anonymous WRITE must be denied').toBeGreaterThanOrEqual(400)
  expect(mutate.status()).toBeLessThan(500)
  // And the document title is genuinely unchanged (the write did not land).
  await anonPage.reload()
  await expect(anonPage.getByRole('heading', { name: title })).toBeVisible()
  await expect(anonPage.getByRole('heading', { name: `hacked ${title}` })).toHaveCount(0)

  // Revoke the share from the authenticated session.
  await page.getByRole('button', { name: 'Revoke link' }).click()
  await confirmDanger(page)
  await expect(page.getByText('No active share links.')).toBeVisible()

  // The public link no longer resolves the document — the share view shows the
  // not-found state. A same-hash goto() would be a no-op (already there), so force
  // a full reload to re-run the share query against the now-dead link.
  await anonPage.reload()
  await expect(anonPage.getByText('This shared document is no longer available.')).toBeVisible()
  await expect(anonPage.getByRole('heading', { name: title })).toHaveCount(0)

  await anonContext.close()
})
