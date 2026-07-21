# ADR-0019 — Startup reconciliation backfill for lost post-commit file derived-data

- **Status:** accepted
- **Date:** 2026-07-20 (accepted 2026-07-21)
- **Issue:** #159 (public: "Post-commit file processing can be lost on a hard JVM stop, leaving files
  silently unsearchable")
- **Shipped in:** v3.7.1 (migration `dbupdate-059-0.sql`, db.version 58 → 59)
- **Durable record:** This file is the in-repo copy. The authoritative ARCHIMEDES record lives in
  BookStack under the `teedy-docs` Decisions chapter (`a:adr`, `a:project=teedy-docs`); mirror any
  edits there.
- **Revision:** accepted from DRAFT v2. v1 went through two independent design reviews (one
  full-source, one external fresh-eyes); both returned REVISE on the same core defects (live-lease
  stranding, "end of processFile ≠ success", non-idempotent replay, one-shot marker vs recurring
  obligations, no completion ownership). v2 folded all five and re-ran the escalation verdict.

## Decision outcome (as shipped in v3.7.1)

The **backfill** architecture was adopted (not the durable outbox), with **Option A** for the
recurring-obligation question (Section 4): the guarantee is scoped to **first-time post-upload**
processing; the `attach` / manual-reprocess residual is documented in #159 and NOT covered by
generation-fencing (Option B was not built — there is no `FIL_PROCGENERATION_N` column). The two
architecture-independent findings (explicit `ProcessingOutcome`, idempotent keyed replay + auto-tag
dedup) were implemented as required. Concretely shipped: migration 059 (four marker/lease columns +
bounded legacy stamp + `IDX_FIL_PROCESSED`), `FileReconciliationService`, the DB-clock fenced
claim/completion CAS, the drain-on-zero-unprocessed self-stop, and the `reprocess` event flag with
webhook suppression. The outbox remains the named future alternative if durable webhook/event
delivery or full recurring-obligation coverage becomes a goal (see Escalation).

## Context

File derived data — thumbnail, `_web` raster, OCR text into `T_FILE.FIL_CONTENT_C`, the Lucene
index entry, and auto-tag application — is produced **asynchronously, after** the upload
transaction commits. The producer (`FileUtil.createFile`, `FileUtil.java:314-320`) enqueues a
`FileCreatedAsyncEvent` onto an **in-memory** per-transaction list (`ThreadLocalContext.addAsyncEvent`,
`ThreadLocalContext.java:261`), drained **only after** the transaction durably commits
(`fireAllAsyncEvents`, `:271-289`), which posts to the async bus where `FileProcessingAsyncListener`
does the work (`FileProcessingAsyncListener.java:122-174`).

If the JVM stops hard in the commit→process window (`kill -9`, OOM-kill, `docker stop` past grace),
the in-memory event is lost with **no automatic backfill**. This is **derived-data loss, not document
loss** — the encrypted original is durable and downloadable; the file is silently unsearchable,
thumbnail-less, and untagged until a human re-uploads it or runs the per-document `ProcessFiles`
action. `POST /app/batch/reindex` does **not** fix it: `RebuildIndexAsyncListener` re-indexes from the
**stored** `FIL_CONTENT_C` (`FileDao.findAll` → `createFiles`), never re-running OCR or raster.

**Why the obvious predicates are wrong** (both reviews confirmed):

- `FIL_CONTENT_C IS NULL` is a legitimate resting state (unsupported formats:
  `extractContent` returns `null` when no `FormatHandler` matches, `:217-221`; OCR-off images), not a
  lost-work signal.
- **Index-presence is not a durable per-file signal either — with corrected reasoning.** The install
  **seeds `LUCENE_DIRECTORY_STORAGE=FILE`** (`dbupdate-000-0.sql:37`), so a normal instance has a
  **file-backed, boot-persistent** index (RAM only if an operator sets `RAM`, or on a legacy DB
  missing the row → code default RAM, `LuceneIndexingHandler.java:138-145`; a full rebuild fires only
  after an index-init/health failure). Even so, index-presence cannot be the completion predicate:
  the index is a **derived, rebuildable** store — `batch/reindex` repopulates it from stored `content`,
  so a file whose OCR was lost gets an index entry with *empty* content once reindexed ("present in
  index" ≠ "content was extracted"), and coupling durability correctness to a store that is
  intentionally clearable/rebuildable is the anti-pattern we are avoiding.

A correct signal must be a **durable per-file record that processing actually completed**, which does
not exist today.

**In-repo precedent (partial).** #119's `ContentMacBackfillService` established the *shape* — a
nullable marker, a conditional CAS, a null-scan, and a self-completing `AbstractScheduledService`
wired into `AppContext.startUp`. This ADR reuses that **shell**, but (see the design reviews folded
below) the reconciliation problem needs materially more than #119: an atomic **fenced** claim, an
explicit pipeline **success/failure** result, **keyed** (idempotent) replay, and **DB-arbitrated**
auto-tag dedup. #119's own backfill decrypts via `decryptInputStream` and is explicitly *not*
exactly-once (multi-instance idempotent, at-least-once) — so it is a model, not a drop-in.

## Decision

Add a **durable per-file completion marker** plus a **fenced claim lease**, and a **startup
reconciliation service** that replays the lost processing for any active file whose marker is unset.
The five review findings drive the specifics below.

### 1. Schema — migration `dbupdate-059-0.sql` (db.version 59)

Four nullable columns on `T_FILE`:

| Column | Type | Meaning |
|---|---|---|
| `FIL_PROCESSED_D` | `datetime` | **Completion marker.** NULL = not proven complete. Non-null (a DB-clock timestamp) = the full pipeline reached a terminal state (success **or** terminal-skip). |
| `FIL_PROCESSINGAT_D` | `datetime` | **Lease timestamp**, written from the **DB clock**. Drives claimability/expiry. |
| `FIL_PROCESSINGTOKEN_C` | `varchar(36)` | **Per-claim fencing token** (a fresh UUID each claim). Completion is fenced to it — see Finding 5. |
| `FIL_PROCATTEMPTS_N` | `int` | **Bounded-retry counter.** Incremented per claim; at a cap `K` the row is terminal-skip-stamped so a persistently-retryable-failing file cannot keep the service alive forever. |

`datetime` is written verbatim for H2 and auto-transformed to `timestamp` for PostgreSQL by
`DialectUtil` (`DialectUtil.java:73`), so **no `!H2!`/`!PGSQL!` split** is needed for these columns
(unlike the `bit`/`bool` case in migration 058). Both engines accept `add column if not exists`.

```sql
-- #159 durable per-file processing-completion marker + FENCED reprocess claim lease.
alter table T_FILE add column if not exists FIL_PROCESSED_D datetime;
alter table T_FILE add column if not exists FIL_PROCESSINGAT_D datetime;
alter table T_FILE add column if not exists FIL_PROCESSINGTOKEN_C varchar(36);
alter table T_FILE add column if not exists FIL_PROCATTEMPTS_N int;

-- LEGACY LINE (bounded, one-time). Stamp every row that exists at upgrade time COMPLETE. We have no
-- durable way to know which pre-059 rows were actually processed (that is the marker we are adding),
-- and re-deriving the whole corpus on first boot is unacceptable (mass re-OCR/re-tag/re-index). So
-- the marker guarantees correctness GOING FORWARD only; pre-059 files are presumed handled (operators
-- retain per-document ProcessFiles + batch/reindex for known-bad legacy files). FIL_CREATEDATE_D is
-- NULLABLE in the base schema (dbupdate-000-0.sql:4), so COALESCE it to avoid a NULL marker that would
-- re-select the row forever. This UPDATE runs inside DbOpenHelper's startup migration, BEFORE any
-- request or service is serving (migration-time quiescence), so it cannot race live processing.
update T_FILE set FIL_PROCESSED_D = COALESCE(FIL_CREATEDATE_D, CURRENT_TIMESTAMP) where FIL_PROCESSED_D is null;

-- Accelerate the reconciler's hot predicate (processed IS NULL AND deleteDate IS NULL). After the
-- legacy stamp this selects ~0 rows, so a narrow index on the marker keeps the boot scan O(matches).
create index if not exists IDX_FIL_PROCESSED on T_FILE (FIL_PROCESSED_D);

update T_CONFIG set CFG_VALUE_C = '59' where CFG_ID_C = 'DB_VERSION';
```

Bump `db.version` to `59` in **all three** overlays (each shadows at runtime; tests do not catch a
missed overlay — recurring project gotcha): `docs-core/.../config.properties`,
`docs-web/src/dev/resources/config.properties`, `docs-web/src/prod/resources/config.properties`.

Entity (`File.java`): add the four fields with getters/setters. **`FileDao.update` must NOT copy any
of them** (same discipline it already applies to the version-chain columns, `FileDao.java:309-321`):
they change only through the CAS paths below.

### 2. Pipeline success/failure result — completion only on real success (Finding 2)

**Problem:** the pipeline deliberately swallows failures, so "processFile returned" ≠ "processing
succeeded". Verified swallow points:
- raster: `catch (Throwable)` → log & continue (`FileProcessingAsyncListener.java:234-236`);
- content extraction: `catch (Throwable)` → `null` (`:244-246`) — indistinguishable from the
  legitimate no-handler null (`:217-221`);
- PDF handler swallows internally (`PdfFormatHandler`);
- **Lucene write**: `handle()` does `catch (Exception) { log … skipping commit }`
  (`LuceneIndexingHandler.java:659-666`) — an index write can fail silently;
- auto-tag rule-load: `catch` → silent `return` when the rules table is absent
  (`:263-267`) (benign), while a real auto-tag DB failure propagates via `TransactionUtil.handle`.

A completion write placed "after processFile" would stamp a file **whose index write failed**.

**Design:** `processFile` computes an explicit `ProcessingOutcome ∈ {COMPLETE, RETRYABLE_FAILURE,
TERMINAL_SKIP}`. Each step reports into it; the swallowing catches keep their **live-path** behaviour
(a raster failure still must not abort extraction) but now also **record** the failure into the
outcome:

- **Content:** *no handler* → resolved (content legitimately null) — does **not** by itself fail
  completion. Handler present but **threw** → `RETRYABLE_FAILURE`.
- **Index write:** the swallowed `handle()` must **surface** its result. `IndexingHandler.updateFile`
  (see Finding 3) reports success/failure (return a boolean or rethrow so the caller classifies). A
  failed index write ⇒ `RETRYABLE_FAILURE`.
- **Auto-tag:** a thrown auto-tag transaction ⇒ `RETRYABLE_FAILURE`; the benign "no rules table" no-op
  is success.
- **Raster/thumbnail:** a raster failure is **recorded and logged** but does **not** block completion.
  Rationale, stated explicitly: the thumbnail is a cosmetic, on-demand-regenerable *filesystem*
  artifact (not a DB-durable, search-critical output); blocking completion on it would risk infinite
  retry of a file whose rasterization genuinely fails though its text extracts. Completion-required =
  {content resolved, index write ok, auto-tag ok}. **Residual (documented in #159):** a file may end
  up searchable+tagged but thumbnail-less after a persistent raster failure — the pre-existing
  behaviour today.
- **Blob missing/corrupt/undecryptable** (owner key absent, decrypt error) → `TERMINAL_SKIP` (like
  #119's `SKIP_MARKER`): it can never succeed, so stamp `processed` to stop re-selecting it.

Completion write location: at the **success end** of `processFile`, **only** on `COMPLETE` (or
`TERMINAL_SKIP`), via the **fenced** completion CAS (Finding 5) — **not** in `processEvent`'s
unconditional `finally` (`:99-111`), which runs on every outcome including a throw. On
`RETRYABLE_FAILURE` nothing is stamped; the lease later expires and the row is reclaimed. The
early-return "file/user deleted since" paths (`:130-133`, `:141-144`, `:153-155`) produce **no**
`COMPLETE` and therefore never stamp — and the soft-deleted row is excluded from the work-set anyway
(Finding: deleted-row path fix).

### 3. Idempotent replay (Finding 3)

**(a) Keyed index write.** `createFile` is `addDocument` — **NOT keyed** (`LuceneIndexingHandler.java:227`);
`updateFile` is the keyed op (`updateDocument(new Term("id", …))`, `:235`). v1's "index create keyed by
file id" claim was **false**. A crash between an `addDocument` and the completion CAS would, on replay,
`addDocument` **again** → a duplicate Lucene doc. **Fix:** the replay path uses **`updateFile`
(keyed) unconditionally** — `updateDocument` deletes any existing doc for that id then adds, so it is
idempotent whether or not a prior partial run indexed it. Concretely, `processFile` selects the keyed
write when `event.isReprocess()` (regardless of created/updated), so every reconciliation replay is
idempotent.

**(b) Auto-tag double-insert.** Auto-tag is query-then-insert with random PKs
(`FileProcessingAsyncListener.java:296-315`) and there is **no unique `(document, tag)` constraint** —
`dbupdate-019-0.sql` has only the **non-unique** `IDX_DOT_COMPOSITE`. Two files of one document
processing concurrently (or a replay racing a live process) can double-insert the same tag link.
**Fix (DB-arbitrated serialization):** before the query-then-insert, take a `PESSIMISTIC_WRITE` lock
on the parent `T_DOCUMENT` row (the codebase already uses `PESSIMISTIC_WRITE`, e.g.
`FileDao.getActiveByIdForUpdate:353-363`), serializing concurrent auto-tagging of the same document;
under the lock the existing "select existing links, insert the missing" logic is race-free.
*Alternative* (heavier, more durable): a portable partial-unique index on
`(DOT_IDDOCUMENT_C, DOT_IDTAG_C) WHERE DOT_DELETEDATE_D IS NULL` (needs the H2 generated-column trick
from ADR-0018) plus violation-translation. Recommend the per-document lock (no extra migration).
**Note:** this is a pre-existing concurrency bug that replay *exposes*; it must be fixed under **either**
architecture (backfill or outbox) — see Escalation.

### 4. Recurring obligations vs a one-shot marker (Finding 4)

**Problem:** the marker is one-shot, but processing obligations **recur**. `FileResource.attach`
sets a new `documentId` and posts a `FileUpdatedAsyncEvent` ("it wasn't sent during file creation",
`FileResource.java:285-292`); a crash after that commit loses the event while `FIL_PROCESSED_D` stays
set → the file is **permanently excluded** from reconciliation. (Manual `ProcessFiles` and the
`FileResource:574` update path pose the same shape.)

Two ways to resolve, presented for the maintainer:

- **(A) Recommended for this ADR's scope — narrow the guarantee.** The reconciliation backfill
  guarantees recovery of **first-time post-upload** file processing (the `FileCreatedAsyncEvent` from
  `FileUtil.createFile`). It does **not** cover re-processing obligations from `attach` / manual
  reprocess (`FileUpdatedAsyncEvent`). This is defensible on impact: at upload the orphan file already
  gets content+index+thumbnail; `attach` re-posts only to re-index under the new document association
  and run auto-tag — so a lost `attach` event leaves the file **still searchable** (prior state),
  missing only the document-association re-index and auto-tags, a narrower and lower-severity gap than
  "unsearchable", on an operation the user actively triggered and can re-trigger. **This residual gap
  is documented explicitly in #159.**
- **(B) Full fix — generation fencing.** Add a `FIL_PROCGENERATION_N` counter. Every producer that
  creates a new obligation (upload, attach, manual reprocess, EML import) **atomically** (in the same
  transaction as its mutation) bumps the generation **and** reopens the marker (`FIL_PROCESSED_D =
  NULL`). The claim captures the generation; the completion CAS is fenced to it
  (`… AND FIL_PROCGENERATION_N = :claimedGeneration`). As the reviewer noted, a bare "clear the
  marker" is **insufficient** — an older worker could complete a newer obligation; generation-fencing
  rejects that (the old worker's completion matches 0 rows). Cost: **every producer** (≥4 sites) must
  participate in the fencing protocol — materially more invasive than (A).

The **outbox alternative covers recurring obligations for free** (it durably enqueues *every* event,
create/update/attach), which is a genuine correctness point in its favour — see Escalation.

### 5. Fenced claim/lease with a DB clock (Findings 1 & 5)

Two DAO CAS methods using the `executeUpdate()`-row-count-as-ownership idiom the codebase already
relies on (`FileDao.demoteCurrentLatestVersion:224-231`, `setContentMacIfNull:414-421`); each bulk
`UPDATE` takes a row write lock, serializing racing claimers so exactly one observes `1`.

**All time comparisons use the DB clock, never per-JVM `new Date()`** (Finding 5: wall-clock leases
from per-JVM clocks over a PostgreSQL `timestamp without time zone` column make false expiry real
under skew/zone differences). Each iteration reads `dbNow` once via `select current_timestamp`,
computes `cutoff = dbNow − LEASE_TTL` in Java, and passes both as parameters — so every instance
agrees on one clock and the column's lack of a zone is irrelevant (no JVM-zone value is ever written
or compared).

**Claim CAS** — claim iff unprocessed AND (unclaimed OR lease expired), stamping a fresh fencing
token and bumping the attempt counter:

```
update File f
   set f.processingAt = :dbNow, f.processingToken = :newToken, f.procAttempts = coalesce(f.procAttempts,0)+1
 where f.id = :id and f.processed is null
   and (f.processingToken is null or f.processingAt < :cutoff)
```
`== 1` → won (the worker generated `:newToken` and remembers it); `== 0` → already processed or a
**live** claim holds it → skip. At `procAttempts > K` the reconciler instead **terminal-skip-stamps**
the row (bounded retry) and logs it for operator attention.

**Completion CAS — fenced to the claim token** (Finding 5: completion must not be a bare
`WHERE processed IS NULL`, or a stale worker past its TTL could mark complete and clear a successor's
lease):

```
update File f
   set f.processed = :dbNow, f.processingToken = null, f.processingAt = null
 where f.id = :id and f.processed is null and f.processingToken = :myToken
```
A re-claim writes a **new** token, so a stale worker's completion matches 0 rows and is rejected. On
`TERMINAL_SKIP` the same statement runs (semantically a skip; distinguished only in logs).

**"Existential completion" — stated explicitly.** Completion is **owner-fenced**, not existential:
only the current claim-holder can stamp. But because replay is now idempotent (keyed `updateFile` +
serialized auto-tag dedup, Finding 3), even the anomaly of two runs both succeeding converges to
correct state; the fence merely ensures the *loser* cannot clear the *winner's* successor lease. The
one admitted anomaly is **wasted duplicate processing** when a lease falsely expires under sustained
load — bounded (by `K`) and harmless (idempotent).

**Drain / self-stop — the live-lease stranding fix (Finding 1, both reviews' top finding).** v1
stopped when the *work-set* (unclaimed/expired rows) was empty, which strands a live lease: claim at
10:00, crash at 10:05, reboot at 10:06 — the scan excludes the still-live lease, sees empty,
self-stops; at 10:30 the lease expires with **no service left to reclaim** (and identically if the
async listener fails after the service stopped). **Fix:** the service self-stops **only when
`count(active rows with FIL_PROCESSED_D IS NULL) == 0`** — i.e. while *any* unprocessed active row
exists (whether unclaimed, leased-in-flight, or expired) the service stays scheduled (fixed-delay
1/min). A genuinely in-flight reprocess keeps its row unprocessed → the service stays alive → when
that lease resolves (completion removes the row from the count) or expires (next poll re-claims via
the DB-clock cutoff and re-enqueues) the count eventually reaches 0 and it stops. Terminal failures
are stamped (Finding 2), so they leave the count; only truly-retryable in-flight/pending work keeps
the service alive, and `K` bounds even a misclassified persistent failure. (An optional
`FIL_PROCESSINGOWNER_C = bootId` would let a reboot recognise prior-boot claims as abandoned
*instantly* rather than waiting up to `LEASE_TTL`; deferred as an optimisation — the stay-alive drain
already removes the stranding, at the cost of an up-to-TTL post-reboot delay, acceptable since the
file was already unsearchable since the crash.)

`LEASE_TTL`: conservative, above the longest realistic single-file OCR (default **30 min**,
configurable via a `docs.*` property mirroring the existing async knobs).

### 6. Reconciliation service + startup/shutdown wiring

New `FileReconciliationService extends AbstractScheduledService`, reusing the `ContentMacBackfillService`
shell (throttled batches, per-file own transaction, self-stop):

- **Work-set scan** (own short read tx, no held lock): active rows with `FIL_PROCESSED_D IS NULL` and
  claimable (`processingToken IS NULL OR processingAt < :cutoff`), `order by FIL_CREATEDATE_D`,
  `limit BATCH_SIZE`. No `documentId` filter (orphan files also get derived data on upload,
  `FileUtil.java:315-320`); no `latestVersion` filter (non-latest versions are indexed too).
- **Per file:** `claimForReprocess(...)`; if won, load the user for its private key, **decrypt the
  stored blob to a temp file** (`EncryptionUtil.decryptFile` on
  `DirectoryUtil.getStorageDirectory().resolve(id)` — **modelled on**
  `ContentMacBackfillService.processFile:141-159` and `ProcessFilesAction:47-52`, which use
  `decryptInputStream`/`decryptFile`; not "exactly as", and not exactly-once), recover the document's
  language via `DocumentDao` (null for orphans), then post a replay event carrying `reprocess=true`;
  `processFile` runs the keyed (`updateFile`) path (Finding 3a) and, on `COMPLETE`, runs the fenced
  completion CAS. The existing `processEvent` `finally` (`:106-110`) owns temp-delete + in-memory
  marker release.
- **Startup ordering** (`AppContext.startUp:145-255`): register **after** `resetEventBus()` (bus +
  `FileProcessingAsyncListener`/`WebhookAsyncListener` registered, `:260-278`) and **after** the
  indexing handler starts (`:154-168`); place adjacent to `contentMacBackfillService` (`:210-219`),
  reusing its `TERMINATED`/`STOPPING` self-stop tolerance around `awaitRunning`.
- **Shutdown ordering (advisory fix).** `AppContext.shutDown` currently shuts the **async executors
  and the index first** (`:457-469`) and the backfill services after — the **reverse** of what a
  service that *enqueues onto* those executors needs. The reconciler must be **`stopAsync()` +
  `awaitTerminated()` FIRST**, before the executor-shutdown loop and `indexingHandler.shutDown()`, so
  it cannot enqueue a replay onto a closing executor / write to a closed index during shutdown.

### 7. Webhook suppression on replay (Finding 4-adjacent / original Constraint 4)

`WebhookAsyncListener` fires `FILE_UPDATED` (and `FILE_CREATED`) for file events
(`WebhookAsyncListener.java:78-88`). A replay is not a user-facing file event. Add `boolean reprocess`
(default `false`) to the file event(s); guard the listener to `return` when `reprocess` is true.
`FileProcessingAsyncListener` ignores the flag. Only the reconciler sets it, so the live path is
byte-identical to today. The reconciler does **not** re-post the `DocumentUpdatedAsyncEvent` the live
create path also enqueues (`FileUtil.java:322-327`), so no `DOCUMENT_UPDATED` webhook fires.
**Required regression test:** cover **both** flag paths — `reprocess=true` suppresses the webhook,
`reprocess=false` still fires it — so a future refactor cannot silently drop the live notification or
leak a replay one.

## Observability

The service knows what it **selected / claimed / enqueued**, not what "reprocessed" — the actual
processing and its `COMPLETE`/`RETRYABLE`/`TERMINAL` outcome happen later on the bus thread. Report,
per iteration and as a boot summary, counts of: **selected, claimed, enqueued, completed, failed
(retryable), terminal-skipped, expired-reclaimed**. (v1's "reprocessed N" was an overstatement — the
service cannot assert completion at enqueue time.) The `processFile` INFO logs already fire on the
replay path (`:241,247`). Operator gauge: `select count(*) from T_FILE where FIL_PROCESSED_D is null
and FIL_DELETEDATE_D is null` = live unreconciled backlog. Self-stop logs a single line when the
count reaches 0.

## Consequences / trade-offs

- **Blast radius (v2, larger than v1 claimed):** 1 migration (4 columns + legacy stamp + one index);
  a new service (near-copy shell) + ~3 DAO CAS methods; **a refactor of the processing path** to
  produce an explicit `ProcessingOutcome` and to surface the Lucene write result (Finding 2/3a); an
  **auto-tag concurrency fix** (per-document lock, Finding 3b); DB-clock fenced leasing (Finding 5); a
  drain rewrite (Finding 1); an event flag + listener guard + regression test (Finding 4-adjacent).
- **Guarantee is scoped to first-time post-upload processing** (Finding 4 option A) unless
  generation-fencing (option B) is adopted; the `attach`/manual-reprocess residual is documented in
  #159.
- **The gap is narrowed at recovery time, not eliminated at the source:** a lost file is recovered on
  the next boot (up to `LEASE_TTL` after reboot for a prior-boot orphan), not at commit; there is
  still a window where it is unsearchable until restart.
- **Original webhook delivery is still best-effort** — this ADR suppresses a *duplicate* on replay; it
  does not make the *original* `FILE_CREATED`/`FILE_UPDATED` webhook durable. Out of scope for a
  derived-data fix; it is the outbox's job.

## Escalation — v2 verdict: is the fenced lease/backfill still materially simpler than a durable outbox?

The named alternative is a **durable event outbox**: a `T_ASYNC_EVENT` table into which every producer
`INSERT`s the event **in the same transaction** as its mutation (atomically durable with the commit),
a **continuously-running** dispatcher that claims and delivers undispatched rows and marks them
dispatched, and startup replay of anything undelivered. It is **durable at-least-once, requiring
consumer-side dedup** — *not* exactly-once (v1 overstated this).

**v2 verdict: the margin has narrowed materially. The backfill is still somewhat simpler and
lower-risk _for the narrowed (first-upload) scope_, chiefly because it does not rewrite the
event-firing mechanism and reuses the self-stopping-service shell — but it is no longer the
near-trivial #119 copy v1 implied, and for full correctness the outbox is a legitimate, arguably
better, one-time investment. This is a maintainer decision; I do not make it here.**

Facts driving the v2 verdict:

1. **Findings 2 and 3 are REQUIRED under BOTH designs — they do not differentiate.** An outbox
   guarantees the *event* is delivered at-least-once, but the *consumer* (`processFile`) still swallows
   failures, so the outbox **also** needs an explicit success result to decide ack-vs-retry (Finding 2)
   **and** idempotent keyed replay + auto-tag dedup arbitration (Finding 3), precisely because
   at-least-once means duplicate deliveries. Do these regardless of architecture.
2. **What the backfill must build that the outbox gets structurally:** recurring-obligation coverage
   (Finding 4) — the outbox durably enqueues *every* event (create/update/attach) for free, whereas
   the backfill needs generation-fencing (option B, ≥4 producer touch-points) to match, or accepts the
   narrowed guarantee (option A). This is a real correctness point for the outbox.
3. **What the outbox must build that the backfill avoids:** a new table + entity + DAO, a **rewrite of
   the fire path** (`ThreadLocalContext.fireAllAsyncEvents`, `:271-289`) to insert-in-transaction at
   **all four** producers, and a continuously-running dispatcher with its own multi-instance claim
   fencing. Larger, more foundational blast radius; touches every async event, not just file
   processing.
4. **Lease complexity (Findings 1, 5) is intrinsic to the *claim* model, not to the outbox's
   *dispatch-status* model** — an always-running dispatcher does not have the "self-stopped while a
   lease is live" hazard (Finding 1) in the same form, though multi-instance dispatch still needs
   fencing (Finding 5).
5. **Scope/impact:** the outbox additionally makes **webhook and document-event delivery durable**,
   which the backfill explicitly does not.

**Decision hinges on two maintainer questions** (crisp, for the maintainer to answer):
(a) Is **first-upload-only** recovery an acceptable guarantee, or must `attach`/manual-reprocess also
be covered? (b) Is **durable webhook/event delivery** a goal **now** or **later**? If (a)=first-upload
acceptable and (b)=later → the backfill is the smaller, lower-risk fix. If (a)=must-cover-all or
(b)=now → the outbox subsumes this work and is likely the better one-time investment. Either way,
**Findings 2 and 3 are done first**, because both architectures need them.

## Alternatives rejected

- **`FIL_CONTENT_C IS NULL` predicate** — legitimate for unsupported/OCR-off files; would loop forever.
- **Index-presence predicate** — a derived, rebuildable store; "present" ≠ "extracted" after a reindex.
- **Reprocess every null-marker row on first boot (no legacy stamp)** — mass re-OCR/re-tag/re-index of
  the whole corpus + re-fired side effects. Rejected for the bounded, one-time legacy stamp.
- **Completion in `processEvent`'s `finally`** — runs on every outcome including a throw (`:99-111`),
  would mark failed extractions/index-writes complete. Rejected (Finding 2).
- **`addDocument` (create) on the replay path** — non-idempotent; duplicates the Lucene doc on
  crash-retry. Rejected for keyed `updateFile` (Finding 3a).
- **Bare `WHERE processed IS NULL` completion / per-JVM wall-clock lease** — stale-worker clobber +
  false expiry. Rejected for token-fenced completion + DB-clock leases (Finding 5).
- **Self-stop on empty work-set (v1)** — strands live leases. Rejected for stop-on-zero-unprocessed
  (Finding 1).
- **Bare marker-clear for recurring obligations** — an older worker can complete a newer obligation.
  Rejected for generation-fencing (option B) or the explicitly-narrowed guarantee (option A), Finding 4.
- **Durable event outbox** — see Escalation. Correct and more general (covers recurring obligations +
  durable webhooks), larger blast radius; deferred to the maintainer as the explicit alternative,
  with Findings 2 and 3 common to both.
