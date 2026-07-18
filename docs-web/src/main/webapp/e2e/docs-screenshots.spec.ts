import { test, expect, type Page, type APIRequestContext } from './fixtures'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { unique } from './helpers'

// Documentation screenshot capture (D3). Runs against the REAL e2e Docker
// container (scripts/e2e-run.sh boots the prod WAR on port 8080, embedded H2,
// admin/admin). This spec SEEDS realistic data via the same REST/UI helpers the
// functional specs use, then captures deterministic PNGs straight into docs/images/
// so the docs/*.md pages can reference them.
//
// This is a CAPTURE spec, not an assertion spec: each shot waits for a stable,
// user-visible barrier (a heading, a row, a canvas) BEFORE page.screenshot() so
// nothing races a half-rendered frame. Animations are disabled globally and the
// viewport is pinned so re-runs are byte-stable-ish and legible. It is a single
// serial test (workers=1) so the seeded fixtures compose across sections without
// cross-test ordering assumptions.
//
// Output: docs/images/<name>.png (git-tracked) — but ONLY when
// E2E_UPDATE_SCREENSHOTS=1. Regenerating these PNGs dirties the working tree of anyone
// who runs the suite (and of CI, which never wants to commit them), so by default the
// spec still SEEDS data and ASSERTS the UI (the stable barriers below) but writes
// nothing to disk. Set E2E_UPDATE_SCREENSHOTS=1 to refresh the docs images.

const here = dirname(fileURLToPath(import.meta.url))
// e2e -> webapp -> main -> src -> docs-web -> repo root, then docs/images.
const IMAGES_DIR = resolve(here, '../../../../..', 'docs/images')
const invoicePdf = resolve(here, 'fixtures/invoice.pdf')
const VIEWPORT = { width: 1280, height: 800 }
const UPDATE_SCREENSHOTS = process.env.E2E_UPDATE_SCREENSHOTS === '1'

function shotPath(name: string): string {
  return resolve(IMAGES_DIR, `${name}.png`)
}

// A full-viewport (not full-page) screenshot: the fixed 1280x800 frame keeps the
// docs images a consistent size and avoids capturing an over-long scrolled body.
// A no-op unless E2E_UPDATE_SCREENSHOTS=1 so a normal run never writes to the tree.
async function shootViewport(page: Page, name: string): Promise<void> {
  if (!UPDATE_SCREENSHOTS) return
  await page.screenshot({ path: shotPath(name), animations: 'disabled' })
}

// A shot clipped to a single element's bounding box (padded) — used for focused
// captures like the search-bar dropdown so the docs image frames the feature. The
// bounding-box assertion still runs (it is a real check of the element) but the write
// is gated on E2E_UPDATE_SCREENSHOTS=1.
async function shootElement(page: Page, locator: ReturnType<Page['locator']>, name: string): Promise<void> {
  const box = await locator.boundingBox()
  expect(box, `bounding box for ${name}`).not.toBeNull()
  if (!UPDATE_SCREENSHOTS) return
  const pad = 12
  await page.screenshot({
    path: shotPath(name),
    animations: 'disabled',
    clip: {
      x: Math.max(0, box!.x - pad),
      y: Math.max(0, box!.y - pad),
      width: Math.min(VIEWPORT.width, box!.width + pad * 2),
      height: box!.height + pad * 2,
    },
  })
}

// --- API seeding helpers (mirror the functional specs) -----------------------

async function apiCreateTag(request: APIRequestContext, name: string, color = '#2aabd2'): Promise<string> {
  const res = await request.put('/api/tag', { form: { name, color } })
  expect(res.ok(), `create tag ${name}`).toBeTruthy()
  return (await res.json()).id as string
}

async function apiCreateDocument(
  request: APIRequestContext,
  opts: { title: string; description?: string; tagIds?: string[] },
): Promise<string> {
  const body = new URLSearchParams([
    ['title', opts.title],
    ['language', 'eng'],
    ...(opts.description ? [['description', opts.description] as [string, string]] : []),
    ...(opts.tagIds ?? []).map((id): [string, string] => ['tags', id]),
  ])
  const res = await request.put('/api/document', {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: body.toString(),
  })
  expect(res.ok(), `create document ${opts.title}`).toBeTruthy()
  return (await res.json()).id as string
}

async function apiAttachFile(request: APIRequestContext, documentId: string, filePath: string, name: string): Promise<void> {
  const { readFileSync } = await import('node:fs')
  const res = await request.put('/api/file', {
    multipart: {
      id: documentId,
      file: { name, mimeType: 'application/pdf', buffer: readFileSync(filePath) },
    },
  })
  expect(res.ok(), `attach ${name} to ${documentId}`).toBeTruthy()
}

test.describe.configure({ mode: 'serial' })

test.beforeEach(async ({ page }) => {
  await page.setViewportSize(VIEWPORT)
  // Kill transitions/animations so every shot is a settled frame.
  await page.addStyleTag({
    content: `*, *::before, *::after { transition: none !important; animation: none !important; caret-color: transparent !important; }`,
  }).catch(() => {})
})

// ============================================================================
// 1. Document list with tag chips + the facet/filter panel, and the plain
//    first-login screen (no OIDC).
// ============================================================================
test('doc list, tag facets, and the first-login screen', async ({ page, request }) => {
  // Seed a small realistic corpus: three tags, several tagged documents.
  const tInvoice = await apiCreateTag(request, 'invoice', '#e67e22')
  const tContract = await apiCreateTag(request, 'contract', '#2aabd2')
  const tReport = await apiCreateTag(request, 'report', '#27ae60')

  await apiCreateDocument(request, { title: 'ACME invoice 2026-0042', tagIds: [tInvoice] })
  await apiCreateDocument(request, { title: 'Office lease agreement', tagIds: [tContract] })
  await apiCreateDocument(request, { title: 'Q2 financial report', tagIds: [tReport] })
  await apiCreateDocument(request, { title: 'Supplier invoice — Globex', tagIds: [tInvoice, tContract] })
  await apiCreateDocument(request, { title: 'Onboarding handbook', tagIds: [tReport] })

  // The documents list: rows carry tag chips; the left panel shows the filter tree.
  await page.goto('/#/document')
  // These are readiness barriers before the shot, not uniqueness checks. This spec
  // seeds fixed, realistic titles by design, and CI reruns the whole serial file on a
  // retry — which re-seeds the same titles on top of the first attempt's, so the list
  // legitimately holds several "ACME invoice 2026-0042" rows / "invoice" facet nodes.
  // Match the first so a duplicate from a retry can't trip strict mode.
  await expect(page.getByText('ACME invoice 2026-0042', { exact: true }).first()).toBeVisible()
  // Ensure the tag filter panel has rendered its tags before shooting.
  await expect(page.locator('.left-panel').getByRole('button', { name: /invoice/ }).first()).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'document-list-facets')

  // The plain first-login screen (default install, OIDC off): username/password
  // form with the Teedy logo. Fresh anonymous context so no session leaks in.
  const context = await page.context().browser()!.newContext({
    storageState: { cookies: [], origins: [] },
    baseURL: page.url().split('/#')[0],
    viewport: VIEWPORT,
  })
  const anon = await context.newPage()
  try {
    await anon.addStyleTag({ content: `*, *::before, *::after { transition: none !important; animation: none !important; }` }).catch(() => {})
    await anon.goto('/#/login?local=1')
    await expect(anon.getByLabel('Username')).toBeVisible()
    await expect(anon.getByRole('button', { name: 'Sign in' })).toBeVisible()
    await anon.waitForLoadState('networkidle')
    await anon.setViewportSize(VIEWPORT)
    if (UPDATE_SCREENSHOTS) await anon.screenshot({ path: shotPath('login-first'), animations: 'disabled' })
  } finally {
    await context.close()
  }
})

// ============================================================================
// 2. A document view: description, an attached PDF file, the relations section,
//    and the rotation controls on the PDF preview.
// ============================================================================
test('document view: relations + PDF rotation controls', async ({ page, request }) => {
  const tInvoice = await apiCreateTag(request, unique('inv').replace(/[^a-z0-9]/gi, ''), '#e67e22')
  const mainId = await apiCreateDocument(request, {
    title: 'ACME invoice 2026-0042',
    description:
      'Monthly services invoice from ACME Supplies GmbH. Received 2026-07-11, net 30 days. Total due EUR 255.84.',
    tagIds: [tInvoice],
  })
  const relatedId = await apiCreateDocument(request, {
    title: 'Purchase order PO-8817',
    description: 'The purchase order this invoice bills against.',
  })
  await apiAttachFile(request, mainId, invoicePdf, 'invoice-2026-0042.pdf')

  // Link the invoice to the purchase order via the real document-update API:
  // POST /api/document/{id} with a `relations` form param (DocumentResource
  // updateRelationList). The relation section then renders "Links to" on the source.
  const relRes = await request.post(`/api/document/${mainId}`, {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    data: new URLSearchParams([
      ['title', 'ACME invoice 2026-0042'],
      ['language', 'eng'],
      ['relations', relatedId],
    ]).toString(),
  })
  expect(relRes.ok(), 'link invoice to purchase order').toBeTruthy()

  // Content tab of the invoice: description, the PDF preview with rotate controls,
  // and the related-documents section.
  await page.goto(`/#/document/view/${mainId}/content`)
  await expect(page.getByRole('heading', { name: 'ACME invoice 2026-0042' })).toBeVisible()

  // Wait for the PDF canvas to actually paint (pdf.js renders into a <canvas>).
  const canvas = page.locator('.pdf-viewer canvas')
  await expect(canvas).toBeVisible()
  await expect
    .poll(async () => ((await canvas.boundingBox())?.height ?? 0), { timeout: 15_000 })
    .toBeGreaterThan(100)
  // Rotate controls present.
  await expect(page.getByRole('button', { name: 'Rotate right' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Related documents' })).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'document-view-relations')

  // A tighter shot centred on the PDF preview + its rotate controls.
  const previewCard = page.locator('.file-preview-card').first()
  await previewCard.scrollIntoViewIfNeeded()
  await expect(previewCard).toBeVisible()
  await shootElement(page, previewCard, 'document-viewer-rotation')
})

// ============================================================================
// 3. Bulk multi-select action bar and the Trash view.
// ============================================================================
test('document list bulk-select bar and the trash view', async ({ page, request }) => {
  const idA = await apiCreateDocument(request, { title: 'Bulk demo — payslip March' })
  const idB = await apiCreateDocument(request, { title: 'Bulk demo — payslip April' })
  await apiCreateDocument(request, { title: 'Bulk demo — payslip May' })

  await page.goto('/#/document')
  await expect(page.getByText('Bulk demo — payslip March', { exact: true })).toBeVisible()

  // Enter multi-select: tick two row checkboxes; the bulk-action bar appears.
  const rowA = page.getByRole('row', { name: /Bulk demo — payslip March/ })
  const rowB = page.getByRole('row', { name: /Bulk demo — payslip April/ })
  await rowA.getByRole('checkbox').first().check()
  await rowB.getByRole('checkbox').first().check()
  // The bulk bar shows a selected count (e.g. "2 selected").
  await expect(page.getByText(/2\s+selected/i)).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'document-list-bulk')

  // Trash view: soft-delete one document, then shoot the trash list with its
  // restore / permanent-delete actions.
  const delRes = await request.delete(`/api/document/${idA}`)
  expect(delRes.ok()).toBeTruthy()
  await page.goto('/#/document/trash')
  await expect(page.getByRole('heading', { name: 'Trash' })).toBeVisible()
  await expect(page.getByText('Bulk demo — payslip March', { exact: true })).toBeVisible()
  await expect(page.getByRole('row', { name: /Bulk demo — payslip March/ }).locator('.p-tag')).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'trash')
  void idB
})

// ============================================================================
// 4. Saved-filters dropdown in the search bar.
// ============================================================================
test('saved-filters dropdown in the search bar', async ({ page, request }) => {
  const tag = unique('sf-tag').replace(/[^a-z0-9-]/gi, '')
  const tagId = await apiCreateTag(request, tag)
  await apiCreateDocument(request, { title: 'Saved-filter demo doc', tagIds: [tagId] })

  await page.goto('/#/document')
  await expect(page.getByText('Saved-filter demo doc', { exact: true })).toBeVisible()

  // Build a filter (include the tag) then save it by name.
  const tagNode = page.locator('.left-panel').getByRole('button', { name: new RegExp(tag) })
  await expect(tagNode).toBeVisible()
  await tagNode.click()
  await expect(page).toHaveURL(/[?&]tags=/)
  await page.getByPlaceholder('Search', { exact: true }).fill('invoice 2026')

  await page.getByRole('button', { name: 'Save filter' }).click()
  await page.locator('#saved-filter-name').fill('Unpaid invoices')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Filter saved')).toBeVisible()

  // Open the Saved-filters dropdown so both it and the "Save filter" button show.
  await page.getByRole('button', { name: 'Saved filters' }).click()
  await expect(page.getByRole('button', { name: 'Unpaid invoices', exact: true })).toBeVisible()
  await page.waitForLoadState('networkidle')
  // Full-viewport: the "Saved filters" dropdown renders in a teleported overlay,
  // so a viewport shot reliably frames the search bar + dropdown + "Save filter".
  await shootViewport(page, 'saved-filters')
})

// ============================================================================
// 5. Settings → Workflow: build the invoice-approval 2-step model, capture the
//    editor; then run it on a doc and capture the mid-flight pending step and the
//    validate/approve action buttons.
// ============================================================================
test('workflow editor, a running route, and the act buttons', async ({ page, request }) => {
  const modelName = 'Invoice Approval'
  const groupName = unique('wfgrp').replace(/[^a-z0-9]/gi, '').toLowerCase()

  // Group admin belongs to (so admin can act on the steps + the target resolves).
  const createGrp = await request.put('/api/group', { form: { name: groupName } })
  expect(createGrp.ok()).toBeTruthy()
  const addAdmin = await request.put(`/api/group/${groupName}`, { form: { username: 'admin' } })
  expect(addAdmin.ok()).toBeTruthy()

  // Build the model in the real editor.
  await page.goto('/#/settings/workflow')
  await expect(page.getByRole('heading', { name: 'Workflows' })).toBeVisible()
  await page.getByRole('button', { name: 'New workflow' }).click()
  const dialog = page.getByRole('dialog', { name: 'New workflow' })
  await expect(dialog).toBeVisible()
  await dialog.locator('#wf-name').fill(modelName)

  const stepCards = dialog.locator('.step-card')
  await configureStep(page, stepCards.nth(0), { name: 'Accounting check', type: 'Validate', groupName })
  await dialog.getByRole('button', { name: 'Add step' }).click()
  await expect(stepCards).toHaveCount(2)
  await configureStep(page, stepCards.nth(1), { name: 'Manager approval', type: 'Approve', groupName })

  // Capture the filled editor BEFORE saving (both steps visible with types+targets).
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'workflow-editor')

  await dialog.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByText('Workflow saved')).toBeVisible()

  // Start the route on a document and capture the mid-flight pending step.
  const docId = await apiCreateDocument(request, { title: 'ACME invoice 2026-0042 (approval)' })
  await page.goto(`/#/document/view/${docId}/workflow`)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Start a workflow' })).toBeVisible()
  await page.locator('.wf-start-select').click()
  // A retry re-seed can leave several models named "Invoice Approval"; any is a valid
  // one to start, so pick the first rather than tripping strict mode on the duplicate.
  await page.getByRole('option', { name: modelName, exact: true }).first().click()
  await page.getByRole('button', { name: 'Start', exact: true }).click()
  await expect(page.getByText('Workflow started')).toBeVisible()

  // Mid-flight: the first (Accounting check / VALIDATE) step is the current step.
  await expect(page.getByRole('heading', { name: 'Current step' })).toBeVisible()
  await expect(page.locator('.wf-step-name')).toHaveText('Accounting check')
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'workflow-pending')

  // The act buttons + comment field on the pending step. The VALIDATE step shows a
  // Validate button and a comment box; capture the current-step panel.
  const stepPanel = page.locator('.wf-current-step, .wf-step-actions').first()
  if (await stepPanel.count()) {
    await shootElement(page, stepPanel, 'workflow-actions')
  } else {
    await shootViewport(page, 'workflow-actions')
  }
})

async function configureStep(
  page: Page,
  card: ReturnType<Page['locator']>,
  opts: { name: string; type: 'Validate' | 'Approve'; groupName: string },
): Promise<void> {
  await card.locator('input.step-name').fill(opts.name)
  await card.locator('.step-field').nth(0).locator('.p-select').click()
  await page.getByRole('option', { name: opts.type, exact: true }).click()
  await card.locator('.step-field').nth(1).locator('.p-select').click()
  await page.getByRole('option', { name: 'Group', exact: true }).click()
  const targetInput = card.locator('.step-field-target input')
  // The target is a PrimeVue AutoComplete that searches as you type
  // (/api/acl/target/search, debounced). fill() sets the value in one shot and can
  // race the controlled re-render, so the debounced search fires with an empty query
  // (completeTargetSearch bails on an empty query) and no option ever renders — the
  // option click then times out under CI load. Type key by key so the
  // search-as-you-type listener fires deterministically for the full query.
  await targetInput.click()
  await targetInput.pressSequentially(opts.groupName)
  await page.getByRole('option', { name: opts.groupName, exact: true }).click()
  await expect(targetInput).toHaveValue(opts.groupName)
}

// ============================================================================
// 6. Settings → Vocabulary: doc-types with Invoice / Contract / Report entries.
// ============================================================================
test('settings vocabulary with doc-type entries', async ({ page, request }) => {
  const ns = 'doc-types'
  // Seed the namespace + entries via API so the picker has a realistic list.
  const entries = ['Invoice', 'Contract', 'Report']
  for (let i = 0; i < entries.length; i++) {
    await request.put('/api/vocabulary', { form: { name: ns, value: entries[i], order: String(i) } }).catch(() => {})
  }

  await page.goto('/#/settings/vocabulary')
  await expect(page.getByRole('heading', { name: 'Vocabularies' })).toBeVisible()
  // Select the seeded namespace.
  await page.locator('#vocabulary-name').click()
  await page.getByRole('option', { name: ns, exact: true }).click()
  await expect(page.getByRole('cell', { name: 'Invoice', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'Contract', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'Report', exact: true })).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'vocabulary')
})

// ============================================================================
// 7. Settings → Tag rules: one rule (tag + rule type + regex pattern).
// ============================================================================
test('settings tag rules with a content-regex rule', async ({ page, request }) => {
  const tag = unique('rule-tag').replace(/[^a-z0-9-]/gi, '')
  const tagId = await apiCreateTag(request, tag, '#e67e22')
  // Seed the rule via API for determinism (CONTENT_REGEX matching "invoice").
  const ruleRes = await request.put('/api/tagmatchrule', {
    form: { tag_id: tagId, rule_type: 'CONTENT_REGEX', pattern: '(?i)invoice', order: '0' },
  })
  expect(ruleRes.ok(), 'create tag-match rule').toBeTruthy()

  await page.goto('/#/settings/tag-rules')
  await expect(page.getByRole('heading', { name: /Auto-tagging rules/i })).toBeVisible()
  await expect(page.getByRole('row', { name: /invoice/ })).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'tag-rules')
})

// ============================================================================
// 8. Settings → Webhooks: a webhook with its event dropdown.
// ============================================================================
test('settings webhooks with the event dropdown open', async ({ page }) => {
  await page.goto('/#/settings/webhooks')
  await expect(page.getByRole('heading', { name: /Webhooks/i })).toBeVisible()

  await page.getByRole('button', { name: 'Add webhook' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.locator('#webhook-url').fill('https://automation.example.com/teedy-hook')
  // Open the event Select so the dropdown is visible in the shot.
  await dialog.locator('#webhook-event').click()
  await expect(page.getByRole('option').first()).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'webhooks')
})

// ============================================================================
// 9. Settings → Inbox: enable toggle + IMAP connection fields.
// ============================================================================
test('settings inbox with the connection fields revealed', async ({ page }) => {
  await page.goto('/#/settings/inbox')
  await expect(page.getByRole('heading', { name: /Inbox/i })).toBeVisible()

  // Turn scanning on to reveal the IMAP fields, then fill a plausible host so the
  // shot is realistic (config is not saved).
  await page.locator('#inbox-enabled').click()
  await expect(page.locator('#inbox-hostname')).toBeVisible()
  await page.locator('#inbox-hostname').fill('imap.example.com')
  await page.locator('#inbox-port').fill('993')
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'inbox')
})

// ============================================================================
// 10. Settings → OIDC: enabled with a masked secret; and the login screen with
//     the "Login with SSO" button + footer links (config is left DISABLED after).
// ============================================================================
test('OIDC settings, SSO login screen, footer links, and the users list', async ({ page, request }) => {
  // --- OIDC settings shot: enable + fill fields, masked secret ---
  await page.goto('/#/settings/oidc')
  await expect(page.getByRole('heading', { name: 'OIDC authentication' })).toBeVisible()
  await page.locator('#oidc-enabled').click()
  await expect(page.locator('#oidc-issuer')).toBeVisible()
  await page.locator('#oidc-issuer').fill('https://auth.example.com')
  await page.locator('#oidc-client-id').fill('teedy')
  await page.locator('#oidc-client-secret').fill('super-secret-value')
  // redirect_uri is required when enabled and has no default — fill it or the save
  // fails validation (scope + claims already carry defaults).
  await page.locator('#oidc-redirect-uri').fill('https://teedy.example.com/api/oidc/callback')
  await page.locator('#oidc-username-claim').fill('preferred_username')
  await page.locator('#oidc-email-claim').fill('email')
  // Ensure the secret is masked (type=password) in the shot.
  await expect(page.locator('#oidc-client-secret')).toHaveAttribute('type', 'password')
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'oidc-settings')

  // Save so oidc_enabled flips true → the login screen renders the SSO button.
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('OIDC configuration saved')).toBeVisible()

  // --- Footer links: set two via the admin API so they render on login ---
  await request.post('/api/app/footer_links', {
    form: { links: JSON.stringify([
      { label: 'Imprint', url: 'https://example.com/imprint' },
      { label: 'Privacy', url: 'https://example.com/privacy' },
    ]) },
  })

  // --- Users list shot: create an extra user so the list is realistic ---
  const userName = unique('jdoe').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const userRes = await request.put('/api/user', {
    // Password must satisfy strength rules (upper+lower+digit) or the create 400s.
    form: { username: userName, password: 'Password123', email: `${userName}@example.com`, storage_quota: '1000000000' },
  })
  expect(userRes.ok(), 'create demo user').toBeTruthy()
  await page.goto('/#/settings/users')
  await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible()
  await expect(page.getByRole('row', { name: /admin/ })).toBeVisible()
  await expect(page.getByRole('row', { name: new RegExp(userName) })).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'users-list')

  // Users edit dialog exposing the quota field.
  await page.getByRole('row', { name: new RegExp(userName) }).getByRole('button', { name: 'Edit' }).click()
  await expect(page.locator('#edit-user-quota')).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'users-quota')
  await page.getByRole('button', { name: 'Cancel' }).click().catch(() => {})

  // --- SSO login screen (anonymous) with the SSO button + footer links ---
  const context = await page.context().browser()!.newContext({
    storageState: { cookies: [], origins: [] },
    baseURL: page.url().split('/#')[0],
    viewport: VIEWPORT,
  })
  const anon = await context.newPage()
  try {
    await anon.addStyleTag({ content: `*, *::before, *::after { transition: none !important; animation: none !important; }` }).catch(() => {})
    // ?local=1 suppresses the auto-redirect so BOTH the local form and the SSO
    // button render together (matches the docs description).
    await anon.goto('/#/login?local=1')
    await expect(anon.getByRole('button', { name: 'Sign in' })).toBeVisible()
    await expect(anon.getByRole('button', { name: /SSO/i })).toBeVisible()
    await expect(anon.getByRole('link', { name: 'Imprint' })).toBeVisible()
    await anon.waitForLoadState('networkidle')
    await anon.setViewportSize(VIEWPORT)
    if (UPDATE_SCREENSHOTS) await anon.screenshot({ path: shotPath('login-sso'), animations: 'disabled' })
  } finally {
    await context.close()
  }

  // Cleanup the global config so a re-run / other specs see defaults.
  await request.post('/api/app/footer_links', { form: { links: JSON.stringify([]) } }).catch(() => {})
  // Disable OIDC again (save a disabled config).
  await page.goto('/#/settings/oidc')
  await expect(page.getByRole('heading', { name: 'OIDC authentication' })).toBeVisible()
  const enabledToggle = page.locator('#oidc-enabled')
  // If it's on, click to turn off.
  if (await page.locator('#oidc-issuer').count()) {
    await enabledToggle.click()
  }
  await page.getByRole('button', { name: 'Save', exact: true }).click().catch(() => {})
})

// ============================================================================
// 11. Cookbook: an "invoice" document created from an inbox import, carrying its
//     auto-applied tag (shown on the document view header).
// ============================================================================
test('cookbook: an invoice document with its auto-applied tag', async ({ page, request }) => {
  const tagId = await apiCreateTag(request, 'invoice-auto', '#e67e22')
  const id = await apiCreateDocument(request, {
    title: 'ACME invoice 2026-0042',
    description: 'Imported from the invoices@ mailbox and auto-tagged by a CONTENT_REGEX rule.',
    tagIds: [tagId],
  })
  await apiAttachFile(request, id, invoicePdf, 'invoice-2026-0042.pdf')

  await page.goto(`/#/document/view/${id}`)
  await expect(page.getByRole('heading', { name: 'ACME invoice 2026-0042' })).toBeVisible()
  // The auto-applied tag chip in the header.
  await expect(page.locator('.doc-header-tags').getByRole('button', { name: /invoice-auto/ })).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'cookbook-invoice')
})

// ============================================================================
// 12. /apidoc Swagger UI page.
// ============================================================================
test('apidoc swagger UI', async ({ page }) => {
  await page.goto('/apidoc/')
  await expect(page.getByRole('heading', { name: 'Teedy API' })).toBeVisible()
  await page.waitForLoadState('networkidle')
  await shootViewport(page, 'apidoc')
})
