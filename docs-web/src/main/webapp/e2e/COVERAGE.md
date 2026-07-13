# Teedy Playwright e2e coverage

Each spec drives the REAL production Docker image (embedded H2, native form login as
admin/admin) booted by `scripts/e2e-run.sh`. Specs use unique, timestamped test data
and clean up after themselves so reruns never collide. Auth: a persisted admin
`storageState` (see `global-setup.ts`); login-form / anonymous / guest specs opt out
of it per-test.

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
| `settings-crud.spec.ts` | Admin settings CRUD: groups, custom-metadata fields, webhooks, API keys, tag rules. |
| `ldap.spec.ts` | LDAP settings UI + client-side validation (no live LDAP needed). |
| `workflow.spec.ts` | **Route models + document routing**: admin builds a VALIDATE→APPROVE model (SettingsWorkflow), runs it to DONE (validate then approve), and a second run halts on REJECT (route ends REJECTED, no advance); history shows status badges + acted/rejected rows. |
| `vocabulary.spec.ts` | **Vocabulary CRUD** (SettingsVocabulary): create namespace, add/rename/reorder/delete entries; and a vocabulary value backing a document dropdown (built-in `type` namespace → `#edit-type` Select). |
| `acl.spec.ts` | **Document permissions**: admin creates a 2nd user, grants READ on a doc (DocumentViewPermissions), sees it listed, revokes it. |
| `activity.spec.ts` | **Per-document activity** (DocumentViewActivity → `/auditlog?document=<id>`): the tab shows audit rows scoped to that document, growing after a mutation, attributed to admin. |
| `guest.spec.ts` | **Guest login**: admin enables guest login via the API (`POST /app/guest_login`), the real guest button works in a clean cookie-less context (session becomes user `guest`), then guest login is disabled again in teardown. |
| `workflow-filter.spec.ts` | **"Assigned to me" filter round-trip (v3.3.0 #28)**: an API-seeded workflow (single VALIDATE step targeting USER admin) makes one doc genuinely assigned; toggling the filter puts `workflow=me` in the URL and the spec waits for the post-refresh row set (assigned visible, unassigned detached) before opening the doc; the document view's own back-link (`history.state.returnTo`) restores the filter — URL, `aria-pressed`, and the filtered row set are all re-asserted. |
| `facets.spec.ts` | **Facet children cap + overflow node (v3.3.0 #12)**: 22 mutually co-occurring tags seeded on one document; the expanded facet root shows exactly 19 interactive children plus one non-interactive "…and 2 more" overflow node (no button role); clicking the overflow changes no filter state (URL and `aria-pressed` unchanged). |
| `relations.spec.ts` | **Document relations add/remove (v3.4.0 #36)**: create documents A and B; from A's Content tab add an outgoing relation A→B via the AutoComplete; follow the new link to B IN-APP (no reload — guards the cross-document cache invalidation) and see "Linked from" A; after reload it renders on BOTH views (A under "Links to" with a remove control, B under "Linked from" read-only, no remove control on B's side); removing it from A (the last relation, `relations_reset` path) clears it from both views after reload. |
| `apikey-auth.spec.ts` | **API-key bearer auth end to end**: mint a `tdapi_` key in the real Settings UI, capture the one-time token, then from a cookie-less request context call `/api/user` with `Authorization: Bearer <token>` and read back the OWNER's identity (`anonymous:false`, username `admin`) plus a 200 on the auth-gated `/api/document/list`; negatives: no credential and a garbage token both 403 on the gated endpoint, and after the key is DELETED via the UI the same token is rejected (revocation honoured server-side). |
| `webhook-delivery.spec.ts` | **Webhook delivery OBSERVED (not just CRUD)**: an in-test HTTP listener on an ephemeral port is registered as a `DOCUMENT_CREATED` webhook (via `host.docker.internal`, reachable through the harness `--add-host` + `DOCS_WEBHOOK_ALLOW_PRIVATE=true`); creating a document drives the async `WebhookAsyncListener` POST, and the listener asserts the delivery is a POST to the registered path with the payload shape `{"event":"DOCUMENT_CREATED","id":<the created doc id>}`. The harness guarantees the topology, so a rejected registration (allow-flag dropped, alias unresolvable) FAILS the spec — no silent skip. |
| `i18n.spec.ts` | **UI language switch (de)**: the real Settings → Account language Select flips rendered strings to German (verbatim `de.json` values: Benutzerkonto / Darstellung / Passwort ändern), a reload proves persistence (localStorage `teedy-locale`), and switching back to English restores them (English present, German gone). |
| `dark-mode.spec.ts` | **Dark-mode toggle (computed style)**: the real header "Dark mode" control flips `getComputedStyle(document.body).backgroundColor` to an actually-dark value (low luminance, darker than light) and adds `.dark-mode`; a reload proves persistence (localStorage `teedy-dark-mode`, restored in `main.ts`); toggling back restores the light background exactly. |
| `ui-bundle.spec.ts` | **v3.6.0 UI bundle**: (#61) the settings admin nav renders the renamed "Personal" header plus the three labelled admin groups (Access & Users / Content Model / System) with correct membership; (#52) the items-per-page Select choice persists to localStorage `teedy_document_page_size` across a full reload; (#50) right-clicking a gallery card and choosing a tag from the context menu lands that tag on the document (authoritative `/api/document/:id` read-back); (#57) setting a custom theme name via `POST /api/theme` makes `document.title` (the browser tab) reflect it, restored in teardown. |

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
