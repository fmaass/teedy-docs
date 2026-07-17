import { test, expect, type Page, type APIRequestContext } from './fixtures'
import { unique, createDocument, confirmDanger } from './helpers'

// Workflow (route model + document routing) end to end. Proves the admin route-model
// editor (SettingsWorkflow) AND the per-document act flow (DocumentViewWorkflow):
//   1. Admin builds a fresh 2-step model (VALIDATE -> APPROVE) whose steps are
//      assigned to a group that admin belongs to. Being a member makes admin able to
//      act on every step (route_step.transitionable is true: BaseResource.getTargetIdList
//      contains the caller's group ids) and the resolvable target makes the model
//      COMPLETE -> startable.
//   2. Run A: start the model on a doc, VALIDATE the first step, APPROVE the second
//      -> the route ends DONE and the history shows the two acted rows.
//   3. Run B (reject-halts): start the SAME model on a SECOND doc, VALIDATE, then
//      REJECT the APPROVE step -> the route ends REJECTED and does NOT advance.
//
// NOTE on the target: the built-in `admin` user and `administrators` group are
// deliberately EXCLUDED from /api/acl/target/search (SecurityUtil.skipAclCheck — they
// implicitly access everything), so they cannot be picked in the step typeahead. We
// therefore create an ordinary group, add admin to it, and target THAT group; it both
// appears in the typeahead AND makes admin transitionable. Group + docs + model are
// timestamped and removed in teardown so reruns never collide.

// Build a route model in SettingsWorkflow: one VALIDATE step then one APPROVE step,
// both assigned to `groupName` via the target typeahead. Returns after the "saved"
// toast confirms persistence and the model is listed as complete (not "Incomplete").
async function createReviewModel(page: Page, name: string, groupName: string): Promise<void> {
  await page.goto('/#/settings/workflow')
  await expect(page.getByRole('heading', { name: 'Workflows' })).toBeVisible()

  await page.getByRole('button', { name: 'New workflow' }).click()
  const dialog = page.getByRole('dialog', { name: 'New workflow' })
  await expect(dialog).toBeVisible()

  await dialog.locator('#wf-name').fill(name)

  // The editor seeds one step (a VALIDATE). Fill it, then add an APPROVE step.
  const stepCards = dialog.locator('.step-card')
  await configureStep(page, stepCards.nth(0), { name: 'Review metadata', type: 'Validate', groupName })

  await dialog.getByRole('button', { name: 'Add step' }).click()
  await expect(stepCards).toHaveCount(2)
  await configureStep(page, stepCards.nth(1), { name: 'Final approval', type: 'Approve', groupName })

  await dialog.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByText('Workflow saved')).toBeVisible()

  // The model is listed and NOT flagged incomplete (its target resolved).
  const row = page.getByRole('row', { name: new RegExp(name) })
  await expect(row).toBeVisible()
  await expect(row.getByText('Incomplete')).toHaveCount(0)
}

// Set a step's name + type and assign it to a GROUP via the target AutoComplete
// (searches /api/acl/target/search; picking the option persists name+type so the
// serialized model is complete).
async function configureStep(
  page: Page,
  card: ReturnType<Page['locator']>,
  opts: { name: string; type: 'Validate' | 'Approve'; groupName: string },
): Promise<void> {
  // PrimeVue InputText applies the passed class to the <input> itself.
  await card.locator('input.step-name').fill(opts.name)

  // Step type Select (first Select in the card).
  await card.locator('.step-field').nth(0).locator('.p-select').click()
  await page.getByRole('option', { name: opts.type, exact: true }).click()

  // Assignee type Select -> Group (second Select).
  await card.locator('.step-field').nth(1).locator('.p-select').click()
  await page.getByRole('option', { name: 'Group', exact: true }).click()

  // Assignee AutoComplete -> type the group name, pick the option.
  const targetInput = card.locator('.step-field-target input')
  await targetInput.fill(opts.groupName)
  await page.getByRole('option', { name: opts.groupName, exact: true }).click()
  await expect(targetInput).toHaveValue(opts.groupName)
}

// Delete a route model from the SettingsWorkflow list by name.
async function deleteModel(page: Page, name: string): Promise<void> {
  await page.goto('/#/settings/workflow')
  const row = page.getByRole('row', { name: new RegExp(name) })
  if ((await row.count()) === 0) return
  await row.getByRole('button', { name: 'Delete workflow' }).click()
  await confirmDanger(page)
  await expect(page.getByRole('row', { name: new RegExp(name) })).toHaveCount(0)
}

// Start the given model on the current document's Workflow tab. Reloads first so the
// route-model list query is fresh (a just-created model must appear in the picker).
async function startModel(page: Page, modelName: string): Promise<void> {
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Start a workflow' })).toBeVisible()
  await page.locator('.wf-start-select').click()
  await page.getByRole('option', { name: modelName, exact: true }).click()
  await page.getByRole('button', { name: 'Start', exact: true }).click()
  await expect(page.getByText('Workflow started')).toBeVisible()
}

test('admin builds a workflow, runs it to DONE, and a second run halts on REJECT', async ({ page, request }) => {
  const modelName = unique('wf')
  // A group admin belongs to (so admin can act on its steps) and that the target
  // typeahead will surface. Group names allow letters/digits.
  const groupName = unique('wfgrp').replace(/[^a-z0-9]/gi, '').toLowerCase()

  const docIds: string[] = []
  try {
    // Setup (inside try so the finally cleans up on any failure): create the group and
    // add admin to it (admin cookie from storageState).
    await createMemberGroup(request, groupName)

    await createReviewModel(page, modelName, groupName)

    // ---- Run A: validate then approve -> DONE ----
    const docA = await createDocument(page, unique('wf-approve'))
    docIds.push(docA.id)
    await page.goto(`/#/document/view/${docA.id}/workflow`)
    await startModel(page, modelName)

    // Step 1 is a VALIDATE step: the current step shows a Validate button.
    await expect(page.getByRole('heading', { name: 'Current step' })).toBeVisible()
    await expect(page.locator('.wf-step-name')).toHaveText('Review metadata')
    await page.getByRole('button', { name: 'Validate', exact: true }).click()

    // Step 2 is the APPROVE step: the current step advances to it (the reliable
    // signal the VALIDATE landed) and it surfaces Approve + Reject. Approve it.
    await expect(page.locator('.wf-step-name')).toHaveText('Final approval')
    await expect(page.getByRole('button', { name: 'Reject', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Approve', exact: true }).click()

    // The route is DONE: no current-step section remains, history shows the DONE badge
    // and both acted rows (Validated + Approved).
    await expect(page.getByRole('heading', { name: 'Current step' })).toHaveCount(0)
    const historyA = page.locator('.wf-route').filter({ hasText: modelName })
    await expect(historyA.getByText('Done', { exact: true })).toBeVisible()
    await expect(historyA.locator('.wf-step').filter({ hasText: 'Review metadata' }).getByText('Validated')).toBeVisible()
    await expect(historyA.locator('.wf-step').filter({ hasText: 'Final approval' }).getByText('Approved')).toBeVisible()

    // ---- Run B: validate then reject -> REJECTED, no advance ----
    const docB = await createDocument(page, unique('wf-reject'))
    docIds.push(docB.id)
    await page.goto(`/#/document/view/${docB.id}/workflow`)
    await startModel(page, modelName)

    // Validate step 1.
    await expect(page.locator('.wf-step-name')).toHaveText('Review metadata')
    await page.getByRole('button', { name: 'Validate', exact: true }).click()

    // Reject step 2 (APPROVE step) via the danger-confirm dialog.
    await expect(page.locator('.wf-step-name')).toHaveText('Final approval')
    await page.getByRole('button', { name: 'Reject', exact: true }).click()
    await confirmDanger(page)

    // The route halts REJECTED: no more current step, history shows the REJECTED badge
    // and the rejected transition. There is no third step to advance to, so the route
    // simply ends — a reject halts the whole workflow.
    await expect(page.getByRole('heading', { name: 'Current step' })).toHaveCount(0)
    const historyB = page.locator('.wf-route').filter({ hasText: modelName })
    await expect(historyB.getByText('Rejected', { exact: true }).first()).toBeVisible()
    await expect(
      historyB.locator('.wf-step').filter({ hasText: 'Final approval' }).getByText('Rejected'),
    ).toBeVisible()
  } finally {
    // Cleanup: delete the docs, the model, then the group.
    for (const id of docIds) {
      await page.goto(`/#/document/view/${id}`)
      const del = page.getByRole('button', { name: 'Delete', exact: true })
      if (await del.isVisible().catch(() => false)) {
        await del.click()
        await confirmDanger(page)
      }
    }
    await deleteModel(page, modelName)
    await request.delete(`/api/group/${groupName}`).catch(() => {})
  }
})

// #88 regression: the tag ACL editor extended the shared AclEditor (an immutable
// predicate + a grant-confirmation hook), both OPTIONAL. This proves the SettingsWorkflow
// consumer — which passes neither — still grants and revokes a route-model permission
// unchanged, with NO grant-disclosure dialog (that hook is tag-only).
test('SettingsWorkflow ACL editing still grants and revokes (AclEditor regression, #88)', async ({ page, request }) => {
  const modelName = unique('wfacl')
  const groupName = unique('wfaclgrp').replace(/[^a-z0-9]/gi, '').toLowerCase()
  try {
    await createMemberGroup(request, groupName)
    await createReviewModel(page, modelName, groupName)

    // Re-open the model editor; the Sharing section (AclEditor) renders for an existing model.
    await page.goto('/#/settings/workflow')
    await page.getByRole('row', { name: new RegExp(modelName) })
      .getByRole('button', { name: 'Edit workflow' }).click()
    const dialog = page.getByRole('dialog', { name: 'Edit workflow' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('heading', { name: 'Sharing' })).toBeVisible()

    // Grant the group READ via the AclEditor add form. The add lands DIRECTLY — no
    // disclosure dialog appears, because the tag-only beforeAdd hook is not wired here.
    const addForm = dialog.locator('.acl-add')
    await addForm.locator('input').first().fill(groupName)
    await page.getByRole('option', { name: new RegExp(groupName) }).click()
    await addForm.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(page.getByText('Permission added')).toBeVisible()
    await expect(page.getByRole('alertdialog')).toHaveCount(0)

    const aclRow = dialog.locator('.acl-row', { hasText: groupName })
    await expect(aclRow).toBeVisible()
    await expect(aclRow.getByText('Can view')).toBeVisible()

    // Revoke it: the danger confirm removes the grant from the list.
    await aclRow.getByRole('button', { name: 'Remove permission' }).click()
    await confirmDanger(page)
    await expect(page.getByText('Permission removed')).toBeVisible()
    await expect(dialog.locator('.acl-row', { hasText: groupName })).toHaveCount(0)

    // Close the editor so its modal mask does not block the list actions in cleanup.
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog).toBeHidden()
  } finally {
    await deleteModel(page, modelName)
    await request.delete(`/api/group/${groupName}`).catch(() => {})
  }
})

// Create a group and add admin to it via the admin API. This is setup plumbing for
// the workflow target, not the surface under test — the workflow editor is.
async function createMemberGroup(request: APIRequestContext, name: string): Promise<void> {
  const createRes = await request.put('/api/group', { form: { name } })
  expect(createRes.ok(), 'create workflow target group').toBeTruthy()
  const addRes = await request.put(`/api/group/${name}`, { form: { username: 'admin' } })
  expect(addRes.ok(), 'add admin to workflow target group').toBeTruthy()
}
