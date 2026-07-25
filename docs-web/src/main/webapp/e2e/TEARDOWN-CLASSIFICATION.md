# e2e teardown classification (#187)

Every `finally` block in `e2e/*.spec.ts`, classified individually, as the phase requires. The point of
the exercise: restricting the migration to the eight `deleteDoc` files would have left the identical
hazard — a teardown that throws and replaces the body's real exception — in `activity`, `workflow`,
`two-factor`, `auth`, `guest` and a dozen more.

## Measurement (re-measured at phase start, not taken from the plan)

```
$ grep -oE 'finally \{' e2e/*.spec.ts | wc -l
88
$ grep -lE 'finally \{' e2e/*.spec.ts | wc -l
43
$ grep -oE 'deleteDoc\(' e2e/*.spec.ts | wc -l
36
```

88 blocks across 43 spec files — identical to the plan-time count. The 36 `deleteDoc(` occurrences are
**28 call sites plus 8 local `async function deleteDoc(...)` declarations** (one per file in
`cover`, `dedup-hint`, `file-panel`, `move`, `nullname`, `pdf-organizer`, `revision-upload`,
`share-files`); the classification below counts **blocks**, not occurrences. No block is nested inside
another `finally`, and none sits inside a `test.step` or a helper function — all 88 are top-level
`try/finally` wrappers around a test body.

## Result

| classification | blocks |
|---|---|
| teardown-capable — **migrated** to `cleanup.defer` | **88** |
| teardown-capable — kept in `finally` via the guarded wrapper | **0** |
| genuinely unrelated — no teardown, left as-is | **0** |
| **total** | **88** |

**Every one of the 88 blocks performs teardown.** There is no block that merely wraps a body in
`try/finally` without cleaning something up, so the "unrelated" bucket is empty and the post-phase
re-audit of that bucket has nothing to check. The guarded-wrapper bucket is empty too: no spec teardown
turned out to need the finalizer, because a `cleanup.defer` callback can run UI code just as well as API
code. The wrapper (`guardedTeardown`, `e2e/helpers.ts`) is used once outside the specs, in
`e2e/global-setup.ts`, where no fixture exists.

The 88 blocks became **141 deferred steps** — the blocks that cleaned up several entities were split
so one broken step cannot abort the rest. By teardown call:

| deferred step | count |
|---|---|
| `deleteDocApi` (trash **and** purge) | 74 |
| `deleteTagByNameApi` | 11 |
| `deleteUserApi` | 10 |
| `deleteTagApi` | 7 |
| context / API-context / http-server disposal (`close()`, `dispose()`) | 11 |
| everything else, moved verbatim (config restores, metadata, webhooks, route models, groups, vocabulary, `page.unroute`, UI teardown with no API path) | 28 |
| **total** | **141** |

(74 > the 52 document blocks because a block that purged two documents becomes two independent steps,
and because `bulk.spec.ts` — which had no teardown at all — contributed two more.)

## Per-block classification

Line numbers are the `finally` keyword's line in the **pre-migration** tree (`main` @ `9946787d`).

### Document teardown

| block | teardown it ran | classification |
|---|---|---|
| `activity.spec.ts:133` | `removeDoc` (UI delete) | migrated → `deleteDocApi` |
| `activity.spec.ts:151` | `removeDoc` (UI delete) | migrated → `deleteDocApi` |
| `activity.spec.ts:184` | `removeDoc` (UI delete) | migrated → `deleteDocApi` |
| `activity.spec.ts:221` | `removeDoc` (UI delete) | migrated → `deleteDocApi` |
| `activity.spec.ts:64` | UI delete of a target + a decoy document | migrated → `deleteDocApi` ×2 |
| `activity.spec.ts:250` | `page.unroute` + UI document delete | migrated → 2 defers |
| `cover.spec.ts:102` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `dedup-hint.spec.ts:54` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `documents.spec.ts:132` | API document + tag deletes | migrated → `deleteDocApi` + `deleteTagApi` |
| `download-export.spec.ts:82` | `trashDoc` — **trash only, never purged** | migrated → `deleteDocApi` |
| `download-export.spec.ts:102` | `trashDoc` — trash only | migrated → `deleteDocApi` |
| `download-export.spec.ts:137` | `trashDoc` ×2 — trash only | migrated → `deleteDocApi` ×2 |
| `download-export.spec.ts:161` | `trashDoc` — trash only | migrated → `deleteDocApi` |
| `facets.spec.ts:99` | API document + tags, `.catch` swallowed | migrated, swallow dropped |
| `favorites.spec.ts:105` | API deletes of two documents, swallowed | migrated, swallow dropped |
| `file-panel.spec.ts:71` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `file-panel.spec.ts:91` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `file-panel.spec.ts:124` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `file-panel.spec.ts:155` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `file-panel.spec.ts:182` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `file-panel.spec.ts:229` | document + a second user | migrated → `deleteDocApi` + `deleteUserApi` |
| `file-panel.spec.ts:301` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `gallery.spec.ts:177` | API deletes of two documents, swallowed | migrated, swallow dropped |
| `gallery.spec.ts:238` | two documents + a tag, swallowed | migrated, swallow dropped |
| `gallery.spec.ts:277` | two documents, swallowed | migrated, swallow dropped |
| `move.spec.ts:119` | local `deleteDoc` ×2 (UI) | migrated → `deleteDocApi` ×2 |
| `nullname.spec.ts:75` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `nullname.spec.ts:93` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `nullname.spec.ts:118` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `pdf-organizer.spec.ts:74` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `pdf-organizer.spec.ts:129` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `pdf-organizer.spec.ts:163` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `pdf-organizer.spec.ts:201` | document + a second user | migrated → `deleteDocApi` + `deleteUserApi` |
| `relations.spec.ts:89` | UI delete of two documents | migrated → `deleteDocApi` ×2 |
| `revision-upload.spec.ts:99` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `revision-upload.spec.ts:121` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `revision-upload.spec.ts:143` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `revision-upload.spec.ts:162` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `revision-upload.spec.ts:183` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `revision-upload.spec.ts:211` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `revision-upload.spec.ts:237` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `revision-upload.spec.ts:257` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `revision-upload.spec.ts:291` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `rich-description.spec.ts:70` | API document delete | migrated → `deleteDocApi` |
| `rich-description.spec.ts:111` | API document delete | migrated → `deleteDocApi` |
| `share-files.spec.ts:88` | local `deleteDoc` (UI) | migrated → `deleteDocApi` |
| `slide-over-delete.spec.ts:65` | UI document delete + UI user delete | migrated → `deleteDocApi` + `deleteUserApi` |
| `stats.spec.ts:64` | API document delete, swallowed | migrated, swallow dropped |
| `tag-add-focus.spec.ts:70` | API document + tag, swallowed | migrated, swallow dropped |
| `tag-add-focus.spec.ts:98` | API document + tag, swallowed | migrated, swallow dropped |
| `tag-chips.spec.ts:85` | two documents + a tag, swallowed | migrated, swallow dropped |
| `ui-bundle.spec.ts:65` | API document delete, swallowed | migrated, swallow dropped |
| `ui-bundle.spec.ts:115` | API document + tag, swallowed | migrated, swallow dropped |
| `user-reassign.spec.ts:128` | context close + UI delete of the reassigned document | migrated → 2 defers |
| `versions.spec.ts:38` | UI document delete | migrated → `deleteDocApi` |
| `vocabulary.spec.ts:144` | document + metadata field + vocabulary purge | migrated → 3 defers |
| `vocabulary.spec.ts:180` | UI document delete + UI vocabulary-entry delete | migrated → 2 defers (UI code kept) |
| `webhook-delivery.spec.ts:119` | node server close + document + webhook | migrated → 3 defers |
| `workflow-filter.spec.ts:106` | two documents + a route model | migrated → 3 defers |
| `workflow.spec.ts:167` | UI deletes of N documents + route model + group | migrated → defers |
| `acl.spec.ts:57` | UI document delete + UI user delete | migrated → `deleteDocApi` + `deleteUserApi` |
| `tag-acl.spec.ts:123` | context close + document + tag + user | migrated → 4 defers |

### User teardown

| block | teardown it ran | classification |
|---|---|---|
| `admin-guards.spec.ts:49` | UI `deleteUser` | migrated → `deleteUserApi` |
| `admin-guards.spec.ts:133` | UI `deleteUser` | migrated → `deleteUserApi` |
| `admin-guards.spec.ts:173` | UI `deleteUser` | migrated → `deleteUserApi` |
| `auth.spec.ts:140` | local `deleteUser` — **no reassign target, no assertion** | migrated; reassign target + success assertion added |
| `auth.spec.ts:167` | local `deleteUser` — same defect | migrated; fixed |
| `auth.spec.ts:185` | local `deleteUser` — same defect | migrated; fixed |
| `settings-hub.spec.ts:75` | UI `deleteUser` | migrated → `deleteUserApi` |
| `stats.spec.ts:104` | raw `DELETE /api/user/:u`, **no reassign target**, swallowed | migrated → `deleteUserApi` |
| `two-factor.spec.ts:74` | local `deleteUser` (already reassigns + asserts) | migrated, semantics kept |

### Tag teardown

| block | teardown it ran | classification |
|---|---|---|
| `tag-acl.spec.ts:156` | `deleteTagIfPresent` (UI) | migrated → `deleteTagByNameApi` |
| `tags.spec.ts:273` | local `deleteTag` ×2 (UI) | migrated → `deleteTagByNameApi` ×2 |
| `tags.spec.ts:306` | local `deleteTag` ×3 (UI) | migrated → `deleteTagByNameApi` ×3 |

### Config restore

| block | teardown it ran | classification |
|---|---|---|
| `dark-mode.spec.ts:57` | reset the `teedy-dark-mode` localStorage flag | migrated, restore call kept verbatim |
| `guest.spec.ts:52` | disable `guest_login` **with `expect` assertions**, close the guest context, re-check on a clean context | migrated; FIFO registration preserves the load-bearing order |
| `i18n.spec.ts:53` | reset the `teedy-locale` localStorage key | migrated, restore kept |
| `ldap-persist.spec.ts:62` | UI: untick "LDAP enabled" and Save | migrated, UI code kept verbatim in the defer |
| `locale-persist.spec.ts:56` | `POST /api/user {locale:'en'}` + localStorage reset | migrated, restore kept |
| `ui-bundle.spec.ts:135` | `POST /api/theme` restoring the pre-test theme | migrated; registered right after the snapshot is read |

### Resource disposal

| block | teardown it ran | classification |
|---|---|---|
| `apikey-auth.spec.ts:111` | `anon.dispose()` + a best-effort UI API-key cleanup | migrated → 2 defers |
| `docs-screenshots.spec.ts:157` | `context.close()` | migrated |
| `docs-screenshots.spec.ts:548` | `context.close()` | migrated |
| `footer-links.spec.ts:73` | `context.close()` | migrated |
| `rotation.spec.ts:146` | `otherContext.close()` | migrated |
| `slide-over-delete.spec.ts:62` | `userContext.close()` | migrated |
| `vocabulary.spec.ts:87` | `purgeVocabulary` (failure-safe namespace purge) | migrated, call kept verbatim |
| `workflow.spec.ts:223` | route-model delete + group delete | migrated → 2 defers |

## Two behaviours that deliberately changed

1. **Teardown now purges.** `DELETE /api/document/:id` only soft-deletes. Every document the suite
   "cleaned up" stayed in the trash, still consuming quota — a clean-start baseline run finished with
   **40 active + 10 trashed documents, 10 tags and 1 user** on the server. `deleteDocApi` trashes and
   then permanently deletes.
2. **`.catch(() => {})` on teardown is gone.** It existed only to stop a `finally` from masking the
   body's error. The fixture now does that properly — attaching the failure when the body already
   failed, reporting it when the body passed — so a swallowed teardown is pure loss.

One consequence worth stating: an `expect` that used to sit in a `finally` (`guest.spec.ts`) failed the
test unconditionally. Deferred, it still fails the test when the body passed, but when the body has
already failed it becomes an attached diagnostic. That is the intended trade — the body's failure is
the one worth reporting, and the teardown assertion is still visible in the report.

## The guardrail

`eslint.config.js` bans the shape, not the helper name: a `finally` block may contain nothing but calls
to `guardedTeardown`. `e2e/lint-fixtures/teardown-in-finally.ts` is a file that must fail lint, and
`npm run lint:teardown-rule` fails if it ever stops failing.

## Measured effect on the corpus

A clean-start container, full functional suite (desktop + mobile), counted through the admin API
before and after. Every run starts from **0 documents, 0 tags, 2 users (admin, guest), 0 trashed**.

| run | active documents | trashed | tags | users | failures |
|---|---|---|---|---|---|
| baseline (`main` @ `9946787d`) | 40 | 10 | 10 | 3 | 4 |
| after this phase, pass 1 | 31 | 1 | 9 | 3 | 3 |
| after this phase, pass 2 | 31 | 1 | 9 | 3 | 1 |

The two post-phase passes produce **identical** counts despite failing different tests — which is the
property the phase is really after: teardown no longer depends on the body succeeding.

The residual 31 documents / 9 tags / 1 user are **not** from migrated blocks. They come from specs
that never had a `finally` and therefore never had teardown to migrate — `comments`, `confirm-locale`,
`conversion`, `docs-screenshots`, `duplicate`, `files`, `forgiving-search`, `responsive`,
`saved-filters`, `share`, `trash`, `visual`, plus `tags.spec.ts`'s tag-filter-panel documents. Giving
those specs teardown is a separate, mechanical follow-up; this phase deliberately touched only
`bulk.spec.ts` outside the `finally` set, because the plan named it.

## Verifying the fixture itself

* `node scripts/verify-cleanup-controls.mjs` (app must be up) — runs the two control specs and asserts
  their OUTCOME. `scripts/e2e-run.sh` invokes it after the functional suite, so CI carries it.
* `npm run lint:teardown-rule` — asserts the ESLint guardrail still rejects every banned shape.

## Post-rebase amendment

The measurement and the per-block table above were taken at **`9946787d`** — 88 `finally` blocks
across 43 spec files, 36 `deleteDoc(` occurrences. This branch was subsequently rebased onto
**`9b5a6d7a`**, which lands #181 and #178; #181 adds one further teardown-capable block, the
`finally` of the new keyboard test in `file-panel.spec.ts`. It was dropped along with its
UI-driven `deleteDoc` during the conflict resolution, and its ZIP document is now purged by the
defer at `file-panel.spec.ts:321`.

| | blocks |
|---|---|
| measured at `9946787d` | 88 |
| added by the rebase base `9b5a6d7a` (#181 keyboard test) | 1 |
| **effective total, all converted** | **89** |
| remaining in the tree | **0** |

**Teardown order.** Registration happens at the point each entity is created, which is what makes a
defer failure-safe. Because steps run FIFO, that deliberately INVERTS the old `finally` order in a
few places — `workflow.spec.ts` now deletes the group and route model before the documents (the
group/model delete cancels referencing routes server-side and does not depend on the documents),
and `tag-acl.spec.ts` / `file-panel.spec.ts` delete the second user before the tag and document
(both are admin-owned, so the user is not their owner). `guest.spec.ts` is the one place where the
order itself is load-bearing, and there the defers are registered to reproduce it exactly. Every
inversion was checked against ownership and is green across three full suite runs.

