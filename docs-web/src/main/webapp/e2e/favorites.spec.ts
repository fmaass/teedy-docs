import { test, expect, type Page, type APIRequestContext } from './fixtures'
import { unique } from './helpers'

// #41: per-user document favorites. A user STARS a document from the list row, the
// star is visible (and persistent) on the document detail, the "Favorites" filter
// restricts the list to it, then UNSTARRING removes it from the favorites view.
//
// DETERMINISM: two documents are created — one favorited, one not. Every assertion is
// made against the POST-refresh list state (a barrier: the non-favorited row detaches
// AND the favorited row survives once the filtered response renders), and against the
// authoritative server state re-read after a full page reload — never a pre-refresh
// render, never a conditional. The star's aria-pressed and the favorites=me URL param
// are the load-bearing assertions; a filter that did not actually drive the query, or a
// favorite that did not persist, fails the spec.

function rowFor(page: Page, title: string) {
  return page.getByRole('row', {
    name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
}

async function apiCreateDocument(request: APIRequestContext, title: string): Promise<string> {
  const res = await request.put('/api/document', { form: { title, language: 'eng' } })
  expect(res.ok(), `create document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

test('star from list, see it on detail, filter by favorites, unstar (#41)', async ({ page, request }) => {
  const favedTitle = unique('fav-doc')
  const otherTitle = unique('fav-other')
  let favedId: string | undefined
  let otherId: string | undefined

  try {
    favedId = await apiCreateDocument(request, favedTitle)
    otherId = await apiCreateDocument(request, otherTitle)

    await page.goto('/#/document')
    const favedRow = rowFor(page, favedTitle)
    const otherRow = rowFor(page, otherTitle)
    await expect(favedRow).toBeVisible()
    await expect(otherRow).toBeVisible()

    // STAR the first document from its row. The row's star flips to the "remove"
    // (favorited) state — an aria-pressed assertion, not a cosmetic one.
    const star = favedRow.getByRole('button', { name: 'Add to favorites' })
    await star.click()
    await expect(favedRow.getByRole('button', { name: 'Remove from favorites' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    // Reload the list: the favorite persisted server-side (authoritative read-back),
    // so the row still shows the favorited star after a full refresh.
    await page.reload()
    await expect(
      rowFor(page, favedTitle).getByRole('button', { name: 'Remove from favorites' }),
    ).toHaveAttribute('aria-pressed', 'true')

    // Open the favorited document — its detail header shows the favorited star.
    await rowFor(page, favedTitle).dblclick()
    await expect(page).toHaveURL(/#\/document\/view\//)
    await expect(page.getByRole('button', { name: 'Remove from favorites' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    // Back to the list and activate the Favorites filter — the URL gains favorites=me.
    await page.goto('/#/document')
    // The filter toggle lives in the filter row; scope to it so the row-level star
    // buttons ("Add/Remove from favorites") never ambiguate the "Favorites" match.
    const filterToggle = page.locator('.wf-filter-row').getByRole('button', { name: 'Favorites' })
    await filterToggle.click()
    await expect(filterToggle).toHaveAttribute('aria-pressed', 'true')
    await expect(page).toHaveURL(/favorites=me/)

    // POST-refresh barrier: only once the FILTERED response renders is the
    // non-favorited row detached AND the favorited row still present.
    await expect(rowFor(page, otherTitle)).toBeHidden()
    await expect(rowFor(page, favedTitle)).toBeVisible()

    // UNSTAR from the row (still in the favorites-filtered view). The favorites list
    // re-queries and the now-unfavorited row drops out of the filtered set.
    await rowFor(page, favedTitle).getByRole('button', { name: 'Remove from favorites' }).click()
    await expect(rowFor(page, favedTitle)).toBeHidden()

    // Authoritative read-back: reload the favorites-filtered URL directly. The
    // document is gone from the favorites view because the star was removed
    // server-side (the favorited row does not resurface after a full reload).
    await page.goto('/#/document?favorites=me')
    await expect(page.locator('.wf-filter-row').getByRole('button', { name: 'Favorites' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    await expect(rowFor(page, favedTitle)).toBeHidden()

    // And clearing the filter, both documents are visible again (neither was deleted).
    await page.goto('/#/document')
    await expect(rowFor(page, favedTitle)).toBeVisible()
    await expect(rowFor(page, otherTitle)).toBeVisible()
    // The favorited star is OFF again (the unstar persisted).
    await expect(
      rowFor(page, favedTitle).getByRole('button', { name: 'Add to favorites' }),
    ).toHaveAttribute('aria-pressed', 'false')
  } finally {
    for (const id of [favedId, otherId]) {
      if (id) await request.delete(`/api/document/${id}`).catch(() => {})
    }
  }
})
