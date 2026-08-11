# Teedy Playwright e2e coverage

Each spec drives the REAL production Docker image (embedded H2, native form login as
admin/admin) booted by `scripts/e2e-run.sh`. Specs use unique, timestamped test data
and clean up after themselves so reruns never collide. Auth: a persisted admin
`storageState` (see `global-setup.ts`); login-form / anonymous / guest specs opt out
of it per-test.

## Two viewports: `desktop` + `mobile` projects

`playwright.config.ts` defines **two** projects, both driving the SAME booted app and
the SAME admin `storageState`:

- **`desktop`** — Desktop Chrome (1280×720). Runs the FULL spec set (this is the
  original behaviour; the project was renamed from `chromium`). Ignores only the
  mobile-only `responsive.spec.ts`.
- **`mobile`** — Playwright's `Pixel 5` device descriptor (393×851, touch, DPR 2.75),
  so AppLayout's `matchMedia('(max-width: 1024px)')` branch renders the PrimeVue
  Drawer + hamburger instead of the desktop side-panel. Re-runs the ENTIRE spec set
  at the mobile viewport as a **regression suite against app-wide mobile CSS
  glitches**, plus the dedicated `responsive.spec.ts`.

Running `scripts/e2e-run.sh` (no `--project`) runs BOTH projects, so CI gains the
mobile regression pass automatically. Run one with `--project=mobile` /
`--project=desktop`.

### Viewport-agnostic navigation (no spec forks)

The shared specs are NOT forked per viewport. On mobile the desktop side-panel is
replaced by a Drawer that is closed by default; the tag tree, the settings/admin nav,
and the footer links all live inside it. `e2e/helpers.ts` exposes viewport-aware
helpers that the specs route their navigation through so ONE spec works at both sizes:

- `isMobileViewport(page)` — true under the mobile project (viewport ≤ 1024px).
- `openNav(page)` — opens the mobile Drawer (no-op on desktop) and returns the live
  nav container (`aside.left-panel` on desktop, the Drawer `dialog` on mobile).
- `tagTreePanel(page)` — the `.tag-tree` scoped to that live container.
- `toggleTagFilter(page, name)` — click a tag node (re-opens the Drawer per click on
  mobile, where a select CLOSES the Drawer — the real mobile flow).
- `tagNodeState(page, name)` — read a tag node's `aria-pressed` / excluded state,
  re-opening the Drawer on mobile. The tri-state filter specs assert the URL
  (viewport-agnostic) as the primary signal and read node state via this helper.

Brand-link / footer-link / side-panel anchors that are hidden inside the closed
Drawer on mobile were replaced in the shared specs with header controls that render at
both viewports (e.g. the header **Logout** / **About** buttons) or with main-content
headings.

### Mobile exclusions / skips (with reasons)

- **`docs-screenshots.spec.ts`** — excluded from the `mobile` project (config
  `testIgnore`). It deliberately pins a fixed **1280×800** viewport to capture desktop
  marketing/doc screenshots; it is a capture tool, not a responsive test, and running
  it on mobile would fight its own `setViewportSize`. Its assertions already run on
  desktop.
- **`ui-bundle.spec.ts` #50 (gallery right-click adds a tag)** — `test.skip` on mobile
  only. A right-click / `contextmenu` has **no touch equivalent** on a mobile device
  (verified: neither a right-button click nor a dispatched `contextmenu` opens the
  quick-tag menu on Pixel 5). This is a UX affordance gap by design, not a layout bug;
  the desktop project covers it.

## Mobile responsive coverage — `responsive.spec.ts`

Runs ONLY under the `mobile` project. Functional (hard-gate) assertions at the Pixel 5
viewport, all environment-independent:

- the desktop side-panel is absent and the header hamburger is visible (exercises the
  `isMobile` branch);
- opening the Drawer reveals the nav and a nav link is reachable + inside the viewport;
- the four header action icons (#67) are all visible, hold a tappable width, and do
  not overlap in the narrow bar;
- the document slide-over (#68): a long title truncates and its bounding box does not
  overlap the (clickable) close button, both within the viewport.

The pixel-comparison for these CSS-glitch classes is owned by the **standing visual
gate** (`visual.spec.ts`) — `responsive.spec.ts` keeps only the environment-independent
functional assertions as the mobile hard gate.

## Visual-regression gate — `visual.spec.ts` (key screens × {desktop,mobile} × {en,de})

A **default-on, standing** visual gate (no `E2E_VISUAL` opt-in). It captures the six
key screens most prone to layout/overflow, each rendered in **English then German**
(German UI strings run ~30% longer — the #1 overflow cause), under **both** the
`desktop` and `mobile` projects → 4 combos/screen. Determinism: animations disabled
(config `toHaveScreenshot.animations`) + a transition/caret-killing stylesheet, and
every volatile region is `mask`ed (`.doc-meta` per-row dates, `.about-version` badge)
so a diff only ever reflects a real CSS/layout change.

Key screens covered:

1. **Document list** (`/#/document`, seeded corpus with tag chips).
2. **Gallery view** (same corpus, `teedy_document_view_mode=gallery`).
3. **Document slide-over with a long title** (the #68 truncation area).
4. **Settings hub** (`/#/settings`) and **Settings › Config form** (`/#/settings/config`).
5. **Rich-text description editor** with an ordered + an unordered list (the #70 area).
6. **About dialog** (version badge masked).

It also has three **functional German-overflow assertions** (the HARD gate, not pixel
comparison): German header action buttons stay inside the viewport, German footer nav
labels stay inside their nav container, and German settings-hub cards don't overflow —
a German label that overflows its container is a real bug.

Locale is set by seeding `localStorage['teedy-locale']` before a reload (the key
`main.ts` reads at boot), which renders the whole screen in the target locale without
a per-screen Settings click.

### Below-the-fold sight, and what the tolerance is calibrated to (#259)

**`fullPage: true` does not see below the fold in this app.** The shell is a fixed 100vh
layout and the scrolling happens *inside* `.app-content` (`AppLayout.vue`,
`overflow-y: auto`), so the page itself never exceeds the viewport: a full-page capture is
exactly 1280x720 (desktop) / 393x727 (mobile — the Pixel 5 descriptor's **viewport** height;
its 851 is `screen.height`, not the captured box). Commit `ffc31d5f` added a seventh admin
card to the settings hub and the baselines still matched byte for byte. An element
screenshot cannot rescue it either — a Playwright element shot of an `overflow: auto` box
captures its **client** box, not its `scrollHeight`.

The mechanism that works is a **taller viewport for the tests whose surface is taller than
the fold**: `growViewport()` + the `TALL_VIEWPORT` table in `visual.spec.ts` set the height
(width and device scale factor untouched) so the container no longer scrolls, and the
ordinary capture then contains the whole screen. It is applied to **settings-hub**
(1280x1600 / 393x2000) and **settings-config** (1280x2400 / 393x2700), sized from the
measured content height plus ~15% headroom — deliberately not more, because a taller frame
dilutes every diff ratio. Two checks then run **after `freeze()`, immediately before the
capture** (so they read the same layout the screenshot takes — `freeze()` waits on
`document.fonts.ready`, and a late font reflows the content):
`expectBottomInFrame()` asserts the bottom edge of the screen's structurally-last element
lies inside the viewport, and `expectSurfaceFitsViewport()` asserts `.app-content` has
nothing left to scroll. A screen that outgrows its taller viewport therefore fails **by
name** instead of silently cropping again. (`toBeVisible()` alone would not do: Playwright
calls an element visible whenever it has a box, in frame or not.) document-list and gallery keep the standard
viewport on purpose: the 4-document seed corpus fits above the fold, so the treatment would
only churn their baselines.

**The tolerance is measured, and it is tight.** `maxDiffPixelRatio` is 0.00004 (40 ppm), not
the old 0.06 — at 0.06 a reworded string passed green against a baseline showing the old
wording, which is the other half of #259. The numbers behind it (and the one per-call
opt-out, gallery at desktop) are recorded in `playwright.config.ts`; regenerate them the
same way if the environment changes: run the gate 3x at `maxDiffPixelRatio: 0` for the noise
floor, then reword one visible string / add one card, rebuild the image and re-run for the
signal.

**Byte-0 reproducibility is NOT the bar.** A regeneration that leaves a few baselines
byte-different from the previous ones is expected: headless Skia's icon-glyph anti-aliasing
(primeicons) is not byte-deterministic, and it is worst at the mobile DPR of 2.75. Measured
here: a fresh run differs from 7 of the 28 committed baselines by 1..108 raw pixels, of
which only 2 survive the per-pixel YIQ threshold of 0.2 (70 px each, gallery desktop, which
is why those two carry the per-call opt-out). So the gate is verified by
**repeated green compare runs**, never by `git diff --stat` on the PNGs — and a handful of
byte-changed baselines after a deliberate regeneration is not a defect to chase.

### Authoritative Linux baselines (the operational crux)

Playwright name-spaces baselines by OS (`*-linux.png`); **CI runs Linux, so a macOS
baseline is useless and MUST NOT be committed.** Only the `*-linux.png` files under
`e2e/visual.spec.ts-snapshots/` are authoritative and committed. A screen with no
committed Linux baseline **fails loudly** ("missing snapshot" — Playwright's default),
so a new un-baselined screen is caught; the committed baselines make CI green.

Generate/refresh the baselines with the official Playwright Docker image whose tag
**matches the repo's `@playwright/test` version** (currently `1.62.1`), pointed at the
booted RC app. The Playwright container MUST join the app container's **network
namespace** so the app is reachable as `http://localhost:8080` — Teedy's session cookie
only sticks on a `localhost` origin, so via `host.docker.internal`/a container DNS name
the post-login `GET /api/user` comes back anonymous and the form login never completes:

    # 1. Build the prod WAR and Teedy image, boot it (embedded H2, admin/admin).
    ./mvnw -Pprod -DskipTests clean install
    docker build -t teedy-visual:local .
    docker run -d --name teedy-visual-app teedy-visual:local
    # 2. Run the matching Playwright container in the app's netns, updating snapshots.
    docker run --rm --ipc=host --network container:teedy-visual-app \
      -v "$PWD/docs-web/src/main/webapp":/work -w /work \
      -e PLAYWRIGHT_BASE_URL="http://localhost:8080" -e CI=1 \
      mcr.microsoft.com/playwright:v1.62.1-jammy \
      bash -lc "npm ci && npx playwright test visual.spec --update-snapshots"
    # 3. Commit ONLY e2e/visual.spec.ts-snapshots/*-linux.png

### CI wiring — OS-consistent (the reliability crux)

Playwright baselines are renderer/font-sensitive, and the baselines above come from the
**jammy** container (Ubuntu 22.04 fonts) — NOT the GitHub `ubuntu-latest` (Noble)
runner. A host `npx playwright test` on Noble would flake the pixel diffs. So the CI
run is split by a `@visual` grep tag (on the pixel-comparison `describe` in
`visual.spec.ts`):

- **Host functional run** — `scripts/e2e-run.sh` (default) runs the whole suite on the
  runner but appends `--grep-invert @visual`, so the deterministic functional specs —
  including the three German-overflow checks in `visual.spec.ts` — run at both viewports
  as before; the pixel specs do NOT run on the mismatched-font host. This is the `e2e`
  job.
- **Visual gate** — a dedicated `e2e-visual` CI job boots the SAME RC image and runs
  `scripts/e2e-run.sh` with `E2E_VISUAL_ONLY=1`, which runs ONLY the `@visual` specs
  INSIDE the pinned jammy container (image tag derived from `package-lock.json`), joined
  to the app's netns at `http://localhost:8080` — the exact OS/font environment the
  committed baselines match. A visual diff fails this job, and the image-publish
  (`docker`) job gates on it. Default-on, real gate, no macOS/Noble baseline mismatch.

| Spec | Feature proven |
| --- | --- |
| `smoke.spec.ts` | App boots and the authenticated shell renders. |
| `auth.spec.ts` | Native form login: success, bad-credentials error, logout; **TOTP login (v3.2.2 A)** — a TOTP-enabled account is challenged (OTP field revealed), a computed valid RFC-6238 code completes login, a wrong code shows the invalid-code error, and editing the username retracts the challenge. |
| `documents.spec.ts` | Document create / view / delete; **document-list UX (v3.2.2 D)** — double-click a row opens the full view, a >3-tag document shows a focusable `+N` overflow whose popover reveals the hidden tags, and admin/settings pages render at the wider width. |
| `admin-guards.spec.ts` | Non-admin routes/redirects are guarded; **disabled-user enforcement (v3.2.2 B)** — an admin disables a user (native login then denied), re-enabling restores it, and the toggle is hidden for the guest+admin rows. |
| `tags.spec.ts` | Tag CRUD and the tri-state (include/exclude/clear) tag filter; **tag pickers (v3.2.2 C)** — the document-edit tag MultiSelect filter winnows options and renders a selected tag as a colored chip, and the tag-edit parent Select filter winnows options. |
| `files.spec.ts` | File attach + the file list on a document. |
| `versions.spec.ts` | File version-history dialog lists the current version. **Documents the product gap**: no UI path adds a *second* file version (backend supports `previousFileId`, the SPA never sets it) — that case is an explicit `test.skip`. |
| `search.spec.ts` | Full-text + structured document search. |
| `bulk.spec.ts` | Multi-select bulk actions on the document list. |
| `comments.spec.ts` | Add + delete a document comment. |
| `share.spec.ts` | Public share-link create -> anonymous read-only view -> revoke (server-side write denial asserted). |
| `trash.spec.ts` | Soft-delete to trash, restore, purge. |
| `settings-branding.spec.ts` | **Settings › Branding (#57 + #241)**: the section is reachable from the hub and the settings sidebar; an admin sets the app name (tab title follows) and replaces/resets the favicon from the UI alone; a brand colour derives the interface palette and SURVIVES a preset-family switch and a dark-mode toggle; custom CSS actually styles the page and custom JavaScript is loaded as an external script and actually executes. Resets the instance-wide theme over the API in a deferred cleanup. |
| `settings-crud.spec.ts` | Admin settings CRUD: groups, custom-metadata fields, webhooks, API keys, tag rules. |
| `ldap.spec.ts` | LDAP settings UI + client-side validation (no live LDAP needed). |
| `workflow.spec.ts` | **Route models + document routing**: admin builds a VALIDATE→APPROVE model (SettingsWorkflow), runs it to DONE (validate then approve), and a second run halts on REJECT (route ends REJECTED, no advance); history shows status badges + acted/rejected rows. |
| `vocabulary.spec.ts` | **Vocabulary CRUD** (SettingsVocabulary): create namespace, add/rename/reorder/delete entries; and a vocabulary value backing a document dropdown (built-in `type` namespace → `#edit-type` Select). |
| `acl.spec.ts` | **Document permissions**: admin creates a 2nd user, grants READ on a doc (DocumentViewPermissions), sees it listed, revokes it. |
| `activity.spec.ts` | **Per-document activity** (DocumentViewActivity → `/auditlog?document=<id>`): the tab shows audit rows scoped to that document, growing after a mutation, attributed to admin. |
| `history.spec.ts` | **Global activity history (#177)** (`HistoryView` → `/auditlog` with no `document`): the header's history button opens `/#/history`; an ADMIN's feed contains rows authored by a second user, a class filter narrows it to one entity type, "load older" keeps the filter while appending, clearing restores the wider feed, and a resolvable target navigates; a NON-ADMIN's feed contains only their own rows and NO `Acl` ("Permission") rows — unfiltered **and** behind a type filter, which is the server-side OR-composition leak this guards. |
| `guest.spec.ts` | **Guest login**: admin enables guest login via the API (`POST /app/guest_login`), the real guest button works in a clean cookie-less context (session becomes user `guest`), then guest login is disabled again in teardown. |
| `workflow-filter.spec.ts` | **"Assigned to me" filter round-trip (v3.3.0 #28)**: an API-seeded workflow (single VALIDATE step targeting USER admin) makes one doc genuinely assigned; toggling the filter puts `workflow=me` in the URL and the spec waits for the post-refresh row set (assigned visible, unassigned detached) before opening the doc; the document view's own back-link (`history.state.returnTo`) restores the filter — URL, `aria-pressed`, and the filtered row set are all re-asserted. |
| `facets.spec.ts` | **Facet children cap + overflow node (v3.3.0 #12)**: 22 mutually co-occurring tags seeded on one document; the expanded facet root shows exactly 19 interactive children plus one non-interactive "…and 2 more" overflow node (no button role); clicking the overflow changes no filter state (URL and `aria-pressed` unchanged). |
| `relations.spec.ts` | **Document relations add/remove (v3.4.0 #36)**: create documents A and B; from A's Content tab add an outgoing relation A→B via the AutoComplete; follow the new link to B IN-APP (no reload — guards the cross-document cache invalidation) and see "Linked from" A; after reload it renders on BOTH views (A under "Links to" with a remove control, B under "Linked from" read-only, no remove control on B's side); removing it from A (the last relation, `relations_reset` path) clears it from both views after reload. **Direction swap (#191)**: a second spec seeds A→B, reverses it from A's OUTGOING group and — in-app, no reload — sees the row move to "Linked from" while B gains it under "Links to" WITH the remove control (ownership of the link moved); then reverses it BACK from A's INCOMING group, which exercises the opposite argument order, and a reload confirms B is read-only incoming again. |
| `apikey-auth.spec.ts` | **API-key bearer auth end to end**: mint a `tdapi_` key in the real Settings UI, capture the one-time token, then from a cookie-less request context call `/api/user` with `Authorization: Bearer <token>` and read back the OWNER's identity (`anonymous:false`, username `admin`) plus a 200 on the auth-gated `/api/document/list`; negatives: no credential and a garbage token both 403 on the gated endpoint, and after the key is DELETED via the UI the same token is rejected (revocation honoured server-side). |
| `webhook-delivery.spec.ts` | **Webhook delivery OBSERVED (not just CRUD)**: an in-test HTTP listener on an ephemeral port is registered as a `DOCUMENT_CREATED` webhook (via `host.docker.internal`, reachable through the harness `--add-host` + `DOCS_WEBHOOK_ALLOW_PRIVATE=true`); creating a document drives the async `WebhookAsyncListener` POST, and the listener asserts the delivery is a POST to the registered path with the payload shape `{"event":"DOCUMENT_CREATED","id":<the created doc id>}`. The harness guarantees the topology, so a rejected registration (allow-flag dropped, alias unresolvable) FAILS the spec — no silent skip. |
| `i18n.spec.ts` | **UI language switch (de)**: the real Settings → Account language Select flips rendered strings to German (verbatim `de.json` values: Benutzerkonto / Darstellung / Passwort ändern), a reload proves persistence (localStorage `teedy-locale`), and switching back to English restores them (English present, German gone). |
| `dark-mode.spec.ts` | **Dark-mode toggle (computed style)**: the real header "Dark mode" control flips `getComputedStyle(document.body).backgroundColor` to an actually-dark value (low luminance, darker than light) and adds `.dark-mode`; a reload proves persistence (localStorage `teedy-dark-mode`, restored in `main.ts`); toggling back restores the light background exactly. |
| `ui-bundle.spec.ts` | **v3.6.0 UI bundle**: (#61) the settings admin nav renders the renamed "Personal" header plus the three labelled admin groups (Access & Users / Content Model / System) with correct membership; (#52) the items-per-page Select choice persists to localStorage `teedy_document_page_size` across a full reload; (#50) right-clicking a gallery card and choosing a tag from the context menu lands that tag on the document (authoritative `/api/document/:id` read-back — **desktop only**, `test.skip` on mobile: right-click has no touch equivalent); (#57) setting a custom theme name via `POST /api/theme` makes `document.title` (the browser tab) reflect it, restored in teardown. |
| `clean-storage-dry-run.spec.ts` | **Clean storage dry-run confirm (v3.6.0 #60)**: admin Config maintenance "Clean storage" first fetches the side-effect-free dry-run preview (`GET /app/batch/clean_storage/dry_run`, observed on the wire) and opens a summary confirm stating the file count + reclaim size BEFORE the real cleanup runs; accepting it POSTs `/app/batch/clean_storage` and shows the success toast. |
| `responsive.spec.ts` | **Mobile / responsive (mobile project only)**: at the Pixel 5 viewport the desktop side-panel is hidden and the header hamburger is shown (isMobile branch); opening the Drawer reveals a reachable, in-viewport nav link; the four header action icons (#67) stay visible, tappable, and non-overlapping in the narrow bar; the slide-over (#68) truncates a long title without overlapping the clickable close button. Functional hard gate only — pixel comparison is owned by `visual.spec.ts`. |
| `encoding-guardrail.spec.ts` | **Non-ASCII ingest guardrail (#143 / #207)**: a document created through the real add form and a file uploaded through the real dropzone both carry a non-ASCII name (umlauts + ß, and CJK), and EVERY surface that name reaches is compared to the source literal character for character — no normalization, no substring match. Per fixture class: the API bytes (`GET /api/document/:id` title, `GET /api/file/list` name), the download name the browser would save under (`Content-Disposition` RFC 5987 `filename*`), the file panel in BOTH modes (grid label, list cell), the list cell's native `title` tooltip (the #207 mechanism — `FileListTable.vue`, where hover is the only way to read a name the cell has ellipsized), the document-list title cell, and fulltext search driven by a non-ASCII term (`Prüfung` / `日本語`) MUST-combined with a run token, so a hit proves the non-ASCII term itself matched the index. Closes the regression-test gap #143 shipped with. |
| `first-nav-race.spec.ts` | **A navigation issued during boot wins (#216)**: every route chunk and every API call is held back 1500ms and the second navigation is fired on OBSERVING the boot's own `/api/user` request (strict — a boot that stops issuing it fails the spec), which puts it squarely inside vue-router's first-navigation window; a hash navigation AND a browser Back press each have to survive it, asserted as the ORDERED list of route roots that ever mounted, so a fix that flashes the clobbered route fails too. Each test first asserts the window was genuinely open (a chunk really delayed, the guard really fetched, no route mounted yet) — the permanent non-vacuity control. A third test fires the same navigation AFTER the boot settled and is the differential control: it passes with and without the fix. |
| `boot-server-unavailable.spec.ts` | **A transient `/api/user` failure at boot is not a sign-out (#245)**: `/api/user` is intercepted so the boot receives exactly the failure each case is about. A SINGLE 500 is retried once by the auth store and the app boots signed in on the requested route (the 500 served and the retry request are both asserted, so the interception cannot pass vacuously); a PERSISTENT 500 lands on the login shell showing the shared ErrorState with Retry and NO credential form; clicking Retry after the server recovers lands on the documents list. A second describe covers the IdP hand-off that could hide the outage: with `/api/app` rewritten to report `oidc_enabled` and `api/oidc/login` stubbed, an unavailable server must NOT auto-redirect — with a healthy-server control, booted anonymous exactly like the subject, proving the fixture really did enable OIDC. |
| `visual.spec.ts` | **Standing visual-regression + i18n gate**: the six key screens most prone to layout/overflow (document list, gallery, slide-over with a long title, settings hub + Config form, rich-text editor with ordered+unordered lists (#70), About dialog) captured in **English and German** under **both** the `desktop` and `mobile` projects (4 combos/screen) against committed `*-linux.png` baselines; volatile regions (dates, version badge) masked; animations disabled. Plus three **functional German-overflow** assertions (header buttons / nav labels / settings-hub cards stay within their container at both viewports) — the German hard gate. Default-on (no `E2E_VISUAL`); a missing Linux baseline fails loudly. |

## Route readiness — every navigation goes through a helper (#215 / #203 / #224)

`page.goto` resolves on `load`, which on a contended runner fires while vue-router's FIRST
navigation is still pending. vue-router attaches its history listener only once that
navigation finalizes, so a hash navigation issued inside that window updates `location` and
is never observed: **the URL reads as the new route while the app keeps rendering the old
one, permanently.** The next locator then times out MUTE, blaming an element instead of the
navigation that never happened. An in-app hash navigation has the sibling race — `goto` to a
same-document URL resolves the moment `location` changes, before the destination component
has mounted, so a spec that reads the destination reads the PREVIOUS route's DOM.

`e2e/helpers.ts` answers both with the same assertion — do not proceed until the URL is the
expected one AND the destination route's own root is on screen:

- **`ROUTE_ROOT`** — the route-root selector map. Each entry is that route's own root
  element, verified destination-exclusive against `src/` (a selector shared with another
  route would make readiness pass on the wrong page). Keys cover every route the suite
  navigates to: `documentList`, `documentTrash`, `documentEdit`, `documentContent`,
  `documentPermissions`, `documentWorkflow`, `documentActivity`, `documentComments`,
  `history`, `tagList`, `login`, `shareView`, and `settings*` for each settings leaf.
- **`gotoRouteReady(page, url, routeRoot)`** — navigate AND wait for that route to mount.
  The default for a spec that navigates and then acts.
- **`gotoDocumentList(page)`** — the documents list, route-ready (the most common target).
- **`expectRouteReady(page, url, routeRoot)`** — the barrier on its own. A spec that
  RELOADS re-runs the whole first-navigation sequence, so its readiness belongs AFTER the
  reload; a barrier asserted before it is discarded with the document the reload replaces.
- **`gotoRaw(page, url)`** — the sanctioned escape hatch: raw navigation, no barrier. Named
  and greppable so a deliberate raw goto is visibly deliberate. Every call site carries a
  one-line reason. Current legitimate classes: a real non-SPA path (`/apidoc/`), a nav-guard
  BOUNCE (the landing route is deliberately not the requested one), a deep link whose
  `?file=` param is consumed during mount, and the goto half of a goto-then-reload pair.

A route that REDIRECTS (`/#/document/view/:id` → `…/content`) is addressed by its
post-redirect URL, because `expectRouteReady` pins the FINAL hash.

### The guard

`eslint.config.js` bans any direct `.goto()` member call in `e2e/**/*.spec.ts`
(`no-restricted-syntax`, alongside the #187 finalizer rules). It matches the member call,
not the identifier `page`, so secondary page objects (`anon`, `userPage`, `viewer`,
`departingPage`, …) are covered too. `npm run lint` fails on a new bare goto; `helpers.ts`
and `global-setup.ts` are not spec files and stay exempt.

## Not covered by Playwright (by design)

- **OIDC / SSO login** — the CI e2e container is booted with no OIDC properties, so
  there is no OIDC flow to drive in-browser. OIDC is verified as a **deploy-time**
  check against the real OIDC-configured deployment: `scripts/oidc-deploy-smoke.sh`
  (docker-exec authoritative read-back of `/api/app` `oidc_enabled` + the
  `/api/oidc/login` authorization redirect; the client secret is never read/printed).
- **Adding a second file version** — no SPA control sets `previousFileId`; see the
  `versions.spec.ts` skip note. Product gap, not a test gap.
- **OIDC provider-binding security fix** — the login/logout flow is bound to its
  originating provider (a callback whose login state has no pinned provider is
  rejected). This has no in-browser CI coverage because the e2e container is booted
  with no OIDC config and there is no IdP to round-trip against. It is covered by the
  backend JUnit suites `TestOidcCallbackFlow`, `TestOidcProvisioning`, and
  `TestOidcTokenHardening` (docs-web/src/test/java/…), which exercise the provider
  binding directly against the resource.

## Screenshot capture (opt-in)

- `docs-screenshots.spec.ts` still SEEDS realistic data and ASSERTS the UI on every
  run, but it only WRITES the `docs/images/*.png` files when `E2E_UPDATE_SCREENSHOTS=1`.
  A normal run (and CI) leaves the working tree clean; set the env var to refresh the
  docs images.
