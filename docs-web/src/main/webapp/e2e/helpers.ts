import { expect, type Page } from '@playwright/test'
import { createHmac } from 'node:crypto'

// Shared e2e helpers. Kept selector-light and user-facing to match the harness
// style (getByRole/getByLabel/getByText; the reused #edit-title id for the doc
// form). Every helper here produces UNIQUE titles/names per run so specs are
// idempotent — a re-run never collides with leftovers from a prior run.

let counter = 0
export function unique(prefix: string): string {
  // Date.now() runs in Node inside the spec; add a monotonic counter so two
  // calls in the same millisecond still differ. No spaces — keeps tag names
  // single-token so the panel's accessible-name regex matches cleanly.
  return `${prefix}-${Date.now()}-${counter++}`
}

// Create a document via the real Add-document form. Returns the new document id
// (parsed from the /document/view/<id> URL the save routes to) plus the title.
export async function createDocument(
  page: Page,
  title: string,
  opts: { description?: string } = {},
): Promise<{ id: string; title: string }> {
  await page.goto('/#/document/add')
  await expect(page.getByRole('heading', { name: 'New document' })).toBeVisible()
  await page.locator('#edit-title').fill(title)
  if (opts.description) await fillDescription(page, opts.description)
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
  const url = page.url()
  const id = url.split('/document/view/')[1].split(/[/?#]/)[0]
  return { id, title }
}

// Type plain text into the rich description editor (a Quill contenteditable, not a
// native textarea). The editor lives under the #edit-desc Editor root; its editable
// region is `.ql-editor`. Quill stores typed text as a paragraph, so the rendered
// description round-trips as `<p>text</p>`.
export async function fillDescription(page: Page, text: string): Promise<void> {
  const editor = page.locator('#edit-desc .ql-editor')
  await expect(editor).toBeVisible()
  await editor.click()
  await editor.fill(text)
}

// Delete a document (currently on its full view) via the header Delete button +
// the danger confirm dialog. Lands back on the documents list.
export async function deleteCurrentDocument(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
  await expect(page).toHaveURL(/#\/document$/)
}

// The shared danger-confirm dialog (useConfirmDanger -> PrimeVue ConfirmDialog,
// registered in App.vue with no custom labels) renders role=alertdialog with the
// default accept label "Yes" and reject "No". Accept it and wait for it to close.
export async function confirmDanger(page: Page): Promise<void> {
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'Yes' }).click()
  await expect(dialog).toBeHidden()
}

// Delete a user via Settings › Users. Since #55, deleting a user reassigns all of
// their documents to a REQUIRED target and opens a real (non-danger) Dialog with a
// reassign-target Select — it is NOT the old alertdialog danger-confirm. The row's
// trash button opens the "Delete user" dialog; a target must be picked before the
// dialog's own "Delete" button fires. Lands with a "User deleted" toast.
// `reassignTo` defaults to admin (always present, distinct from any test-created user).
export async function deleteUser(page: Page, username: string, reassignTo = 'admin'): Promise<void> {
  await page.goto('/#/settings/users')
  const row = page.getByRole('row', { name: new RegExp(username) })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Delete' }).click()

  const dialog = page.getByRole('dialog', { name: 'Delete user' })
  await expect(dialog).toBeVisible()
  await dialog.locator('#reassign-target').click()
  await page.getByRole('option', { name: reassignTo, exact: true }).click()
  await dialog.getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByText('User deleted')).toBeVisible()
}

// Log in through the native form in the current (typically fresh) context.
export async function login(page: Page, user: string, pass: string): Promise<void> {
  await page.goto('/#/login')
  await page.getByLabel('Username').fill(user)
  await page.locator('#login-pass').fill(pass)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/#\/document$/)
}

// --- TOTP (behavior A) -------------------------------------------------------
// The backend's GoogleAuthenticator is a standard RFC-6238 TOTP: Base32 secret,
// 30-second window, HmacSHA1, 6 digits (see
// docs-core/.../util/totp/GoogleAuthenticator.java). We recompute the SAME code
// here so a spec can drive a genuine valid-OTP login end to end — no mock, the
// code is checked by the real server. If the algorithm regressed server-side, a
// code computed here would be rejected and the login would fail, so this is a
// REAL assertion of the server's TOTP verification, not a self-check.

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

function base32Decode(secret: string): Buffer {
  const clean = secret.replace(/=+$/, '').toUpperCase().replace(/\s/g, '')
  let bits = ''
  for (const ch of clean) {
    const idx = BASE32_ALPHABET.indexOf(ch)
    if (idx === -1) continue
    bits += idx.toString(2).padStart(5, '0')
  }
  const bytes: number[] = []
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    bytes.push(parseInt(bits.slice(i, i + 8), 2))
  }
  return Buffer.from(bytes)
}

// Compute the 6-digit TOTP for a Base32 secret at a given epoch-ms (default now).
export function totpCode(secret: string, atMs: number = Date.now()): string {
  const key = base32Decode(secret)
  const counter = Math.floor(atMs / 1000 / 30)
  const buf = Buffer.alloc(8)
  // 64-bit big-endian counter (high word is 0 for all realistic times).
  buf.writeUInt32BE(Math.floor(counter / 2 ** 32), 0)
  buf.writeUInt32BE(counter >>> 0, 4)
  const hmac = createHmac('sha1', key).update(buf).digest()
  const offset = hmac[hmac.length - 1] & 0x0f
  const binary =
    ((hmac[offset] & 0x7f) << 24) |
    ((hmac[offset + 1] & 0xff) << 16) |
    ((hmac[offset + 2] & 0xff) << 8) |
    (hmac[offset + 3] & 0xff)
  return (binary % 1_000_000).toString().padStart(6, '0')
}
