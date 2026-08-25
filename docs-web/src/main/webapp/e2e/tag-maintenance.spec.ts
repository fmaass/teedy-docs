import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type CleanupFixture,
  type Locator,
  type Page,
} from './fixtures'
import {
  unique,
  uniqueTag,
  isMobileViewport,
  confirmDanger,
  deleteDocApi,
  deleteTagApi,
  deleteUserApi,
  expectResponseOk,
  gotoRouteReady,
  login,
  ROUTE_ROOT,
} from './helpers'

// #298 parts 1 and 2 — removing unused tags from the tag-management tree.
//
// The feature is one safety property with a UI on top: a tag may be deleted only when its ENTIRE
// subtree carries no document, and nothing is ever un-assigned from a document to make a tag
// deletable ("as long as tags are sticking to any doc, do not delete them generally" — the
// reporter, #298). Part 1 is the per-node action; part 2 is the instance-wide sweep, which must
// PREVIEW before it deletes and REPORT what it deleted.
//
// PREMISE, fully self-constructed. Every tag and document this spec reasons about is seeded by
// this spec, through a FRESH account's own session, and the assertions are counts and set
// membership over that account's tree. That matters more here than in most specs: the cleanup
// action is instance-wide over what the caller can see, so run against the shared admin account it
// would sweep up whatever unused tags earlier specs left behind — the assertion "exactly these
// three went" would be a statement about suite order, and the sweep itself would delete other
// specs' fixtures. Teedy's tag list is ACL-scoped with no admin bypass (TagResource#list ->
// getTargetIdList), so a brand-new account's tree contains exactly what is seeded below.

// The seeded tree. One used branch that must survive everything, one unused branch that goes
// whole, and one unused leaf, so "only the unused went" is a real set comparison and not a
// single-item check.
interface Seeded {
  stem: string
  used: string
  usedChild: string
  empty: string
  emptyChild: string
  lonely: string
}

const SEED_USER_PASSWORD = 'TagMaint123'

async function apiCreateTag(request: APIRequestContext, name: string, parentId?: string): Promise<string> {
  const form: Record<string, string> = { name, color: '#3399cc' }
  if (parentId) form.parent = parentId
  const res = await request.put('/api/tag', { form })
  await expectResponseOk(res, `create tag ${name}`)
  return (await res.json()).id as string
}

/**
 * Seed the tree through `request`'s session, registering every teardown on that same context.
 * The tag deletions are registered even though the test body deletes most of them: deleteTagApi
 * confirms an already-gone tag against the tag list rather than trusting a status code, so a
 * body that deleted them is not an error — and a body that FAILED to leaves nothing behind.
 */
async function seed(request: APIRequestContext, cleanup: CleanupFixture): Promise<Seeded> {
  // All five names share one stem, which is what lets the tree filter reveal the whole seeded
  // forest in one keystroke (see revealSeededTree). The derived suffixes stay inside uniqueTag's
  // 3-character budget, so every name clears the server's 36-character cap.
  //
  // Every name carries a suffix of the SAME length, none is bare: several assertions below are
  // "this list does not mention that tag", and a bare stem is a substring of every other name, so
  // such an assertion could never fail — it would pass on a preview that listed the used branch.
  const stem = uniqueTag('tmn')
  const names = {
    used: `${stem}-a`,
    usedChild: `${stem}-b`,
    empty: `${stem}-c`,
    emptyChild: `${stem}-d`,
    lonely: `${stem}-e`,
  }

  const usedId = await apiCreateTag(request, names.used)
  cleanup.defer(`delete the tag ${names.used}`, () => deleteTagApi(request, usedId))
  const usedChildId = await apiCreateTag(request, names.usedChild, usedId)
  cleanup.defer(`delete the tag ${names.usedChild}`, () => deleteTagApi(request, usedChildId))
  const emptyId = await apiCreateTag(request, names.empty)
  cleanup.defer(`delete the tag ${names.empty}`, () => deleteTagApi(request, emptyId))
  const emptyChildId = await apiCreateTag(request, names.emptyChild, emptyId)
  cleanup.defer(`delete the tag ${names.emptyChild}`, () => deleteTagApi(request, emptyChildId))
  const lonelyId = await apiCreateTag(request, names.lonely)
  cleanup.defer(`delete the tag ${names.lonely}`, () => deleteTagApi(request, lonelyId))

  // The ONE document in the fixture, and it hangs off the DEEP child — the whole point of the
  // subtree rule is that a document down there protects the empty parent above it too.
  const docTitle = unique('tmndoc')
  const docRes = await request.put('/api/document', {
    form: { title: docTitle, tags: usedChildId, language: 'eng' },
  })
  await expectResponseOk(docRes, `create the document ${docTitle}`)
  const docId = (await docRes.json()).id as string
  cleanup.defer('purge the seeded document', () => deleteDocApi(request, docId))

  return { stem, ...names }
}

async function createSeedUser(baseURL: string): Promise<string> {
  // unique() mints separators the username field rejects; stripping them keeps the per-worker
  // uniqueness (timestamp + pid + counter) inside the server's 50-character cap.
  const username = unique('tmnuser').replace(/[^a-z0-9]/gi, '').toLowerCase()
  const admin = await pwRequest.newContext({ baseURL })
  const adminLogin = await admin.post('/api/user/login', {
    form: { username: 'admin', password: 'admin', remember: false },
  })
  await expectResponseOk(adminLogin, 'admin login for the tag-maintenance user seed')
  const created = await admin.put('/api/user', {
    form: {
      username,
      password: SEED_USER_PASSWORD,
      email: `${username}@example.com`,
      storage_quota: 1_000_000_000,
    },
  })
  await expectResponseOk(created, `create the tag-maintenance user ${username}`)
  await admin.dispose()
  return username
}

async function deleteSeedUser(baseURL: string, username: string): Promise<void> {
  const admin = await pwRequest.newContext({ baseURL })
  const adminLogin = await admin.post('/api/user/login', {
    form: { username: 'admin', password: 'admin', remember: false },
  })
  await expectResponseOk(adminLogin, 'admin login for the tag-maintenance user teardown')
  await deleteUserApi(admin, username)
  await admin.dispose()
}

async function openSeedUserRequest(baseURL: string, username: string): Promise<APIRequestContext> {
  const asUser = await pwRequest.newContext({ baseURL })
  const userLogin = await asUser.post('/api/user/login', {
    form: { username, password: SEED_USER_PASSWORD, remember: false },
  })
  await expectResponseOk(userLogin, `log the tag-maintenance user ${username} in`)
  return asUser
}

/**
 * Everything a test needs before it can act: a throwaway account, its seeded tree, and a browser
 * session logged into it. Teardown order is registration order (the cleanup fixture is FIFO), so
 * the tag/document deletions registered inside `seed` run FIRST, while that session is alive;
 * only then is the account removed and only then is the context disposed.
 */
async function seedAccountAndLogIn(
  page: Page,
  baseURL: string,
  cleanup: CleanupFixture,
): Promise<Seeded> {
  const username = await createSeedUser(baseURL)
  const asUser = await openSeedUserRequest(baseURL, username)
  const seeded = await seed(asUser, cleanup)
  cleanup.defer('delete the tag-maintenance user', () => deleteSeedUser(baseURL, username))
  cleanup.defer('dispose the tag-maintenance API context', () => asUser.dispose())
  await login(page, username, SEED_USER_PASSWORD)
  return seeded
}

/**
 * Type the shared stem into the tree's own filter box. That both narrows the tree to the seeded
 * forest and force-expands every parent (#279 wired the filter to expansion), which is the only
 * way a CHILD node is on screen — the tree opens collapsed.
 */
async function revealSeededTree(page: Page, stem: string): Promise<void> {
  await page.locator(`${ROUTE_ROOT.tagList} .tag-tree input`).fill(stem)
  await expect(node(page, `${stem}-d`), 'the filter revealed the nested nodes').toBeVisible()
}

/** The tree node whose label is exactly `name`. */
function node(page: Page, name: string): Locator {
  return page.locator(`${ROUTE_ROOT.tagList} .tag-node`).filter({
    has: page.locator('.tag-label', { hasText: new RegExp(`^${name}$`) }),
  })
}

/** The labels of every tag node currently rendered in the management tree. */
async function renderedLabels(page: Page): Promise<string[]> {
  return page.locator(`${ROUTE_ROOT.tagList} .tag-node .tag-label`).allInnerTexts()
}

async function gotoTagManagement(page: Page, stem: string): Promise<void> {
  await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
  await revealSeededTree(page, stem)
}

// The seeding is five sequential tag creates, a document create and a real form login before the
// page is even opened, and the account is torn down afterwards.
const SEED_TIMEOUT = 60_000

test.describe('tag maintenance, on an isolated account', () => {
  // Cleared so the shared admin session cannot log the browser in first; each test authenticates
  // through the real form as its own seeded user.
  test.use({ storageState: { cookies: [], origins: [] } })

  test('deletes an unused subtree whole and never offers the delete on a used branch (#298)', async ({
    page,
    baseURL,
    cleanup,
  }) => {
    test.setTimeout(SEED_TIMEOUT)
    const seeded = await seedAccountAndLogIn(page, baseURL!, cleanup)
    await gotoTagManagement(page, seeded.stem)

    // PREMISE: all five seeded tags are on screen, so every "gone" assertion below is a real
    // disappearance rather than a node that was never rendered.
    expect(
      (await renderedLabels(page)).sort(),
      'the seeded forest is fully rendered before anything is deleted',
    ).toEqual([seeded.empty, seeded.emptyChild, seeded.lonely, seeded.used, seeded.usedChild].sort())

    // The used branch: the document sits on the DEEP child, and that has to protect the empty
    // parent above it as well. Both refuse, and the parent's refusal quotes the count that
    // explains it — the reason is the point, a greyed-out button with no explanation is not.
    const usedDelete = node(page, seeded.used).locator('.tag-delete-btn')
    const usedChildDelete = node(page, seeded.usedChild).locator('.tag-delete-btn')
    await expect(usedDelete, 'an unused parent above a used child offers no delete').toBeDisabled()
    await expect(usedChildDelete, 'the used tag itself offers no delete').toBeDisabled()
    // The FULL reason string, not a loose /1/: the seeded names are base-36 and can contain a
    // digit 1 of their own, and the other two reasons ("cannot edit", "status unavailable") would
    // otherwise be indistinguishable from the one this case is about.
    await expect(usedDelete).toHaveAttribute('title', 'Documents in this branch: 1')

    // The unused branch goes, root and child together, after an explicit confirm.
    const emptyDelete = node(page, seeded.empty).locator('.tag-delete-btn')
    await expect(emptyDelete, 'a fully unused branch offers the delete').toBeEnabled()
    await emptyDelete.click()
    // The prompt names the tag and says the branch is two tags, so nobody removes a subtree
    // thinking they clicked one tag.
    const prompt = page.getByRole('alertdialog')
    await expect(prompt).toBeVisible()
    await expect(prompt).toContainText(seeded.empty)
    // The sentence, not a bare '2' — the tag name in the same prompt is base-36 and may carry one.
    await expect(prompt).toContainText('Tags to be deleted: 2')
    await confirmDanger(page)

    // The report names what went — the acceptance criterion for a destructive action. Read off
    // the toast rather than the page, so a node the tree has not re-rendered away yet cannot
    // stand in for the report.
    const toast = page.locator('.p-toast-message')
    await expect(toast, 'the report names the root that was deleted').toContainText(seeded.empty)
    await expect(toast, 'and the child that went with it').toContainText(seeded.emptyChild)

    await expect
      .poll(async () => (await renderedLabels(page)).sort(), {
        message: 'only the unused branch went; the used one and the untouched leaf stayed',
      })
      .toEqual([seeded.lonely, seeded.used, seeded.usedChild].sort())
  })

  // A right-click has no touch equivalent, so the mobile project cannot reach this menu the way a
  // user would (measured on Pixel 5 for the same menu in tag-menu-geometry.spec.ts, and recorded
  // in e2e/COVERAGE.md). The row button covers the action at both viewports; only the menu is
  // desktop-only, and asserting it through an event no touch device can produce would be
  // measuring the harness rather than the product.
  test('offers the same delete from the right-click menu, and states why it cannot (#298)', async ({
    page,
    baseURL,
    cleanup,
  }) => {
    test.skip(isMobileViewport(page), 'right-click/contextmenu is a desktop-only pointer affordance')
    test.setTimeout(SEED_TIMEOUT)
    const seeded = await seedAccountAndLogIn(page, baseURL!, cleanup)
    await gotoTagManagement(page, seeded.stem)

    // On a used branch the command is offered but refused, with the reason spelled out beside it —
    // a greyed-out command with no explanation is what made the old per-document menu confusing.
    await node(page, seeded.used).click({ button: 'right' })
    const menu = page.locator('.p-contextmenu')
    await expect(menu).toBeVisible()
    await expect(menu).toContainText('Delete unused tag')
    await expect(
      menu,
      'the menu says how many documents hold the branch back, not just that it cannot go',
    ).toContainText('Documents in this branch: 1')

    // A click elsewhere puts the menu away. This is the page's OWN dismissal, not PrimeVue's:
    // measured on primevue 4.5.5, the component's outside-click listener leaves this menu open
    // (a capture-phase document listener sees the click, the panel stays), so the assertion is
    // the one that pins the added handler.
    await page.locator(`${ROUTE_ROOT.tagList} .page-header h1`).click()
    await expect(menu, 'a click outside the menu closes it').toBeHidden()

    // On an unused one it works, through the same confirm as the row button.
    await node(page, seeded.lonely).click({ button: 'right' })
    await expect(menu).toBeVisible()
    await menu.getByText('Delete unused tag', { exact: true }).click()
    await confirmDanger(page)

    await expect
      .poll(async () => (await renderedLabels(page)).includes(seeded.lonely), {
        message: 'the tag deleted from the context menu left the tree',
      })
      .toBe(false)
  })

  test('previews the unused tags, deletes them only on confirm, and reports what went (#298)', async ({
    page,
    baseURL,
    cleanup,
  }) => {
    test.setTimeout(SEED_TIMEOUT)
    const seeded = await seedAccountAndLogIn(page, baseURL!, cleanup)
    await gotoTagManagement(page, seeded.stem)

    await page.locator('.tag-cleanup-btn').click()
    const dialog = page.getByRole('dialog', { name: 'Unused tags' })
    await expect(dialog).toBeVisible()

    // PREVIEW. Only the two unused ROOTS are listed — a root stands for its whole branch, so the
    // unused child is not a row of its own, and the used branch appears nowhere.
    const rows = dialog.locator('.cleanup-row')
    await expect(rows).toHaveCount(2)
    const previewed = (await rows.allInnerTexts()).join('\n')
    expect(previewed).toContain(seeded.empty)
    expect(previewed).toContain(seeded.lonely)
    expect(previewed, 'a used branch is never previewed').not.toContain(seeded.used)
    expect(previewed, 'a branch root stands for its descendants').not.toContain(seeded.emptyChild)

    // …and looking deleted nothing: the tree behind the dialog is untouched.
    expect(
      (await renderedLabels(page)).sort(),
      'the preview is read-only',
    ).toEqual([seeded.empty, seeded.emptyChild, seeded.lonely, seeded.used, seeded.usedChild].sort())

    // CONFIRM. The button says how many tags go before it is pressed.
    const confirmButton = dialog.locator('.cleanup-confirm-btn')
    await expect(confirmButton).toContainText('Delete (3)')
    await confirmButton.click()

    // REPORT: every deleted tag named, with the count.
    const result = dialog.locator('.cleanup-result')
    await expect(result).toBeVisible()
    // The count sentence: the listed paths are base-36 and can contain a 3 by themselves.
    await expect(result).toContainText('Tags deleted: 3')
    await expect(result).toContainText(seeded.empty)
    await expect(result).toContainText(seeded.emptyChild)
    await expect(result).toContainText(seeded.lonely)

    // The footer's own Close, not the Dialog header's close icon (both carry the accessible
    // name "Close").
    await dialog.locator('.cleanup-close-btn').click()
    await expect(dialog).toBeHidden()

    await expect
      .poll(async () => (await renderedLabels(page)).sort(), {
        message: 'the sweep took every unused tag and left the used branch standing',
      })
      .toEqual([seeded.used, seeded.usedChild].sort())

    // A second sweep has nothing left to do and says so rather than offering a delete of nothing.
    await page.locator('.tag-cleanup-btn').click()
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('.cleanup-empty')).toBeVisible()
    await expect(dialog.locator('.cleanup-confirm-btn')).toBeDisabled()
  })
})
