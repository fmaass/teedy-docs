import { test, expect } from './fixtures'
import { createDocument, unique } from './helpers'

// PrimeVue renders its own Yes/No on the shared danger-confirm dialog (App.vue's
// ConfirmDialog, which sets no custom accept/reject labels). Those labels come from
// PrimeVue's built-in locale, which the app now feeds from vue-i18n. This spec proves
// the delete-document confirm renders the GERMAN accept/reject ("Ja"/"Nein") — the
// strings are copied verbatim from src/locale/{de.json → yes/no, so a broken locale
// bridge (unset PrimeVue locale, or one not re-applied after setLocale) would leave
// the buttons on the English default and fail here. It is not asserting against itself.
//
// Two independent paths are covered because main.ts installs PrimeVue AFTER kicking
// off an un-awaited initial setLocale (the two can resolve in either order):
//   1) boot with a persisted German locale (the startup race), and
//   2) a runtime language switch via the real Settings control.

const DE = {
  deleteBtn: 'Löschen', // top-level `delete`
  confirmHeader: 'Dokument löschen', // ui.delete_document
  accept: 'Ja', // yes
  reject: 'Nein', // no
  deletedToast: 'Dokument gelöscht', // ui.document_deleted
}

async function assertGermanDeleteConfirm(page: import('@playwright/test').Page): Promise<void> {
  await page.getByRole('button', { name: DE.deleteBtn, exact: true }).first().click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toBeVisible()
  // The dialog header proves German is the active UI locale…
  await expect(dialog.getByText(DE.confirmHeader)).toBeVisible()
  // …and the accept/reject buttons prove PrimeVue's OWN built-in strings localized.
  await expect(dialog.getByRole('button', { name: DE.accept, exact: true })).toBeVisible()
  await expect(dialog.getByRole('button', { name: DE.reject, exact: true })).toBeVisible()
  // Complete the real flow: confirm, land on the list, see the German toast.
  await dialog.getByRole('button', { name: DE.accept, exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(page).toHaveURL(/#\/document$/)
  await expect(page.getByText(DE.deletedToast)).toBeVisible()
}

test.afterEach(async ({ page }) => {
  // Never leak a non-English locale into a shared context for later specs — and note the
  // switch persists TWO ways. The runtime-switch test drives Settings → Account, whose
  // handleLocaleChange also POSTs the choice to the admin user's server-side profile
  // (POST /api/user locale=de, #82); the auth store re-seeds the UI locale from that
  // server preference on the next login, so a fresh browser context — e.g. the
  // mobile-project rerun of the whole suite — would otherwise boot German and break
  // English-baseline assertions in unrelated specs. Restore BOTH the server preference
  // (via the same endpoint, over the page's authenticated session) and localStorage.
  // afterEach runs on every exit path — pass, fail, or timeout — so cleanup is guaranteed.
  await page.request.post('/api/user', { form: { locale: 'en' } }).catch(() => {})
  await page.evaluate(() => localStorage.setItem('teedy-locale', 'en')).catch(() => {})
})

test('delete confirm renders Ja/Nein when the app BOOTS with a persisted German locale', async ({
  page,
}) => {
  // Create the document while still English (createDocument asserts English labels).
  const { id } = await createDocument(page, unique('de-boot'))

  // Persist German and reload: main.ts now runs its initial (un-awaited) setLocale('de')
  // BEFORE registering PrimeVue — the startup race the locale bridge must survive.
  await page.evaluate(() => localStorage.setItem('teedy-locale', 'de'))
  await page.goto(`/#/document/view/${id}`)
  await page.reload()

  // German UI is active (header delete button localized) before we open the confirm.
  await expect(page.getByRole('button', { name: DE.deleteBtn, exact: true }).first()).toBeVisible()
  await assertGermanDeleteConfirm(page)
})

test('delete confirm renders Ja/Nein after a RUNTIME switch to German via Settings', async ({
  page,
}) => {
  const { id } = await createDocument(page, unique('de-switch'))

  // Switch the UI language through the real Settings → Account control (same path as
  // i18n.spec.ts), which calls setLocale at runtime and must re-apply the PrimeVue locale.
  await page.goto('/#/settings/account')
  await page.locator('#account-locale').click()
  await page.getByRole('option', { name: 'Deutsch', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Benutzerkonto' })).toBeVisible()

  await page.goto(`/#/document/view/${id}`)
  await assertGermanDeleteConfirm(page)
})
