import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { unique } from './helpers'

// #28: the "Assigned to me" (workflow=me) document-list filter must round-trip
// through the URL — toggling it activates the filter and puts `workflow=me` in the
// URL; opening a document and using the IN-APP Back affordance (the returnTo
// history-state mechanism under test, NOT browser history) restores the filter.
//
// DETERMINISM (this spec was previously guard-raced): the document under test IS
// assigned to the logged-in admin — a single-step VALIDATE workflow targeting USER
// "admin" is started on it via the API, so the filter KEEPS its row visible. A
// second, unassigned document proves the filter actually filters. After toggling,
// the spec waits for the POST-refresh list state (unassigned row detached AND
// assigned row still present) before interacting with any row: the pre-refresh
// render shows BOTH rows, so the "unassigned hidden" assertion can only pass once
// the filtered response has rendered — no interaction can race the refresh.
//
// REALNESS: aria-pressed on the PrimeVue ToggleButton, the workflow=me URL param,
// and the row set are the assertions; the Back step clicks the document view's
// own back-link (router.push(returnTo)), so a dropped workflow key in returnTo or
// a missing route hydration fails the spec.

function rowFor(page: Page, title: string) {
  return page.getByRole('row', {
    name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  })
}

// Create a document via the API (fast seeding; the UI form is not under test here).
async function apiCreateDocument(request: APIRequestContext, title: string): Promise<string> {
  const res = await request.put('/api/document', { form: { title, language: 'eng' } })
  expect(res.ok(), `create document ${title}`).toBeTruthy()
  return (await res.json()).id as string
}

// A minimal single-step VALIDATE workflow whose step targets USER admin directly —
// the strict wire shape RouteModelResource.validateRouteModelSteps requires.
async function apiCreateRouteModel(request: APIRequestContext, name: string): Promise<string> {
  const steps = JSON.stringify([
    {
      name: 'Review',
      type: 'VALIDATE',
      target: { type: 'USER', name: 'admin' },
      transitions: [{ name: 'VALIDATED', actions: [] }],
    },
  ])
  const res = await request.put('/api/routemodel', { form: { name, steps } })
  expect(res.ok(), 'create route model').toBeTruthy()
  return (await res.json()).id as string
}

test('the "Assigned to me" filter round-trips through open + in-app Back (#28)', async ({ page, request }) => {
  const assignedTitle = unique('wf-assigned')
  const otherTitle = unique('wf-other')
  const modelName = unique('wfm')
  let assignedId: string | undefined
  let otherId: string | undefined
  let modelId: string | undefined

  try {
    // Seed: two docs; a workflow targeting USER admin started on the first, so it
    // is "assigned to me" for the logged-in admin and the filter keeps it visible.
    assignedId = await apiCreateDocument(request, assignedTitle)
    otherId = await apiCreateDocument(request, otherTitle)
    modelId = await apiCreateRouteModel(request, modelName)
    const startRes = await request.post('/api/route/start', {
      form: { routeModelId: modelId, documentId: assignedId },
    })
    expect(startRes.ok(), 'start route on assigned doc').toBeTruthy()

    await page.goto('/#/document')
    const assignedRow = rowFor(page, assignedTitle)
    const otherRow = rowFor(page, otherTitle)
    // Unfiltered list: both rows render.
    await expect(assignedRow).toBeVisible()
    await expect(otherRow).toBeVisible()

    // Activate the filter — the URL gains workflow=me.
    const toggle = page.getByRole('button', { name: 'Assigned to me' })
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-pressed', 'true')
    await expect(page).toHaveURL(/workflow=me/)

    // POST-refresh barrier: the unassigned row is only detached once the FILTERED
    // response has rendered (pre-refresh it is visible), and the assigned row
    // must survive it. Only after this settles do we touch a row.
    await expect(otherRow).toBeHidden()
    await expect(assignedRow).toBeVisible()

    // Open the assigned document — its row is in the filtered set, so no pending
    // refresh can detach it mid-dblclick.
    await assignedRow.dblclick()
    await expect(page).toHaveURL(/#\/document\/view\//)

    // IN-APP Back: the document view's back-link pushes history.state.returnTo —
    // the exact #28 mechanism (browser goBack would bypass it).
    await page.locator('.back-link').click()
    await expect(page).toHaveURL(/workflow=me/)
    await expect(page.getByRole('button', { name: 'Assigned to me' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    // And the restored filter is LIVE, not just cosmetic: the filtered row set holds.
    await expect(rowFor(page, assignedTitle)).toBeVisible()
    await expect(rowFor(page, otherTitle)).toBeHidden()
  } finally {
    // Cleanup via API (delete cancels the doc's route steps server-side).
    for (const id of [assignedId, otherId]) {
      if (id) await request.delete(`/api/document/${id}`).catch(() => {})
    }
    if (modelId) await request.delete(`/api/routemodel/${modelId}`).catch(() => {})
  }
})
