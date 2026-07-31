import {
  expect,
  test,
  type APIRequestContext,
  type APIResponse,
  type Locator,
  type Page,
} from '@playwright/test'
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
  // Date.now() plus a monotonic counter makes two calls in the same millisecond
  // differ WITHIN a worker; process.pid makes names differ ACROSS parallel worker
  // processes (Playwright runs each worker in its own process with its own
  // module-scoped counter starting at 0, so without the pid two workers can mint
  // the identical name in the same millisecond and a durable getByText(exact)
  // assertion then matches two tree nodes). No spaces — keeps tag names single
  // -token so the panel's accessible-name regex matches cleanly.
  return `${prefix}-${Date.now()}-${process.pid}-${counter++}`
}

// --- Tag names: the same uniqueness, inside the server's length cap ----------
// TagResource caps a tag name at 36 characters — `add` and `update` both run
// `ValidationUtil.validateLength(name, "name", 1, 36)` (TagResource.java:225 / :318) —
// so an over-long generated name is a 400 at SEED time, surfacing as an unrelated
// "create tag" failure rather than as the real cause. unique() itself cannot be
// clamped: it also names documents, where a deliberately long title is the POINT
// (responsive.spec.ts:141 pins a 69-character title prefix to overrun the slide-over
// header). Tag names therefore get their own generator with the cap designed in, and
// #182's answer — hand-shortening individual prefixes — stops being load-bearing.

// The server's cap (TagResource.java:225, :318).
export const MAX_TAG_NAME_LENGTH = 36

// Specs derive further tag names from a generated one by appending a short suffix, and
// the DERIVED name has to clear the cap too. The longest such suffix in the suite is 3
// characters — facets.spec.ts:52 `${prefix}-${String(i).padStart(2, '0')}` ("-00").
// The others are tags.spec.ts:19's rename target `${name}-r` and documents.spec.ts:72's
// `${runId}-${n}` (both 2). Every generated tag name reserves the longest.
export const TAG_SUFFIX_BUDGET = 3

// Worst-case bounds on the three fields of the unique tail. These are the assumptions
// the length budget below rests on, so the self-check (e2e/unique-names.check.ts) sizes
// BOTH constructions from these same numbers rather than from its own copies.
export const TAG_TAIL_BOUNDS = {
  // Comfortably past the lifetime of this suite, and still 8 base-36 digits (the width
  // only grows at 36**8 ms ≈ 2059-05-25).
  maxEpochMs: Date.UTC(2050, 0, 1),
  // Linux caps pid_max at 2**22.
  maxPid: 4_194_304,
  // 36**3 - 1 generated names per worker process — two orders of magnitude past the
  // busiest spec file.
  maxCounter: 46_655,
} as const

// "-<ts>-<pid>-<counter>" in base 36. The '-' separators are what make the tail
// INJECTIVE: the fields are base-36 digits and can never contain one, so no two
// distinct (timestamp, pid, counter) triples can encode to the same tail even though
// the fields are variable-width. (A hash would be shorter and NOT injective —
// collisions there would reappear as the same 400-flake class, one layer down.)
export const TAG_TAIL_MAX_LENGTH =
  1 +
  TAG_TAIL_BOUNDS.maxEpochMs.toString(36).length +
  1 +
  TAG_TAIL_BOUNDS.maxPid.toString(36).length +
  1 +
  TAG_TAIL_BOUNDS.maxCounter.toString(36).length

// What is left for the caller's prefix once the tail and the suffix budget are reserved.
export const MAX_TAG_PREFIX_LENGTH = MAX_TAG_NAME_LENGTH - TAG_SUFFIX_BUDGET - TAG_TAIL_MAX_LENGTH

/**
 * A unique TAG name that fits the server's 36-character cap with room to spare for the
 * short suffixes specs append (see TAG_SUFFIX_BUDGET).
 *
 * Uniqueness is structural, exactly as in unique(): the monotonic counter separates
 * calls within a worker process, process.pid separates parallel workers, and the
 * timestamp separates runs. Only the ENCODING changes — base 36 instead of decimal,
 * which is what buys the headroom.
 *
 * An over-long prefix THROWS at generation time rather than being truncated: truncating
 * would silently eat the unique tail and reintroduce cross-worker collisions, which is a
 * far worse failure than a loud error naming the file to fix.
 */
export function uniqueTag(prefix: string): string {
  if (prefix.length > MAX_TAG_PREFIX_LENGTH) {
    throw new Error(
      `uniqueTag("${prefix}"): prefix is ${prefix.length} characters, at most ${MAX_TAG_PREFIX_LENGTH} fit — ` +
        `the server caps a tag name at ${MAX_TAG_NAME_LENGTH} (TagResource#add/#update), of which the unique tail ` +
        `takes up to ${TAG_TAIL_MAX_LENGTH} and the derived-suffix budget ${TAG_SUFFIX_BUDGET}. Shorten the prefix.`,
    )
  }

  const name = `${prefix}-${Date.now().toString(36)}-${process.pid.toString(36)}-${(counter++).toString(36)}`

  // Belt and braces, deliberately an AGGREGATE check: it fires on any call whose total
  // would break the budget — the only externally visible failure. A field quietly
  // outgrowing its assumed width on a shorter-prefix call stays harmless: the separators
  // keep names injective at any field width, and every call re-checks the total, so no
  // over-cap name can reach the server regardless of which field drifted.
  const budget = MAX_TAG_NAME_LENGTH - TAG_SUFFIX_BUDGET
  if (name.length > budget) {
    throw new Error(
      `uniqueTag("${prefix}") produced "${name}" (${name.length} characters), over the ${budget}-character budget ` +
        `(${MAX_TAG_NAME_LENGTH}-character server cap minus the ${TAG_SUFFIX_BUDGET}-character derived-suffix budget). ` +
        `The timestamp, pid or counter has outgrown the width assumed for it.`,
    )
  }
  return name
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

// Delete a user via Settings › Users. Since #55, deleting a user reassigns their documents to a
// target and opens a real (non-danger) Dialog — it is NOT the old alertdialog danger-confirm. Since
// #180 the reassign-target Select is rendered ONLY when the departing user still owns active
// documents or tags; an account that owns nothing shows a plain confirmation. This helper handles
// both shapes, so it works for the many specs that create a throwaway user and delete it again.
// Lands with a "User deleted" toast.
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
  // Opening the dialog re-reads /user/list so its shape is decided on fresh ownership data. Wait for
  // THAT response before probing for the Select: checking earlier could read the pre-refresh shape
  // and delete without a target the server then demands.
  const listRefresh = page.waitForResponse((r) => r.url().includes('/api/user/list'))
  await delBtn.click()

  const dialog = page.getByRole('dialog', { name: 'Delete user' })
  await expect(dialog).toBeVisible()
  await listRefresh
  const reassignSelect = dialog.locator('#reassign-target')
  if ((await reassignSelect.count()) > 0) {
    await reassignSelect.click()
    await page.getByRole('option', { name: reassignTo, exact: true }).click()
  }
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

// --- API teardown helpers (#187) ---------------------------------------------
// Teardown must not drive the UI. The UI path is slow, it is the very surface a failed
// body left broken, and every one of its waits is another way for cleanup to throw or
// hang. These helpers hit the REST API directly and FAIL LOUDLY: a teardown that
// silently 4xx'd is how documents, tags and users pile up across a suite run.
//
// They are deliberately idempotent for the "the body already deleted it" case (404),
// and only for that case.

async function describeResponse(res: APIResponse): Promise<string> {
  const body = await res.text().catch(() => '<unreadable body>')
  return `HTTP ${res.status()} ${body.slice(0, 500)}`
}

// --- API failure diagnostics (#186 instrumentation) ---------------------------
// A bare `expect(res.ok()).toBeTruthy()` reports "expected true, received false" and
// nothing else, so an intermittent API failure carries ZERO information about WHY the
// call failed — the exact situation that left #186 undiagnosable across several runs.
//
// `Retry-After` is reported explicitly (including when ABSENT) because it is the one
// header that distinguishes a throttle/bulkhead rejection from every other 4xx/5xx:
// UserResource sets it on both the per-account lockout (:474) and the login-work
// bulkhead shed (:490), and on nothing else. "Retry-After: <absent>" is therefore
// positive evidence AGAINST the throttle hypothesis, not merely a missing field.

/**
 * Render an API failure as evidence: status line, `Retry-After` (present or absent),
 * and the response body. Truncated at 1000 chars — Teedy's error bodies are small JSON
 * objects, and a servlet-container HTML error page is recognisable from its first line.
 */
export async function describeApiFailure(res: APIResponse): Promise<string> {
  const retryAfter = res.headers()['retry-after']
  const body = await res.text().catch(() => '<unreadable body>')
  return [
    `HTTP ${res.status()} ${res.statusText()}`,
    `Retry-After: ${retryAfter ?? '<absent>'}`,
    `url: ${res.url()}`,
    `body: ${body.slice(0, 1000) || '<empty>'}`,
  ].join(' | ')
}

/**
 * Assert an API response succeeded, reporting the real failure signal when it did not.
 *
 * The assertion still FAILS on a bad response — this only enriches its message. The body
 * is read lazily (only on failure) so the success path costs nothing.
 */
export async function expectResponseOk(res: APIResponse, label: string): Promise<void> {
  if (res.ok()) return
  expect(res.ok(), `${label} — ${await describeApiFailure(res)}`).toBeTruthy()
}

/**
 * Permanently remove a document.
 *
 * `DELETE /api/document/:id` only TRASHES (soft-delete, `DOC_DELETEDATE_D`); the document
 * keeps consuming quota and still sits in the owner's trash. Cleanup that stops there
 * passes while the corpus grows every run — a clean-start baseline finished with 40 active
 * AND 10 trashed documents — so this trashes AND purges
 * (`DELETE /api/document/:id/permanent`).
 *
 * Status codes here are MEASURED, not assumed (probed against the running app):
 *   trash   — 200 alive; 500 when already trashed or already gone (the admin path skips the
 *             ACL check, so the DAO, not the 404 branch, is what fails). "Not found" is NOT
 *             a 404 on this endpoint, so it cannot be used as the idempotency signal.
 *   purge   — 200 when the document is in the CALLER's trash; 404 otherwise. It is
 *             owner-scoped server-side (`getDeletedById(id, principal)`), so an admin
 *             context cannot purge another user's trashed document.
 *   GET     — 200 alive; 404 trashed, purged or nonexistent.
 *
 * CONTRACT FOR CALLERS: because the purge is owner-scoped, a document seeded AS A SECOND USER
 * and cleaned up through the ADMIN request context trashes fine and then 404s on the purge, and
 * this helper throws "trashed but not purged" rather than leaving it in that user's trash. A spec
 * that seeds as another user must defer its cleanup on THAT user's own context (or this helper
 * must be extended first). Every current caller seeds as admin.
 *
 * Hence: the purge result is authoritative, a failed purge after a SUCCESSFUL trash means
 * the caller is not the owner (loud failure — the document would otherwise be left in
 * someone's trash), and anything else is confirmed against a read-back before being
 * accepted as "the body already deleted it".
 */
export async function deleteDocApi(request: APIRequestContext, id: string): Promise<void> {
  const trashed = await request.delete(`/api/document/${id}`)
  const purged = await request.delete(`/api/document/${id}/permanent`)
  if (purged.ok()) return
  if (trashed.ok()) {
    throw new Error(
      `teardown: document ${id} was trashed but not purged — ${await describeResponse(purged)}. ` +
        `Permanent delete is owner-scoped: purge from the owning user's request context.`,
    )
  }
  // Neither call succeeded. Accept that only if the document really is gone — never on the
  // strength of an error code alone.
  const readBack = await request.get(`/api/document/${id}`)
  if (!readBack.ok()) return
  throw new Error(
    `teardown: document ${id} still exists after cleanup — trash ${trashed.status()}, ` +
      `purge ${purged.status()}, read-back ${readBack.status()}.`,
  )
}

/**
 * Delete a tag by id. `DELETE /api/tag/:id` answers 500 — not 404 — for a tag that is
 * already gone (measured), so an "already deleted by the body" teardown is confirmed
 * against the tag list rather than inferred from the status code.
 */
export async function deleteTagApi(request: APIRequestContext, id: string): Promise<void> {
  const res = await request.delete(`/api/tag/${id}`)
  if (res.ok()) return
  const list = await request.get('/api/tag/list')
  if (list.ok()) {
    const tags = (await list.json()).tags as Array<{ id: string }>
    if (!tags.some((t) => t.id === id)) return
  }
  throw new Error(`teardown: delete tag ${id} failed — ${await describeResponse(res)}`)
}

/**
 * Delete a tag by NAME (several specs only ever hold the name). Resolves it against the
 * caller's tag list; a name that is no longer present is not an error.
 */
export async function deleteTagByNameApi(request: APIRequestContext, name: string): Promise<void> {
  const listRes = await request.get('/api/tag/list')
  if (!listRes.ok()) {
    throw new Error(`teardown: list tags before deleting "${name}" failed — ${await describeResponse(listRes)}`)
  }
  const tags = (await listRes.json()).tags as Array<{ id: string; name: string }>
  const match = tags.find((t) => t.name === name)
  if (!match) return
  await deleteTagApi(request, match.id)
}

/**
 * Delete a group by name. Measured: `DELETE /api/group/:name` answers 200 on success and a clean
 * 404 when the group is already gone (unlike the document/tag endpoints, which answer 500) — but
 * the 404 is confirmed against the group list rather than trusted, so a future change of that
 * status cannot turn a failed teardown into a silent no-op.
 */
export async function deleteGroupApi(request: APIRequestContext, name: string): Promise<void> {
  const res = await request.delete(`/api/group/${name}`)
  if (res.ok()) return
  const list = await request.get('/api/group')
  if (list.ok()) {
    const groups = (await list.json()).groups as Array<{ name: string }>
    if (!groups.some((g) => g.name === name)) return
  }
  throw new Error(`teardown: delete group ${name} failed — ${await describeResponse(res)}`)
}

/**
 * Delete a user, reassigning any content they still own.
 *
 * `reassign_to_username` is passed ALWAYS: since #180 the server only demands it when
 * the departing account still owns an active document or tag, but a teardown cannot
 * know whether the body left content behind — and omitting it there fails the delete
 * with `ReassignRequired`, whose 400 a `.catch(() => {})` teardown swallowed while the
 * user leaked. The target must be an active user distinct from the departing one;
 * `admin` satisfies both by construction.
 *
 * A user the body already deleted (`UserNotFound`) is not an error.
 */
export async function deleteUserApi(
  request: APIRequestContext,
  username: string,
  reassignTo = 'admin',
): Promise<void> {
  const res = await request.delete(`/api/user/${username}`, {
    params: { reassign_to_username: reassignTo },
  })
  if (res.ok()) return
  const body = await res.text().catch(() => '')
  if (body.includes('UserNotFound')) return
  throw new Error(`teardown: delete user ${username} failed — HTTP ${res.status()} ${body.slice(0, 500)}`)
}

/**
 * The ONE sanctioned way to run teardown inside a `finally` — the single exemption the
 * `no-restricted-syntax` guardrail carves out, for contexts where the `cleanup` fixture
 * is not available (Playwright global setup) or a teardown genuinely must run inside a
 * finalizer.
 *
 * It preserves the body's error BY CONSTRUCTION: it never throws. A failed step is
 * attached to the test report as a `cleanup-failures` diagnostic when a test is running,
 * and logged to stderr otherwise.
 *
 * It is strictly WEAKER than `cleanup.defer`, and that is why it is the fallback and not
 * the default: code inside a `finally` cannot tell whether the body passed (Playwright
 * records the body's error only after the test function returns), so this wrapper cannot
 * turn a broken cleanup after a GREEN body into a red test. `cleanup.defer` can, and does.
 */
export async function guardedTeardown(label: string, fn: () => unknown | Promise<unknown>): Promise<void> {
  const failure = await Promise.resolve()
    .then(fn)
    .then(() => null)
    .catch((error: unknown) => error)
  if (failure === null) return
  const detail = failure instanceof Error ? (failure.stack ?? failure.message) : String(failure)
  const info = (() => {
    try {
      return test.info()
    } catch {
      return null
    }
  })()
  if (!info) {
    process.stderr.write(`guarded teardown "${label}" failed:\n${detail}\n`)
    return
  }
  await info.attach('cleanup-failures', {
    body: `guarded teardown step failed:\n\n- ${label}\n  ${detail}\n`,
    contentType: 'text/plain',
  })
}
