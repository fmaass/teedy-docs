# E2E Coverage Matrix — Playwright (E1) × Browser-Harness (E2)

Teedy has two independent end-to-end layers that run against the **same** booted
image but with **different browser engines and drivers**:

- **E1 — Playwright suite** (`docs-web/src/main/webapp/e2e/*.spec.ts`): drives
  Playwright's own bundled Chromium; the primary, broad functional regression net.
- **E2 — Browser-harness** (`scripts/e2e-browser-harness.sh`): drives the locally
  installed **real Chrome over CDP** (browser-use harness); a second engine that
  certifies release identity and a curated set of high-value surfaces with a real
  interaction + assertion + screenshot per feature area.

Running both against one image gives cross-engine confidence: a regression that a
Chromium-only run masks (rendering, CDP-visible timing, real-Chrome cookie
handling) has a second chance to surface.

## Matrix

| Feature area | Playwright spec (E1) | Browser-harness check (E2) | Relationship |
|---|---|---|---|
| Release identity / version | `smoke.spec.ts` (app shell renders) | **check 1** — `/api/app current_version == 3.2.2` (hard gate) | **Complement** — only E2 asserts the exact release version and fails a stale image. |
| Native login | `auth.spec.ts` (login / bad creds / logout) | **check 2** — form login admin/admin enters the shell | **Overlap** — both verify the login form; E1 is deeper (error paths, logout). |
| Deep-link / URL hydration | `tags.spec.ts` ("URL round-trips included + excluded tag") | **check 3** — cold-load deep link retains search+tags+exclude | **Overlap** — E1 asserts filter *behavior*; E2 asserts *URL retention* after a real reload in a second engine. |
| Documents CRUD | `documents.spec.ts` (create → list → open) | **check 4** — list search surfaces the seeded document row | **Overlap** — E1 owns full CRUD; E2 re-verifies list search + render in real Chrome. |
| Tags + facets | `tags.spec.ts` ("toggles between Tree and Facets view modes") | **check 5** — Facets sidebar shows the seeded tag + a count | **Overlap** — both exercise the Facets mode; E2 asserts the seeded tag and a rendered count node. |
| Vocabulary dropdown on a document | `vocabulary.spec.ts` (admin vocabulary CRUD; a value backs the document Type Select — offered + selected, not saved/read-back) | **check 6** — open the `type` Dublin-Core Select on document-edit, pick a value, save, read back via `/api/document` (authoritative) | **Complement** — E1 proves the vocabulary editor and that a value reaches the document Select; only E2 saves it and reads the persisted value back via `/api/document`. |
| Workflow act (start / approve) | `workflow.spec.ts` (start a route, act on the current step, verify it advances) | **check 7** — start the seeded "Document review" route, ACT on the current step, verify advance via `/api/route` (authoritative) | **Overlap** — both cover a live route transition through the UI; E2 asserts the advance via the authoritative `/api/route`. |
| Admin settings › account sessions | `smoke.spec.ts` only *navigates* to `/settings/account` | **check 8** — the self-service sessions table renders the current-session row | **Complement** — E1 asserts the URL only; E2 asserts the sessions table content. |
| Admin settings › monitoring log viewer | *(none)* | **check 9** — the server-log viewer renders log rows + a total count | **Complement** — E1 has no monitoring spec; E2 is the only e2e exercising the log viewer. |
| Share anonymous view | `share.spec.ts` (create link → view anonymously → revoke) | **check 10** — anonymous visitor sees the shared document's title (context-isolated logout, admin restored after) | **Overlap** — both view a share link logged-out; E1 uses a fresh Playwright browser context, E2 achieves isolation by logging the *persistent* Chrome out via the real logout endpoint. |
| Anonymous route guard | `admin-guards.spec.ts` (non-admin bounced from admin routes) | **check 11** — logged-out deep link to `/settings/users` redirects to `/login` (context-isolated, admin restored after) | **Complement** — E1 covers the *non-admin* guard; E2 covers the *anonymous* (logged-out) guard in a second engine. |
| Admin CRUD (groups, metadata defs, webhooks, api-keys, tag rules) | `settings-crud.spec.ts` (5 tests) | *(none)* | **E1-only** — mechanical admin CRUD stays in Playwright; not duplicated in E2. |
| Comments / files / versions / trash / bulk / LDAP / search operators | `comments`, `files`, `versions`, `trash`, `bulk`, `ldap`, `search` specs | *(none)* | **E1-only** — broad functional coverage; E2 does not re-cover these. |
| **A — TOTP login (#18)** | `auth.spec.ts` — 3 tests: full valid-code login (seeds a TOTP user, computes the RFC-6238 code), wrong-code → invalid-code error, username-edit retracts the OTP field | **check 17** — TOTP challenge reveals the OTP field + a computed valid code completes login (session live via `/api/user`) | **Overlap** — both drive the real challenge→code→login flow with a server-verified code; E1 also covers the wrong-code + field-retract branches, E2 re-verifies the full login in real Chrome + authoritative session read-back. |
| **B — disabled-user enforcement (#17)** | `admin-guards.spec.ts` — 2 tests: disable via the row toggle → login denied (anonymous read-back) → re-enable → login restored; and the toggle is hidden for the guest+admin rows | **check 16** — a disabled user is denied native form login (stays on `/login`, session stays anonymous) | **Overlap** — both assert the disabled login is denied against the running server; E1 additionally proves reversibility (re-enable restores) and the guest/admin toggle-hide; E2 re-verifies the denial in real Chrome. |
| **C — filterable tag pickers, colored chips (#14/#23)** | `tags.spec.ts` — 2 tests: doc-edit MultiSelect filter winnows options + selected chip is a colored `TagBadge`; tag-edit parent Select filter winnows options | **check 13** — doc-edit MultiSelect filter winnows options AND the selected chip is a colored `TagBadge` (non-transparent background) | **Overlap** — both assert the MultiSelect filter actually winnows and the chip is colored; E1 also covers the tag-edit parent Select filter, E2 re-verifies the MultiSelect in real Chrome (computed background color). |
| **D — document-list UX (#11/#24/#25)** | `documents.spec.ts` — 3 tests: dblclick row → full view; a 5-tag doc shows a focusable `+2` control whose popover reveals the hidden tags (not repeating the first 3); admin/settings pages carry the wide-layout class | **check 14** (+N overflow popover reveals hidden tags AND is not clipped — real-Chrome layout + CDP screenshot) and **check 15** (dblclick row → `/document/view/:id`) | **Complement + Overlap** — the +N *clipping* is a genuine real-browser layout concern (the popover teleports out of the DataTable's overflow), so E2 asserts the panel rect is inside the viewport with a screenshot — value E1's jsdom-style checks cannot add; dblclick overlaps E1. The wide-settings width is **E1-only**. |

## Browser-isolation note (E2-specific)

E2 attaches to a **persistent** local Chrome, so `new_tab()` does **not** give a
clean cookie context (unlike Playwright's per-test contexts in E1). The two
anonymous checks (10, 11) therefore explicitly:

1. Log the browser **out** via the real `POST /api/user/logout` (which expires the
   httpOnly `auth_token` cookie exactly as the app does — `document.cookie` cannot
   clear an httpOnly cookie), then run their assertion as a genuine anonymous
   visitor, and
2. **Restore** the admin session via `POST /api/user/login` afterward.

The logged-out region is wrapped in `try/finally`, so the restore **always** runs
even if an anonymous check throws — a logout that is never undone would poison
every later run against the persistent Chrome. The restore is then **verified**
(check 12: `GET /api/user` must show `username=admin`, not anonymous) and the
restore login's HTTP status is checked, so a rejected restore **fails** the harness
rather than passing silently. This makes the anonymous checks order-independent and
rerun-safe, and leaves a clean logged-in state behind. Verified by fault injection:
a forced crash mid-region still restores + verifies the admin session and the run
correctly reports FAIL.

## Data hygiene (E2-specific)

Every run stamps its documents, tags, and share link with a unique token
(`bh-<epoch>-<pid>`), so reruns against a long-lived instance never collide.
Created documents are trashed at the end (best-effort). Verified rerun-green:
back-to-back invocations against the same container both pass all 11 checks.

## Realness

Every E2 row is a genuine browser action (search / open / click / act / logout)
**plus** an assertion on rendered DOM or authoritative API state (`/api/document`,
`/api/route`), **plus** a screenshot. No row merely loads a page and claims
coverage. Checks 6 and 7 assert against authoritative server state read back after
the interaction, not just the rendered page.

## Running both

```bash
# Build the current image (E1's default target), boot it, then run each layer:
./mvnw -Pprod -DskipTests clean install && docker build -t teedy-e2e:local .
docker run -d --name teedy-bh -p 8080:8080 teedy-e2e:local
# wait for /api/app to answer, then:
E2E_BASE_URL=http://localhost:8080 scripts/e2e-browser-harness.sh   # E2
# (E1 runs via the Playwright project in docs-web/src/main/webapp)
docker rm -f teedy-bh
```
