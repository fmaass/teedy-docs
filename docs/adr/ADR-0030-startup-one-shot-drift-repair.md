# ADR-0030 — Repair legacy data drift in idempotent startup one-shots, not SQL migrations

- **Status:** accepted
- **Date:** 2026-09-02
- **Issue:** #312 (workflow start fails after a target group was renamed) — the repair of route
  models whose step blobs still name a group by its pre-rename id produced this decision.

## Context

Issue #312 showed installs where a group had been renamed before the v3.3.0 rename repair existed:
`T_GROUP` kept the old name as the id, and the seeded route model's step blob (`RTM_STEPS_C`, a JSON
document) still named the target by that old name. The fix made resolution tolerant (name, then id)
and derived the "incomplete" flag from the blob, but the drifted names were still on disk: the
route-model editor showed the stale name and every later resolver would have carried the fallback
forever. The data had to be rewritten once.

The repository's migration mechanism is SQL only (`db/update/dbupdate-NNN-0.sql`, one file per
number, applied by `DbOpenHelper` and mirrored in `db.version` across three files). Rewriting a
name inside a JSON blob portably in both H2 and PostgreSQL SQL is not practical, and the repair has
to reuse the Java rename-repair machinery (`RouteModelStepUtil`) so that the derived target index
is re-synchronised the same way a live rename does it.

## Decision

Legacy data drift that a SQL migration cannot express portably is repaired by an idempotent
one-shot that runs at application start (`AppContext.startUp`), in Java, reusing the same code the
live write path uses. The one-shot must:

- decide per row from the current data whether a repair applies (here: the stored name equals a
  live group's id and resolves to no group by name) and rewrite nothing otherwise;
- run inside one transaction per repaired row set and re-synchronise every derived index the live
  path maintains;
- be safe to run on every start (a second run is a no-op) and log what it changed at INFO;
- leave `db.version` untouched, because it does not change the schema.

`RouteModelTargetRepairUtil` is the first instance.

## Consequences

- The repair runs on every start, so its cost must stay proportional to the drifted rows, not to
  the table; the guard predicate is the first thing to read when a start slows down.
- A repair that needs a schema change is still a migration; this decision covers data drift only.
- Two repairs of the same data in one release are ordered by their position in `startUp`; a later
  repair that depends on an earlier one states so in its javadoc.
- The unit test for a repair proves three things: the drifted row is rewritten, a legitimately
  named row that merely looks drifted is left alone, and the second run changes nothing.

## Alternatives considered

- A SQL migration — rejected: no portable JSON rewrite across H2 and PostgreSQL, and it could not
  reuse the Java rename repair that keeps `T_ROUTE_MODEL_TARGET` in sync.
- Resolution fallback only, no rewrite — rejected: the stale names would have stayed visible in
  the editor and every resolver would carry the fallback indefinitely.
- A maintenance endpoint an admin triggers — rejected: nobody would know to call it; the drift is
  invisible until a workflow fails to start.
