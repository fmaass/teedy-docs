package com.sismics.docs.application.document;

import java.util.function.Supplier;

/**
 * Transaction abstraction over the fixed A1 primitives. No JPA types cross this boundary.
 *
 * <p>Behavioral contract (pinned; see the Phase G design note §3):</p>
 * <ol>
 *   <li><b>Ownership detection.</b> {@code required} joins an already-live transaction; only when
 *       none is active does it own a fresh entity manager + transaction. Inside a normal request the
 *       filter owns the transaction, so both document endpoints JOIN it.</li>
 *   <li><b>Nested {@code required}.</b> Under a live transaction it runs inline (join). Reached while
 *       the enclosing frame is completing, it runs in an isolated frame — not exercised by this seed.</li>
 *   <li><b>Exception → rollback.</b> A failure propagates as a thrown exception; the edge translates
 *       it into a non-2xx {@code WebApplicationException} and NEVER returns an error {@code Response},
 *       so the filter's status-driven finalization rolls back exactly as the legacy path. When
 *       {@code required} owns the transaction (background use), a work failure rolls back and rethrows.</li>
 *   <li><b>Read-only {@code query}.</b> Runs through the same owner-or-join machinery (a SELECT still
 *       needs a live transaction under resource-local Hibernate). "Read-only" is an intent marker:
 *       the query path queues no events and registers no callbacks.</li>
 *   <li><b>Callback ordering.</b> {@code afterCommit} callbacks run on durable commit, before the
 *       queued async events fire.</li>
 *   <li><b>{@code afterCommit} across a checkpoint.</b> It binds to the transaction segment active at
 *       registration time, not the whole request — pinned for future slices; this seed checkpoints
 *       nothing.</li>
 * </ol>
 */
public interface UnitOfWork {

    /**
     * Runs {@code work} in a transaction (joining a live one, or owning a fresh one).
     *
     * @param work The unit of work
     */
    void required(Runnable work);

    /**
     * Result-bearing overload of {@link #required(Runnable)} — the same concept, not a distinct
     * operation.
     *
     * @param work The unit of work producing a result
     * @param <T>  Result type
     * @return The work's result
     */
    <T> T required(Supplier<T> work);

    /**
     * Runs a read-only {@code read} in a transaction. Same machinery as {@link #required(Supplier)};
     * the name marks read-only intent (no events, no callbacks on this path).
     *
     * @param read The read to run
     * @param <T>  Result type
     * @return The read's result
     */
    <T> T query(Supplier<T> read);

    /**
     * Registers a callback to run after the current transaction durably commits.
     *
     * @param callback The after-commit callback
     * @throws IllegalStateException when no entity manager with an active transaction is installed, or
     *                               the current frame is completing (registering then would silently
     *                               attach the work to a later transaction on this thread)
     */
    void afterCommit(Runnable callback);
}
