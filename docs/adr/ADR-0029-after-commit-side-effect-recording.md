# ADR-0029 — Record read side effects after commit, in their own transaction, never failing the read

- **Status:** accepted
- **Date:** 2026-08-26
- **Issue:** #300 (document and file access counters) — the review of its first implementation
  produced this decision.

## Context

Issue #300 counts document and file accesses. The first implementation inserted the
`T_ACCESS_EVENT` row inside the read request's own transaction. Review showed two problems: a
failure while recording (a constraint, an exhausted pool, a transient database disconnect) would
turn a successful read into an HTTP 500, and the insert extended the read's transaction footprint
on every document and file view.

## Decision

Side-effect recording triggered by a read — access events today, any future usage or audit
recorder — runs after the read's transaction has committed, in its own short transaction, and its
failure is logged and swallowed. The read's response is never affected by the recorder. The
mechanism is `AccessRecordingUtil` (docs-web `rest/util`), which the resources call once their
transaction has committed.

## Consequences

- Reads stay correct and fast when the recorder fails; counters may under-count on such failures.
  Accepted: the counters are informational, not a ledger.
- Recording is not atomic with the read: a read whose response failed to reach the client is still
  counted. Accepted for the same reason.
- Every later recorder uses the same helper. A recorder that must be atomic with the write it
  observes (an audit trail of a mutation) is a different pattern and not covered by this decision.

## Alternatives considered

- Inline insert inside the read transaction — rejected: a recorder failure fails the read, and the
  read's transaction grows on every view.
- An asynchronous queue or executor — deferred: Teedy runs as a single JVM for a handful of users,
  and an extra executor adds lifecycle and shutdown handling for no measurable gain at this volume.
  Revisit if recording volume grows.
