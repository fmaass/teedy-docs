import { test, expect, type APIRequestContext, type CleanupFixture, type Page } from './fixtures'
import {
  unique,
  uniqueTag,
  deleteDocApi,
  deleteTagApi,
  ROUTE_ROOT,
  gotoRaw,
  gotoRouteReady,
  expectRouteReady,
} from './helpers'

// #301 — the ACL target search box clips its own placeholder, MEASURED, at locale=de.
// The reporter (German UI) sees "Benutzer oder Gruppen suc" in the tag editor's Permissions
// panel: the field is narrower than the prompt it is asked to display, so the user cannot read
// what to type into it.
//
// TWO independent causes, one per implementation:
//
//  1. AclEditor.vue (tag editor + workflow model editor). PrimeVue only stretches
//     `.p-autocomplete-input` to its wrapper when the component renders a dropdown BUTTON
//     (`.p-autocomplete:has(.p-autocomplete-dropdown) .p-autocomplete-input { flex: 1 1 auto;
//     width: 1% }` — @primeuix/styles/autocomplete). This AutoComplete has no `dropdown`, so
//     the input kept the intrinsic width of a bare `<input>` — a CONSTANT 233px measured at
//     every viewport and in every locale, while the wrapper around it grew to 213px (en) /
//     254.8px (de) desktop and 297px mobile. 207px of usable content box against a German
//     placeholder that renders 239.3px wide: clipped by 32px, and no amount of room in the
//     card could reach the input.
//  2. DocumentViewPermissions.vue keeps its own add-permission row. That AutoComplete DOES
//     carry `dropdown`, so its input tracks its wrapper — but `.add-acl-row` never wrapped,
//     so at the 393px mobile viewport the fixed-width perm Select and the Add button squeezed
//     the wrapper down to 151.8px (en) / 102.8px (de), leaving 98px / 49px of content box for
//     a 145.5px / 205.5px placeholder. Worse than the reported case, and it clips in ENGLISH
//     too — which is why it is measured here rather than left to the German-only report.
//
// Why nothing else catches it: `tag-acl.spec.ts` and `workflow.spec.ts` drive the same form
// in English and only assert behaviour (fill, pick, grant), and a clipped placeholder still
// accepts input, so every functional assertion stays green; the jsdom component specs have no
// layout engine; and no visual baseline captures the tag editor, the workflow dialog or the
// document permissions tab (`visual.spec.ts-snapshots/` holds the About dialog, document list,
// gallery, rich editor, settings hub and settings config only). The check therefore has to be
// geometric, and it has to run in German.

// The German placeholders, verbatim from src/locale/de.json (ui.acl_editor.search_placeholder
// and ui.permissions.search_placeholder). Asserting them is what proves the measurement was
// taken against the GERMAN string: without it a locale switch that silently failed would
// measure the shorter English prompt and pass while the reporter's field still clipped.
const DE_ACL_PLACEHOLDER = 'Benutzer oder Gruppen suchen…'
const DE_PERM_PLACEHOLDER = 'Benutzer oder Gruppe suchen …'

interface PlaceholderFit {
  placeholder: string
  // The placeholder's rendered width, measured with a canvas primed from the input's OWN
  // computed font — not a character count, which no proportional font honours.
  textWidth: number
  // The input's CONTENT box: clientWidth is the padding box, so the horizontal padding comes
  // back off. This is the width the placeholder is actually painted into.
  contentWidth: number
  inputWidth: number
  wrapperWidth: number
  rowWidth: number
  viewportWidth: number
}

// Measure one search input against the placeholder it is asked to display. `wrapperSelector`
// addresses the flex item in the add-row (the PrimeVue AutoComplete root, or the input itself
// where the class sits directly on it); `rowSelector` its flex container.
async function measurePlaceholderFit(
  page: Page,
  wrapperSelector: string,
  rowSelector: string,
): Promise<PlaceholderFit> {
  return page.evaluate(
    ([wrapperSelector, rowSelector]) => {
      const wrapper = document.querySelector<HTMLElement>(wrapperSelector)!
      const input =
        wrapper instanceof HTMLInputElement ? wrapper : wrapper.querySelector<HTMLInputElement>('input')!
      const row = document.querySelector<HTMLElement>(rowSelector)!
      const style = getComputedStyle(input)
      const ctx = document.createElement('canvas').getContext('2d')!
      ctx.font = `${style.fontStyle} ${style.fontVariant} ${style.fontWeight} ${style.fontSize} / ${style.lineHeight} ${style.fontFamily}`
      const round = (n: number) => Math.round(n * 10) / 10
      return {
        placeholder: input.placeholder,
        textWidth: round(ctx.measureText(input.placeholder).width),
        contentWidth: round(
          input.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight),
        ),
        inputWidth: round(input.getBoundingClientRect().width),
        wrapperWidth: round(wrapper.getBoundingClientRect().width),
        rowWidth: round(row.getBoundingClientRect().width),
        viewportWidth: window.innerWidth,
      }
    },
    [wrapperSelector, rowSelector] as const,
  )
}

function expectPlaceholderFits(fit: PlaceholderFit, where: string): void {
  expect(
    fit.contentWidth,
    `${where}: the search field must display its whole placeholder "${fit.placeholder}", which ` +
      `renders ${fit.textWidth}px wide in the input's own font. The field offers ` +
      `${fit.contentWidth}px of content box (input ${fit.inputWidth}px inside a ` +
      `${fit.wrapperWidth}px wrapper, in a ${fit.rowWidth}px row at a ${fit.viewportWidth}px ` +
      `viewport), so the prompt is cut off.`,
  ).toBeGreaterThanOrEqual(fit.textWidth)
}

// Switch the UI to German and land on `url` with the locale already applied. The value is
// written BEFORE the navigation and the page is reloaded, because main.ts reads the persisted
// locale during boot — a switch made after mount would leave the field measured mid-swap.
async function gotoInGerman(page: Page, url: string): Promise<void> {
  await gotoRouteReady(page, '/#/tag', ROUTE_ROOT.tagList)
  await page.evaluate(() => localStorage.setItem('teedy-locale', 'de'))
  await gotoRaw(page, url)
  await page.reload()
}

async function seedTag(request: APIRequestContext, cleanup: CleanupFixture): Promise<string> {
  const name = uniqueTag('aclw')
  const res = await request.put('/api/tag', { form: { name, color: '#3399cc' } })
  expect(res.ok(), `create tag ${name}`).toBeTruthy()
  const id = (await res.json()).id as string
  cleanup.defer(`delete the width-probe tag ${name}`, () => deleteTagApi(request, id))
  return id
}

test.afterEach(async ({ page }) => {
  // Never leak German into the shared context. The locale persists in localStorage only here
  // (no Settings control is driven, so the server-side profile is untouched), but the mobile
  // project reruns the whole suite in the same browser and English baselines depend on it.
  await page.evaluate(() => localStorage.setItem('teedy-locale', 'en')).catch(() => {})
})

test('the tag editor permission search shows its whole German placeholder (#301)', async ({
  page,
  request,
  cleanup,
}) => {
  const tagId = await seedTag(request, cleanup)

  await gotoInGerman(page, `/#/tag/${tagId}`)
  await expect(page.locator('.acl-add')).toBeVisible()

  const fit = await measurePlaceholderFit(page, '.acl-add-target', '.acl-add')
  expect(fit.placeholder, 'the panel is rendering the GERMAN placeholder').toBe(DE_ACL_PLACEHOLDER)
  expectPlaceholderFits(fit, 'tag editor › Permissions')

  // The RCA, pinned: a dropdown-less PrimeVue AutoComplete does not stretch its own input, so
  // the input has to be made to fill its wrapper. Without this the wrapper can be widened to
  // any size and the field the user types into stays 233px.
  expect(
    fit.inputWidth,
    'the input fills its AutoComplete wrapper (PrimeVue stretches it only when a dropdown button is present)',
  ).toBeGreaterThanOrEqual(fit.wrapperWidth - 2)
})

test('the workflow model editor permission search shows its whole German placeholder (#301)', async ({
  page,
  request,
  cleanup,
}) => {
  // The same AclEditor, second consumer: SettingsWorkflow renders it for an EXISTING model, so
  // one has to exist. Seeded over the API (the editor UI would have to be driven in German).
  const modelName = unique('aclw-wf')
  const steps = JSON.stringify([
    {
      name: 'Review',
      type: 'VALIDATE',
      target: { type: 'USER', name: 'admin' },
      transitions: [{ name: 'VALIDATED', actions: [] }],
    },
  ])
  const res = await request.put('/api/routemodel', { form: { name: modelName, steps } })
  expect(res.ok(), `create route model ${modelName}`).toBeTruthy()
  const modelId = (await res.json()).id as string
  cleanup.defer('delete the width-probe route model', async () => {
    await request.delete(`/api/routemodel/${modelId}`)
  })

  await gotoInGerman(page, '/#/settings/workflow')
  await expectRouteReady(page, '/#/settings/workflow', ROUTE_ROOT.settingsWorkflow)

  // Open the model's editor. Addressed by row + action position, not by label: the labels are
  // German here and the point of the test is that the German rendering is measured.
  const row = page.getByRole('row', { name: new RegExp(modelName) })
  await expect(row).toBeVisible()
  await row.locator('.row-actions button').first().click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.locator('.acl-add')).toBeVisible()

  const fit = await measurePlaceholderFit(page, '.acl-add-target', '.acl-add')
  expect(fit.placeholder, 'the dialog is rendering the GERMAN placeholder').toBe(DE_ACL_PLACEHOLDER)
  expectPlaceholderFits(fit, 'workflow model editor › Sharing')
  expect(
    fit.inputWidth,
    'the input fills its AutoComplete wrapper',
  ).toBeGreaterThanOrEqual(fit.wrapperWidth - 2)
})

test('the document permissions search shows its whole German placeholder (#301)', async ({
  page,
  request,
  cleanup,
}) => {
  const res = await request.put('/api/document', {
    form: { title: unique('aclw-doc'), language: 'eng' },
  })
  expect(res.ok(), 'create the width-probe document').toBeTruthy()
  const docId = (await res.json()).id as string
  cleanup.defer('purge the width-probe document', () => deleteDocApi(request, docId))

  await gotoInGerman(page, `/#/document/view/${docId}/permissions`)
  await expectRouteReady(page, `/#/document/view/${docId}/permissions`, ROUTE_ROOT.documentPermissions)

  // The permissions tab holds two `.add-acl-row`s — the share-link form first, the ACL
  // typeahead second. The AutoComplete is the one that carries a `.p-autocomplete` root.
  const wrapper = '.p-autocomplete.add-acl-autocomplete'
  await expect(page.locator(wrapper)).toBeVisible()

  const fit = await measurePlaceholderFit(page, wrapper, '.add-acl-row:has(.p-autocomplete)')
  expect(fit.placeholder, 'the panel is rendering the GERMAN placeholder').toBe(DE_PERM_PLACEHOLDER)
  expectPlaceholderFits(fit, 'document view › Permissions')
})
