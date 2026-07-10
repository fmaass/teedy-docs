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

## Not covered by Playwright (by design)

- **OIDC / SSO login** — the CI e2e container is booted with no OIDC properties, so
  there is no OIDC flow to drive in-browser. OIDC is verified as a **deploy-time**
  check against the real OIDC-configured deployment: `scripts/oidc-deploy-smoke.sh`
  (docker-exec authoritative read-back of `/api/app` `oidc_enabled` + the
  `/api/oidc/login` authorization redirect; the client secret is never read/printed).
- **Adding a second file version** — no SPA control sets `previousFileId`; see the
  `versions.spec.ts` skip note. Product gap, not a test gap.
