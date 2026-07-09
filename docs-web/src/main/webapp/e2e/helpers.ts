import { expect, type Page } from '@playwright/test'

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
  if (opts.description) await page.locator('#edit-desc').fill(opts.description)
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(/#\/document\/view\//)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
  const url = page.url()
  const id = url.split('/document/view/')[1].split(/[/?#]/)[0]
  return { id, title }
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

// Log in through the native form in the current (typically fresh) context.
export async function login(page: Page, user: string, pass: string): Promise<void> {
  await page.goto('/#/login')
  await page.getByLabel('Username').fill(user)
  await page.locator('#login-pass').fill(pass)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/#\/document$/)
}
