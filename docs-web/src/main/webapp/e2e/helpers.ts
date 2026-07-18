import { expect, type Locator, type Page } from '@playwright/test'
import { createHmac } from 'node:crypto'

// Shared e2e helpers. Kept selector-light and user-facing to match the harness
// style (getByRole/getByLabel/getByText; the reused #edit-title id for the doc
// form). Every helper here produces UNIQUE titles/names per run so specs are
// idempotent — a re-run never collides with leftovers from a prior run.

// --- Viewport-aware navigation (desktop panel vs mobile Drawer) --------------
// The SAME spec set runs under two Playwright projects: `desktop` (Desktop Chrome,
// 1280px) and `mobile` (Pixel 5, 393px). AppLayout switches on
// `matchMedia('(max-width: 1024px)')` (AppLayout.vue:49): on desktop the left
// `aside.left-panel` is always mounted; on mobile it is REPLACED by a PrimeVue
// Drawer (a role=dialog) that is CLOSED by default and opened via the header
// hamburger. The tag tree (TagTreePanel, `.tag-tree`) and the footer nav links
// ("Manage tags" / "Settings") render inside WHICHEVER container is active.
//
// So a spec that clicks a tag node or a footer nav link must, on mobile, open the
// Drawer first. These helpers hide that difference: pass a Page, get back a Locator
// scoped to the live nav container regardless of viewport. Specs route their nav
// through them and work identically at both sizes — NO desktop/mobile spec forks.

// True when running under the mobile project (viewport narrower than the app's
// 1024px isMobile breakpoint). Driven by the real viewport the project sets, so it
// needs no env var and stays correct if the breakpoint is ever tuned.
export function isMobileViewport(page: Page): boolean {
  const vp = page.viewportSize()
  return !!vp && vp.width <= 1024
}

// Open the mobile navigation Drawer if it isn't already open (no-op on desktop).
// Idempotent: clicking the hamburger toggles, so we only click when the Drawer is
// absent. Returns the Drawer dialog locator on mobile, or the desktop aside.
export async function openNav(page: Page): Promise<Locator> {
  if (!isMobileViewport(page)) return page.locator('aside.left-panel')
  const drawer = page.getByRole('dialog').filter({ has: page.locator('.mobile-panel-body') })
  if (!(await drawer.isVisible().catch(() => false))) {
    await page.getByRole('button', { name: 'Menu', exact: true }).click()
    await expect(drawer).toBeVisible()
  }
  return drawer
}

// Close the mobile nav Drawer if it is open (no-op on desktop, where there is no
// Drawer). IMPORTANT on mobile: an open nav Drawer's overlay mask intercepts pointer
// events across the whole page, so a later interaction on the underlying page (e.g.
// clicking the tag tree on /tag) is blocked until the Drawer is closed. Helpers that
// open the Drawer to READ state must close it again so they leave a clean page.
export async function closeNav(page: Page): Promise<void> {
  if (!isMobileViewport(page)) return
  const drawer = page.getByRole('dialog').filter({ has: page.locator('.mobile-panel-body') })
  if (await drawer.isVisible().catch(() => false)) {
    // PrimeVue Drawer closes on an Escape press or a mask click; Escape is the most
    // robust (no geometry needed) and is what a keyboard user would do.
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden()
  }
}

// The tag-tree filter panel, viewport-agnostic. On desktop it is inside the always
// -present left panel; on mobile it lives in the Drawer, which this opens first.
// Callers then query `.getByRole('button', { name })` for a tag node exactly as
// they did against `.left-panel` before — the tag node markup is identical in both.
export async function tagTreePanel(page: Page): Promise<Locator> {
  const container = await openNav(page)
  return container.locator('.tag-tree')
}

// Click a tag node in the (viewport-correct) tag tree to toggle its filter. On
// mobile, selecting a tag closes the Drawer (handleMobileTagSelect), matching the
// real user flow; on desktop it stays open. The caller asserts the resulting URL
// (viewport-agnostic) — NOT the node's post-click aria state, which is gone on
// mobile once the Drawer closes. Use expectTagNodeState() to assert node state.
export async function toggleTagFilter(page: Page, tagName: string | RegExp): Promise<void> {
  const tree = await tagTreePanel(page)
  const node = tree.getByRole('button', { name: tagName }).first()
  await expect(node).toBeVisible()
  await node.click()
  // On mobile the select CLOSES the Drawer; WAIT for it to fully close before
  // returning, so a following read (expectTagNodeState, which re-opens the Drawer)
  // starts from a clean closed state instead of racing the close animation and
  // reading the stale, mid-close tree.
  if (isMobileViewport(page)) {
    const drawer = page.getByRole('dialog').filter({ has: page.locator('.mobile-panel-body') })
    await expect(drawer).toBeHidden()
  }
}

// Assert a tag node's filter state (its `aria-pressed` and/or excluded class),
// opening the Drawer on mobile to see it. Because a mobile select CLOSES the Drawer,
// the tri-state filter specs must re-derive the node between a click and a state
// read; and a freshly RE-MOUNTED mobile Drawer tree can momentarily render the node
// before the store state hydrates into it — so this POLLS (re-opening the nav each
// attempt) until the node reaches the expected state, rather than reading once and
// racing the re-render. On desktop the panel is always open, so it settles instantly.
export async function expectTagNodeState(
  page: Page,
  tagName: string | RegExp,
  expected: { pressed?: 'true' | 'false'; excluded?: boolean },
): Promise<void> {
  await expect
    .poll(
      async () => {
        const tree = await tagTreePanel(page)
        const node = tree.getByRole('button', { name: tagName }).first()
        if (!(await node.count())) return 'missing'
        // Report only the field(s) the caller constrains, so an exclude check does
        // not also pin aria-pressed (and vice versa).
        const parts: string[] = []
        if (expected.pressed !== undefined) parts.push(`pressed=${await node.getAttribute('aria-pressed')}`)
        if (expected.excluded !== undefined) {
          const cls = (await node.getAttribute('class')) ?? ''
          parts.push(`excluded=${/tag-excluded/.test(cls)}`)
        }
        return parts.join(',')
      },
      { message: `tag node "${tagName}" reaches ${JSON.stringify(expected)}` },
    )
    .toBe(
      [
        expected.pressed !== undefined ? `pressed=${expected.pressed}` : null,
        expected.excluded !== undefined ? `excluded=${expected.excluded}` : null,
      ]
        .filter(Boolean)
        .join(','),
    )
  // Leave the page clean: close the Drawer we opened to read, so a later interaction
  // on the underlying page isn't blocked by the (mobile) Drawer overlay mask.
  await closeNav(page)
}

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

// The document-view file panel defaults to GRID (#58). Switch it to the enriched
// LIST mode (a per-user localStorage preference) so the list-only affordances
// (rows, columns, action menu, drag reorder) are present for assertions.
export async function openFileList(page: Page): Promise<void> {
  await page.locator('.file-view-toggle').getByText('List', { exact: true }).click()
  await expect(page.locator('.file-data-table')).toBeVisible()
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
  // The Users DataTable overflows horizontally on the narrow mobile viewport and its
  // trash button sits in the last column, so scroll it into view before clicking —
  // otherwise the row can shift/re-render under the pointer (element-not-stable /
  // detached) at mobile width. Harmless on desktop (already in view).
  const delBtn = row.getByRole('button', { name: 'Delete' })
  await delBtn.scrollIntoViewIfNeeded()
  await delBtn.click()

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
