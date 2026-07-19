# SAST triage — CodeQL, July 2026 (#104)

- **Analyzed ref/commit:** `refs/heads/release/3.6.5` @ `d3103f327d88c70d07ca5996fc6253ecf3b14211`
- **CodeQL analysis:** run [29443262525](https://github.com/fmaass/teedy-docs/actions/runs/29443262525)
  (analysis id 1483998669, 24 results, gate green against the pre-triage baseline)
- **Triage date:** 2026-07-16
- **Dispositions:** 24 alerts total — **1 FIXED**, **23 ALREADY-MITIGATED**, 0 FALSE-POSITIVE, 0 ACCEPTED

Every open code-scanning alert on the ref was dispositioned individually (same-line alerts get
separate rows). `baseline_id` is the identity in `.github/baselines/codeql-known.json` under the
current gate scheme (`<ruleId>@<uri>:<line>:<col>`); FIXED findings are remediated in code and are
not baselined. Rationales state the neutralizing control or fix — they intentionally do not
enumerate attack mechanics.

## Gate identity model (repaired in this triage)

The previous gate identity hashed CodeQL `partialFingerprints` (line-content hash + column
fingerprint). That scheme was not one-to-one with alerts: byte-identical flagged lines in
*different files* collapsed into a single identity (alerts #14 and #16 below shared one baseline
entry — 23 baseline ids for 24 alerts), and the fingerprints exist only inside the CI run (the
code-scanning API strips the column fingerprint), so identities could not be reproduced or audited
locally. The gate in **both** workflow jobs (`build-deploy.yml`, `regression.yml`) now derives one
identity per result from its primary physical location — injective across all 24 alerts, auditable
against the code-scanning UI, and locally computable. The P1 merge's edit near the alert #6 anchor
did **not** re-anchor its content fingerprint (the pushed gate on `d3103f32` stayed green with the
old baseline), so the scheme change is a repair, not an incident response.

## Dispositions

| # | baseline_id | Rule (severity) | Location | Disposition | Public rationale | Verification | Private advisory |
|---|-------------|-----------------|----------|-------------|------------------|--------------|------------------|
| 1 | — (fixed, not baselined) | java/implicit-cast-in-compound-assignment (high 8.1) | AppContext.java:375 `getQueuedTaskCount` | FIXED | Queued-task sum now accumulates and returns `long`; the `int` compound-assignment narrowing is gone. API meaning unchanged (`queued_tasks` remains a number). | RED `TestQueuedTaskCount.survivesCountsBeyondIntRange` (expected 5500000000, got 1205032704) → GREEN; alert closes on this branch's pushed CodeQL run | N/A |
| 2 | java/command-line-injection@…/VideoFormatHandler.java:36:48 | java/command-line-injection (critical 9.8) | VideoFormatHandler.java:36 `generateThumbnail` | ALREADY-MITIGATED | `ProcessBuilder` argv with constant binary (`ffmpeg`) and constant flags; the only variable element is a server-side absolute file path passed as a discrete argument; no shell, and neither MIME, metadata, nor logical filename becomes command syntax. Invocation deliberately unchanged. | Invariant verified against source (argv built at :33-35, executed :36-37); N/A (no code change) | N/A |
| 3 | java/regex-injection@…/RegexRulePolicy.java:114:43 (sink relocates there from TagMatchRuleResource.java:184 on this branch) | java/regex-injection (high 7.5) | TagMatchRuleResource.java:184 `test` | ALREADY-MITIGATED | Admin-only endpoint. Patterns compile exclusively through the shared `RegexRulePolicy`, which keeps the **`java.util.regex` engine deliberately — rule semantics stay exactly as historically authored, by construction** (no engine translation, no dialect divergence). The DoS risk is contained by BOUNDING: admission requires compilation + a 1000-char pattern cap; every evaluation caps the content to the leading 512 KiB and runs against a 2 s wall-clock deadline enforced from inside the match loop; a timeout or matcher stack exhaustion (StackOverflowError is caught tightly at the match call and converted to the policy abort exception) surfaces as 400 on this endpoint. The compile sink stays visible to the analyzer by design. | `TestRegexRulePolicy` (byte-identical-semantics battery vs direct `java.util.regex`; RED `execution timed out after 10000 ms` on unbounded evaluation → GREEN deadline abort ~2 s on an empirically catastrophic pattern, `(.*a){15}b`; RED raw `StackOverflowError` → GREEN policy abort on `(x|y)*` vs 5000 chars); endpoint timeout and stack exhaustion → 400 in `testHostilePatternsAreBoundedNotReinterpreted` | N/A |
| 4 | java/regex-injection@…/RegexRulePolicy.java:92:29 (sink relocates there from TagMatchRuleResource.java:192 on this branch) | java/regex-injection (high 7.5) | TagMatchRuleResource.java:192 `validateRegex` | ALREADY-MITIGATED | Same shared bounded policy on the validation path (add/update endpoints, admin-only): syntax + length admission; runtime evaluation of persisted rules is deadline- and stack-bounded with fail-safe skip (`ruleMatches`) and a bounded QUARANTINE keyed on rule id + pattern — a rule that timed out, exhausted the matcher stack, or failed to compile is skipped WITHOUT re-evaluation (and without re-logging) until its pattern changes, so even a DAO-level legacy rule cannot stall file processing, tax every upload, or amplify logs. Historical semantics guaranteed — a legacy backreference rule keeps matching exactly as it always did (pinned in `TestTagRuleEvaluationFailSafe`). | Same suites as #3 plus `persistedCatastrophicRuleCannotStallEvaluation`, `stackExhaustingRuleIsSkippedNotFatal` (sibling rules unaffected) and `quarantinedRuleIsNotReEvaluated` (RED: second evaluation took 2000 ms → GREEN: skipped without evaluation) | N/A |
| 5 | java/polynomial-redos@…/ValidationUtil.java:101:36 | java/polynomial-redos (high 7.5) | ValidationUtil.java:101 `validateEmail` | ALREADY-MITIGATED | Sole production caller (user registration, `UserResource`) requires the ADMIN base function and length-caps the input to 100 characters *before* the regex runs; no uncapped or low-privilege input reaches it. | Caller audit: exactly one production call site (`UserResource.java:111`), cap at :108, admin check at :101; N/A (no code change) | N/A |
| 6 | java/path-injection@…/EncryptionUtil.java:79:52 | java/path-injection (high 7.5) | EncryptionUtil.java:79 `decryptFile` | ALREADY-MITIGATED | Source is a server storage path (UUID file id under the app data directory); destination is a JVM-generated temp from `FileService.createTemporaryFile`. No request-derived string participates in either path. | Invariant verified against source; fingerprint did not re-anchor under P1's adjacent edit (pushed gate green on `d3103f32`); N/A | N/A |
| 7 | java/path-injection@…/RasterGenerationUtil.java:174:95 | java/path-injection (high 7.5) | RasterGenerationUtil.java:174 | ALREADY-MITIGATED | Temp output path is `storageDir.resolve(fileId + "_web." + token + ".tmp")`: `fileId` is a server-generated UUID (`FileDao`), reachable only via `[a-z0-9-]`-constrained, DAO-authorized endpoints; `token` is `UUID.randomUUID()`; suffixes are literals; storage dir is app-owned (web users cannot create entries or symlinks there). | Invariant verified against source (:141, :169-171); N/A | N/A |
| 8 | java/path-injection@…/RasterGenerationUtil.java:178:95 | java/path-injection (high 7.5) | RasterGenerationUtil.java:178 | ALREADY-MITIGATED | Same invariant as #7 for the `_thumb` temp path. | Invariant verified against source; N/A | N/A |
| 9 | java/path-injection@…/RasterGenerationUtil.java:221:24 | java/path-injection (high 7.5) | RasterGenerationUtil.java:221 (move source) | ALREADY-MITIGATED | `Files.move` source is the app-created web temp (#7); both endpoints of the move are storage dir + server UUID + literal suffix. | Invariant verified against source (:142-143, :221); N/A | N/A |
| 10 | java/path-injection@…/RasterGenerationUtil.java:221:32 | java/path-injection (high 7.5) | RasterGenerationUtil.java:221 (move target) | ALREADY-MITIGATED | `Files.move` target is `storageDir.resolve(fileId + "_web")` — server UUID + literal suffix. | Invariant verified against source; N/A | N/A |
| 11 | java/path-injection@…/RasterGenerationUtil.java:223:24 | java/path-injection (high 7.5) | RasterGenerationUtil.java:223 (move source) | ALREADY-MITIGATED | `Files.move` source is the app-created thumb temp (#8); same invariant. | Invariant verified against source; N/A | N/A |
| 12 | java/path-injection@…/RasterGenerationUtil.java:223:32 | java/path-injection (high 7.5) | RasterGenerationUtil.java:223 (move target) | ALREADY-MITIGATED | `Files.move` target is `storageDir.resolve(fileId + "_thumb")` — server UUID + literal suffix. | Invariant verified against source; N/A | N/A |
| 13 | java/path-injection@…/RasterGenerationUtil.java:257:34 | java/path-injection (high 7.5) | RasterGenerationUtil.java:257 | ALREADY-MITIGATED | `Files.deleteIfExists` on one of the app-created temp paths above. | Invariant verified against source; N/A | N/A |
| 14 | java/path-injection@…/DocxFormatHandler.java:62:65 | java/path-injection (high 7.5) | DocxFormatHandler.java:62 | ALREADY-MITIGATED | The handler's `Path` parameter is created by `Files.createTempFile` in the app-owned temp directory. The uploaded logical filename DOES flow into the temp-file *suffix* (`FileResource.java:153` → `FileService.createTemporaryFile(name)`, `FileService.java:107`), but the JDK resolves prefix + random component + suffix as a single path element and throws `IllegalArgumentException` ("Invalid prefix or suffix", `java.nio.file.TempFileHelper.generatePath`, `name.getParent() != null`) for any value introducing a path component — verified empirically on JDK 21 (`../../etc/passwd`, `x/y.pdf`, `/abs.pdf` all rejected; a backslash is a literal filename character on the POSIX runtime, not a separator). The random middle component prevents prediction; storage-directory variants of the parameter are server UUIDs. | Invariant verified against source AND JDK behavior (empirical check 2026-07-16); N/A. Shared one hashed identity with #16 under the old scheme — now a distinct baseline id | N/A |
| 15 | java/path-injection@…/ImageFormatHandler.java:42:61 | java/path-injection (high 7.5) | ImageFormatHandler.java:42 | ALREADY-MITIGATED | Same handler-input invariant as #14. | Invariant verified against source; N/A | N/A |
| 16 | java/path-injection@…/OdtFormatHandler.java:62:65 | java/path-injection (high 7.5) | OdtFormatHandler.java:62 | ALREADY-MITIGATED | Same handler-input invariant as #14. | Invariant verified against source; N/A. Shared one hashed identity with #14 under the old scheme — now a distinct baseline id | N/A |
| 17 | java/path-injection@…/PdfFormatHandler.java:43:61 | java/path-injection (high 7.5) | PdfFormatHandler.java:43 | ALREADY-MITIGATED | Same handler-input invariant as #14. | Invariant verified against source; N/A | N/A |
| 18 | java/path-injection@…/TextPlainFormatHandler.java:50:60 | java/path-injection (high 7.5) | TextPlainFormatHandler.java:50 | ALREADY-MITIGATED | Same handler-input invariant as #14. | Invariant verified against source; N/A | N/A |
| 19 | java/path-injection@…/PptxFormatHandler.java:75:65 | java/path-injection (high 7.5) | PptxFormatHandler.java:75 | ALREADY-MITIGATED | Same handler-input invariant as #14. | Invariant verified against source; N/A | N/A |
| 20 | java/path-injection@…/FileResource.java:419:42 | java/path-injection (high 7.5) | FileResource.java:419 | ALREADY-MITIGATED | Path is a server temp produced by decrypting `storageDir.resolve(id)`; the `id` path parameter is regex-constrained to `[a-z0-9-]` and the file record is DAO-loaded and permission-checked before any path is built. | Invariant verified against source (:355, :363, :393-396); N/A | N/A |
| 21 | java/path-injection@…/FileResource.java:525:34 | java/path-injection (high 7.5) | FileResource.java:525 | ALREADY-MITIGATED | Same invariant as #20 for the orphan decrypted temp cleanup. | Invariant verified against source; N/A | N/A |
| 22 | java/path-injection@…/FileResource.java:811:31 | java/path-injection (high 7.5) | FileResource.java:811 | ALREADY-MITIGATED | Path is `storageDir.resolve(fileId + "_" + size)`: `fileId` regex-constrained and DAO-authorized (READ permission checked at :794); `size` allow-listed to `web`/`thumb`/`content` (:789) before the path is built. | Invariant verified against source; N/A | N/A |
| 23 | java/path-injection@…/FileResource.java:813:67 | java/path-injection (high 7.5) | FileResource.java:813 | ALREADY-MITIGATED | Reads a bundled classpath resource `/image/file-<size>.png` with `size` allow-listed to `web`/`thumb`; not a user-controllable filesystem path. | Invariant verified against source; N/A | N/A |
| 24 | java/path-injection@…/ThemeResource.java:211:37 | java/path-injection (high 7.5) | ThemeResource.java:211 | ALREADY-MITIGATED | The `type` path segment is constrained by the JAX-RS route regex to exactly `logo\|background\|favicon` (:194) and the endpoint requires the ADMIN base function (:201); no traversal value can bind. | Invariant verified against source; N/A | N/A |

`baseline_id` paths are abbreviated with `…` for readability; the full ids in
`.github/baselines/codeql-known.json` use the complete artifact uri.

## Regex approach note (engine retained, evaluation bounded)

An engine substitution (RE2/J) was prototyped and deliberately **rejected**: successive review
rounds kept finding constructs the two engines parse differently (class intersection, quoted
regions, bare-dot line-terminator behavior, case-folding tables, anchor placement) — a growing
translation surface with silent-semantic-change risk. Keeping `java.util.regex` preserves
historically authored rule semantics **by construction**; the security property is enforced by
bounds (admission syntax + length cap; per-evaluation content cap + wall-clock deadline with
fail-safe skip and once-per-rule logging). Consequence: the regex-injection *sink* remains in
code and CodeQL keeps flagging it — those two findings are governed by the baseline, not fixed
away. The sink relocates from `TagMatchRuleResource.java` to the shared `RegexRulePolicy` on this
branch. The baseline carries ONLY the relocated anchors computed from source
(`RegexRulePolicy.java:92:29`, `:114:43`) — the old `TagMatchRuleResource` anchors are deliberately
NOT retained: a stale allowance there would let a regression that restores a direct
`Pattern.compile` at those exact coordinates pass the gate silently, and historical commits carry
their own committed baseline. This is fail-closed in both directions: if the analyzer places the
relocated sink at different coordinates, the pushed gate turns red with the actual coordinates and
the entry is re-anchored per the runbook (`docs/ci-pipeline.md` §5d). NOTE: these two anchors
track `RegexRulePolicy.java` source lines — ANY future edit that shifts them must re-anchor the
baseline entries; pushed CI enforces this fail-closed.

## Verification limits

- The baseline + identity-scheme change is **only provable on pushed CI**: local proof here is
  (a) this alert→identity mapping and (b) a dry-run of the exact committed gate script against the
  `d3103f32` SARIF — which flagged **exactly three** unlisted identities: the FIXED implicit-cast
  and the two regex-injection results at their OLD `TagMatchRuleResource` coordinates. All three
  are expected against that pre-change SARIF: the fix and the sink relocation are not analyzed
  yet, and the legacy coordinates are deliberately not baselined (see the approach note). On this
  branch's pushed analysis all three results disappear and the relocated anchors take over.
- The FIXED alert (#1) closes when GitHub processes the CodeQL analysis of this branch's push; the
  23 baselined alerts remain open in code-scanning by design (the baseline governs them, with
  `expires: 2026-10-15` forcing re-triage). The two regex alerts (#3/#4) close at their old
  locations and reopen at the relocated `RegexRulePolicy` sink, covered as described above.
