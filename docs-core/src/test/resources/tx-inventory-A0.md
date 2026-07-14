# Transaction-machinery inventory (Phase A0 characterization)

Map of the JPA transaction ownership and async-event machinery as it exists in the
current tree. This is a characterization artifact only: it records CURRENT behavior
(including known-broken paths) so a later change has a fixed reference and the
characterization tests have a rationale. No behavior is judged or changed here.

All line numbers below were read directly from the current source and verified with
`grep`. Where they differ from an upstream planning note, the divergence is called
out under "Divergences" at the end.

## Transaction owners (the three `createEntityManager()` sites)

There are exactly three production `createEntityManager()` call sites (verified:
`grep -rn createEntityManager .../src/main`). Each owns a JPA `EntityManager` +
resource-local transaction lifecycle.

1. **TransactionUtil** — background/service owner.
   `docs-core/.../core/util/TransactionUtil.java`
   - `handle(Runnable)` reads the existing context EM at line 28 via the MUTATING
     `ThreadLocalContext.getEntityManager()` (see below) before its
     already-in-context check (`em != null && em.isOpen()`, line 30). If already in
     a context it runs the runnable inline and returns (line 30-34) — it does NOT
     own a transaction on that path.
   - Owner path: `EMF.get().createEntityManager()` at line 37 (wrapped in a
     try/catch that only LOGS on failure, line 38-40); `setEntityManager` line 42;
     `tx.begin()` line 44.
   - Runnable runs in a try at line 47. On exception: `cleanup()` line 49, log line
     51, `rollback()` line 56, `close()` line 60, then `return` line 65 — the
     exception is SWALLOWED, never rethrown.
   - Success path: `commit()` line 71, `close()` line 74; `fireAllAsyncEvents()`
     line 83; `cleanup()` line 85.
   - `commit()` (the static checkpoint helper, line 91) commits AND re-begins a
     fresh transaction: `tx.commit()` line 93, `tx.begin()` line 94.
   - Production call sites of `handle`: **22** (23rd `grep` hit is a Javadoc
     `{@link ...#handle}` in OidcResource:1093, not a call).
   - Production call sites of `commit`: **2** — `RasterGenerationUtil.java:89` and
     `AppResource.java:1302` (other `grep` hits are comments).

2. **RequestContextFilter** — REST request owner.
   `docs-web-common/.../util/filter/RequestContextFilter.java`
   - `createEntityManager()` line 78 (throws `ServletException` on failure, line
     80 — unlike TransactionUtil which only logs); `setEntityManager` line 83;
     `tx.begin()` line 85.
   - In-flight exception path: `cleanup()` line 91; for non-IOException, rollback
     line 100, close line 104, rethrow as `ServletException` line 109.
   - End-of-request finalization delegates to `commitAndFinalize(em, status, resp)`
     (line 166): commits for 2xx/3xx (line 174), otherwise rolls back (line 181),
     always closes in a finally (line 187). Returns whether it committed;
     `fireAllAsyncEvents()` is fired only when committed (line 132), else
     `discardAsyncEvents()` (line 139) — the issue-#63 guard.

3. **OidcResource.runOnFreshTransaction()** — resource-local fresh-context owner.
   `docs-web/.../rest/resource/OidcResource.java` (method line 1097).
   - Saves the request EM (line 1099); creates a fresh EM at line 1100; installs it
     via `setEntityManager` line 1105; `tx.begin()` line 1107; `work.apply` line
     1108; `tx.commit()` line 1109. Finally: rollback-if-active line 1114, close
     line 1120, and RESTORE the request EM line 1124. The request transaction stays
     open across this call by design.

## Boundary mutator (characterize, NOT an owner)

**FavoriteDao.beginFreshTransaction()** — `docs-core/.../core/dao/FavoriteDao.java`,
method at line 156. It does NOT create an EM; it takes the current context EM,
`clear()`s it (line 157), rolls back the poisoned/rollback-only transaction if
active (line 160), and `begin()`s a replacement (line 162). Invoked on the
constraint-recovery paths at line 105 and line 130 of `create(...)`. It mutates the
request-owned transaction in place, which is why it is in scope for characterization
but is not a fourth owner.

## Out of scope (named, deliberately NOT tested)

The JDBC bootstrap/migration path is a separate JDBC context (not a JPA
`EntityManager`) and is explicitly out of scope for this phase:
- `EMF.java` — `createEntityManagerFactory("transactions-optional", ...)` at line 61
  (this builds the FACTORY, not an EM), plus the JDBC migration bootstrap it drives.
- `DbOpenHelper.java` — schema/migration bootstrap transactions.

## Async-event machinery

`ThreadLocalContext` — `docs-core/.../util/context/ThreadLocalContext.java`
- `getEntityManager()` (line 64) is a MUTATING getter: when the stored EM is
  non-null AND open it calls `flush()` (line 67) then `clear()` (line 68) before
  returning it; otherwise it returns the field as-is (null or a closed EM) without
  touching it. This deliberately disables the L1 cache on every read. Because 30
  production sites and all three owners route through this getter, a `flush()`
  happens as a side effect of merely asking for the EM.
- `addAsyncEvent(Object)` (line 87) appends to the per-request queue. Production
  call sites: **30** (31st `grep` hit is the method definition itself).
- `fireAllAsyncEvents()` (line 94) drains the queue, posting each to
  `AppContext.getInstance().getAsyncEventBus()` (post at line 99).
- `discardAsyncEvents()` (line 111) clears the queue without firing (rollback path).

`AppContext` — `docs-core/.../core/model/context/AppContext.java`
- Both buses are (re)created in `resetEventBus()`: `asyncEventBus` assigned line
  197, `mailEventBus` assigned line 208.
- **12 subscriber registrations**: 9 on `asyncEventBus` (lines 198-206) and 3 on
  `mailEventBus` (lines 209-211).
- `newAsyncEventBus()` (line 232) returns a SYNCHRONOUS `EventBus` under unit tests
  (`EnvironmentUtil.isUnitTest()`, branch at line 251-252) and a real
  `AsyncEventBus` backed by a bounded executor in production (line 263). Every
  characterization test therefore runs the event bus synchronously.

## Characterization tests (this phase)

- `TestTransactionUtilCharacterization` (`.../core/util/`): pins that the owner path
  of `handle(Runnable)` SWALLOWS a runnable exception (does not propagate) and
  cleans up the thread-local context afterward. This is a known-broken path; a later
  phase is expected to invert it, at which point this test is updated deliberately.
- `TestThreadLocalContextCharacterization` (`.../util/context/`): pins the mutating
  `getEntityManager()` — flush-then-clear on an open manager (a persisted-but-unflushed
  row reaches the DB and the entity is detached), and the no-op on the null/closed path.

### Not pinned by a test (documented instead, per the no-brittle-mock rule)

`TransactionUtil.handle` null-EM path: when `EMF.get().createEntityManager()` at line
37 yields `null` (or the try/catch at 38-40 swallows a creation failure leaving `em`
null), the very next statement `em.getTransaction()` at line 43 throws a
`NullPointerException` OUTSIDE the runnable try/catch (the try starts at line 46), so
it propagates uncaught. Reaching this state requires the static `EMF.get()` to hand
back a null/failing EM, which is only achievable by static-mocking `EMF` or supplying
a broken persistence configuration — both brittle and environment-coupled, and the
first would mock a collaborator of the unit under test. Per the phase rule (no
brittle mock, no mocking the unit under test) this path is recorded here rather than
pinned by a fake or mock-heavy test.

## Divergences from the planning note

- **Reconciled (no action):** `handle` = 22 real call sites (23rd is a Javadoc
  link); `commit` = 2 real call sites (rest are comments); `addAsyncEvent` = 30 call
  sites (31st is the definition); 12 bus registrations (9 async + 3 mail); exactly 3
  `createEntityManager()` owners — no fourth owner exists.
- **Line-label drift in TransactionUtil:** the current file has `begin` at line 44
  (not 37 — line 37 is `createEntityManager`), `rollback` at line 56, `commit` at
  line 71, and the `commit()` re-begin at line 94 (the method opens at line 91).
  The planning note's internal line labels for this one file do not match the current
  source; the STRUCTURE (one begin, one rollback, one commit, a re-beginning
  `commit()`, and the getEntityManager read at line 28) is intact. This is label
  drift only, not a structural or semantic divergence.
