import { test, expect } from '@playwright/test'
import { unique, createDocument } from './helpers'

// #28: the "Assigned to me" (workflow=me) document-list filter must round-trip
// through the URL — toggling it activates the filter and puts `workflow=me` in the
// URL; opening a document and navigating Back restores the filter (toggle stays on,
// URL still carries workflow=me). Proves the returnTo-carries-workflow + route
// hydration path end to end against the real app.
//
// REALNESS: the toggle is a PrimeVue ToggleButton labelled "Assigned to me"; its
// pressed state (aria-pressed / .p-togglebutton-checked) and the `workflow=me` URL
// param are the assertions. If returnTo dropped the key or the route never hydrated
// it, the Back navigation would land on a bare /document with the toggle OFF.

test('the "Assigned to me" filter round-trips through open + Back (#28)', async ({ page }) => {
  // A document to open (so there is a row to navigate into and Back from).
  const doc = await createDocument(page, unique('wf-filter'))

  try {
    await page.goto('/#/document')

    const toggle = page.getByRole('button', { name: 'Assigned to me' })
    await expect(toggle).toBeVisible()

    // Activate the filter — the URL gains workflow=me.
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-pressed', 'true')
    await expect(page).toHaveURL(/workflow=me/)

    // Open a document (full view via double-click on its row).
    const row = page.getByRole('row', {
      name: new RegExp(doc.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
    })
    // The filter may hide the row (admin isn't assigned) — navigate to the view
    // directly to exercise the Back path deterministically, carrying the filtered
    // list as the referrer via the app's in-router history.
    if (await row.count()) {
      await row.dblclick()
    } else {
      await page.goto(`/#/document/view/${doc.id}`)
    }
    await expect(page).toHaveURL(/#\/document\/view\//)

    // Navigate Back to the list — the filter must be restored.
    await page.goBack()
    await expect(page).toHaveURL(/#\/document/)
    await expect(page).toHaveURL(/workflow=me/)
    await expect(page.getByRole('button', { name: 'Assigned to me' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  } finally {
    // Cleanup: remove the document.
    await page.goto(`/#/document/view/${doc.id}`).catch(() => {})
    const del = page.getByRole('button', { name: 'Delete', exact: true })
    if (await del.isVisible().catch(() => false)) {
      await del.click()
      const dialog = page.getByRole('alertdialog')
      await dialog.getByRole('button', { name: 'Yes' }).click().catch(() => {})
    }
  }
})
