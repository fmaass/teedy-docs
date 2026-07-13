import { test, expect, type APIRequestContext } from './fixtures'
import { unique } from './helpers'

// #38: rich (sanitized) HTML descriptions with server-side sanitization.
//
// Two guarantees, both asserted against the authoritative post-refresh state:
//  1. AUTHORING — a description authored in the rich editor (bold + a list + a code
//     block) keeps its formatting after a full page reload (a real save + read-back,
//     not an in-memory render).
//  2. SANITIZATION — a hostile payload submitted at the REQUEST level (a raw
//     form-urlencoded PUT that bypasses the editor entirely) is stored inert: the
//     server strips the script/handler before persistence, so the rendered detail
//     view contains no executable script and no live event handler.

async function apiPutDocument(
  request: APIRequestContext,
  title: string,
  description: string,
): Promise<string> {
  const res = await request.put('/api/document', {
    form: { title, language: 'eng', description },
  })
  expect(res.ok(), `create document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

test('authored rich formatting survives a refresh (#38)', async ({ page }) => {
  const title = unique('rich-desc')
  let id: string | undefined

  try {
    await page.goto('/#/document/add')
    await expect(page.getByRole('heading', { name: 'New document' })).toBeVisible()
    await page.locator('#edit-title').fill(title)

    const editor = page.locator('#edit-desc .ql-editor')
    await expect(editor).toBeVisible()

    // Bold line.
    await editor.click()
    await page.locator('#edit-desc .ql-bold').click()
    await page.keyboard.type('boldword')
    await page.keyboard.press('Enter')
    // Turn OFF bold for the list.
    await page.locator('#edit-desc .ql-bold').click()

    // Bullet list with one item.
    await page.locator('#edit-desc .ql-list[value="bullet"]').click()
    await page.keyboard.type('listitemword')
    await page.keyboard.press('Enter')

    // Code block.
    await page.locator('#edit-desc .ql-code-block').click()
    await page.keyboard.type('codeword();')

    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page).toHaveURL(/#\/document\/view\//)
    id = page.url().split('/document/view/')[1].split(/[/?#]/)[0]

    // Authoritative read-back: reload the detail view and assert the rendered markup.
    await page.reload()
    const desc = page.locator('.doc-description')
    await expect(desc).toBeVisible()
    // Bold survived (a <strong>/<b> around the word).
    await expect(desc.locator('strong, b').filter({ hasText: 'boldword' })).toBeVisible()
    // The list item survived.
    await expect(desc.locator('li').filter({ hasText: 'listitemword' })).toBeVisible()
    // The code block survived.
    await expect(desc.locator('pre').filter({ hasText: 'codeword' })).toBeVisible()
  } finally {
    if (id) {
      await page.request.delete(`/api/document/${id}`)
    }
  }
})

test('request-level hostile payload is stored and rendered inert (#38)', async ({ page, request }) => {
  const title = unique('xss-desc')
  let id: string | undefined

  try {
    // Bypass the editor: submit raw HTML with a script + onerror + javascript: link
    // directly to the API, exactly as a hostile API client would.
    const hostile =
      '<p>safeword</p><script>window.__xss=1;alert(1)</script>' +
      '<img src=x onerror="window.__xss=1">' +
      '<a href="javascript:window.__xss=1">click</a>'
    id = await apiPutDocument(request, title, hostile)

    // Authoritative server read-back: the stored description is already sanitized.
    const apiRes = await request.get(`/api/document/${id}`)
    expect(apiRes.ok()).toBeTruthy()
    const stored = (await apiRes.json()).description as string
    expect(stored).toContain('safeword')
    expect(stored.toLowerCase()).not.toContain('<script')
    expect(stored).not.toContain('alert(1)')
    expect(stored.toLowerCase()).not.toContain('onerror')
    expect(stored.toLowerCase()).not.toContain('<img')
    expect(stored.toLowerCase()).not.toContain('javascript:')

    // Rendered detail view: no script executed (the sentinel global stays unset) and no
    // live event-handler attribute is present in the rendered description markup.
    await page.goto(`/#/document/view/${id}`)
    const desc = page.locator('.doc-description')
    await expect(desc).toBeVisible()
    await expect(desc).toContainText('safeword')
    expect(await desc.locator('script').count()).toBe(0)
    expect(await desc.locator('img').count()).toBe(0)
    const xssFired = await page.evaluate(() => (window as unknown as { __xss?: number }).__xss)
    expect(xssFired, 'no injected script must have executed').toBeFalsy()
  } finally {
    if (id) {
      await request.delete(`/api/document/${id}`)
    }
  }
})
