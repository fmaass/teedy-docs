package com.sismics.docs.core.util;

import com.google.common.eventbus.Subscribe;
import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.util.TransactionBoundary.ClassifiedOutcome;
import com.sismics.docs.core.util.TransactionBoundary.DurableState;
import com.sismics.docs.core.util.TransactionBoundary.Outcome;
import com.sismics.docs.core.util.TransactionBoundary.OwnedResult;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Behavioral tests for the frame-scoped transaction completion machinery: the single firing authority
 * ({@link TransactionBoundary}), frame isolation via {@link ThreadLocalContext#pushFrame}/{@code
 * popFrame}, the checkpoint rotation, and the explicit committed/rolled-back outcome with its
 * post-commit failure semantics.
 *
 * <p>Async events are observed through the real (synchronous, under unit tests) async event bus with a
 * spy listener, so the assertions reflect what actually reaches the bus — not an in-test reconstruction
 * of the expected value.</p>
 */
public class TestTransactionBoundary extends BaseTransactionalTest {

    /** Marker event used only by these tests; unknown to every production listener. */
    public static final class ProbeEvent {
        private final String tag;

        public ProbeEvent(String tag) {
            this.tag = tag;
        }

        @Override
        public String toString() {
            return tag;
        }
    }

    /** Synchronous spy listener registered on the real async event bus. */
    public static final class Recorder {
        final List<ProbeEvent> received = new ArrayList<>();

        @Subscribe
        public void on(ProbeEvent event) {
            received.add(event);
        }
    }

    private Recorder recorder;

    @BeforeEach
    public void registerRecorder() {
        // Start each test on a clean base frame (the thread-local context is reused across tests on the
        // same thread; the base frame's registry / queue must not carry over).
        ThreadLocalContext.get().resetCurrentFrame();
        recorder = new Recorder();
        AppContext.getInstance().getAsyncEventBus().register(recorder);
    }

    @AfterEach
    public void unregisterRecorder() {
        AppContext.getInstance().getAsyncEventBus().unregister(recorder);
        // Do NOT cleanup() here: the base class teardown still needs the installed entity manager to
        // roll back its transaction. The next test's resetCurrentFrame() clears any residue.
    }

    // (a) An enclosing frame's queued event does NOT fire when a nested frame commits; it fires only
    // when the enclosing frame itself commits.
    @Test
    public void nestedFrameCommitFiresOnlyItsOwnEvents() {
        ThreadLocalContext context = ThreadLocalContext.get();
        context.addAsyncEvent(new ProbeEvent("outer"));

        EntityManager nestedEm = EMF.get().createEntityManager();
        context.pushFrame(nestedEm);
        try {
            context.addAsyncEvent(new ProbeEvent("nested"));
            TransactionBoundary.complete(DurableState.COMMITTED);
        } finally {
            context.popFrame();
            TransactionBoundary.closeQuietly(nestedEm);
        }

        Assertions.assertEquals(List.of("nested"), tags(recorder.received),
                "committing the nested frame must fire only the nested frame's event, never the enclosing frame's");

        TransactionBoundary.complete(DurableState.COMMITTED);
        Assertions.assertEquals(List.of("nested", "outer"), tags(recorder.received),
                "the enclosing frame's event fires when the enclosing frame itself commits");
    }

    // (a, rollback branch) An enclosing frame that rolls back discards its own queued events.
    @Test
    public void frameRollbackDiscardsItsEvents() {
        ThreadLocalContext context = ThreadLocalContext.get();
        context.addAsyncEvent(new ProbeEvent("outer"));

        TransactionBoundary.complete(DurableState.ROLLED_BACK);

        Assertions.assertTrue(recorder.received.isEmpty(),
                "a rolled-back frame must discard its queued events, firing none");
    }

    // (b) A nested frame that rolls back must NOT discard the enclosing frame's queued work.
    @Test
    public void nestedFrameRollbackDoesNotDiscardEnclosingFrameWork() {
        ThreadLocalContext context = ThreadLocalContext.get();
        context.addAsyncEvent(new ProbeEvent("outer"));

        EntityManager nestedEm = EMF.get().createEntityManager();
        context.pushFrame(nestedEm);
        try {
            context.addAsyncEvent(new ProbeEvent("nested"));
            TransactionBoundary.complete(DurableState.ROLLED_BACK);
        } finally {
            context.popFrame();
            TransactionBoundary.closeQuietly(nestedEm);
        }

        Assertions.assertTrue(recorder.received.isEmpty(),
                "the nested rollback discards only the nested frame's events");

        // The enclosing frame's queued work survived the nested rollback and fires on its own commit.
        TransactionBoundary.complete(DurableState.COMMITTED);
        Assertions.assertEquals(List.of("outer"), tags(recorder.received),
                "the enclosing frame's queued work must survive a nested-frame rollback");
    }

    // (c) At a checkpoint commit, events queued before the checkpoint belong to the finished
    // transaction and fire at the boundary; events queued after belong to the fresh frame.
    @Test
    public void checkpointFiresPreCheckpointEventsAndRotatesTheFrame() {
        ThreadLocalContext context = ThreadLocalContext.get();
        context.addAsyncEvent(new ProbeEvent("before-checkpoint"));

        // TransactionUtil.commit() commits the current transaction, fires its queued events, rotates
        // the frame, and re-begins a fresh transaction on the same entity manager.
        TransactionUtil.commit();

        Assertions.assertEquals(List.of("before-checkpoint"), tags(recorder.received),
                "an event queued before the checkpoint fires at the checkpoint boundary");

        context.addAsyncEvent(new ProbeEvent("after-checkpoint"));
        Assertions.assertEquals(List.of("before-checkpoint"), tags(recorder.received),
                "an event queued after the checkpoint belongs to the fresh frame and does not fire at the boundary");

        TransactionBoundary.complete(DurableState.COMMITTED);
        Assertions.assertEquals(List.of("before-checkpoint", "after-checkpoint"), tags(recorder.received),
                "the post-checkpoint event fires when the fresh frame completes");
    }

    // (d) A durable commit runs the after-commit callbacks and NEVER the after-rollback callbacks, and
    // the outcome reports the durable commit even when a post-commit callback fails.
    @Test
    public void durableCommitNeverRunsAfterRollbackEvenWithPostCommitFailure() {
        List<String> ran = new ArrayList<>();
        var registry = ThreadLocalContext.get().getCompletionRegistry();
        registry.registerAfterCommit(() -> {
            throw new IllegalStateException("post-commit callback failure");
        });
        registry.registerAfterRollback(() -> ran.add("after-rollback"));
        registry.registerAfterCompletion(() -> ran.add("after-completion"));

        Outcome outcome = TransactionBoundary.complete(DurableState.COMMITTED);

        Assertions.assertTrue(outcome.isCommitted(), "the outcome must report the durable commit");
        Assertions.assertNotNull(outcome.getDeferredFailure(),
                "the post-commit callback failure must be recorded as a deferred (secondary) failure");
        Assertions.assertFalse(ran.contains("after-rollback"),
                "after-rollback callbacks must NEVER run on a durable commit");
        Assertions.assertTrue(ran.contains("after-completion"),
                "after-completion callbacks run after the outcome-specific callbacks, even if one threw");
    }

    // (e) The first after-commit callback throwing must not stop the remaining after-commit callbacks
    // or the after-completion callbacks; the secondary failure is suppressed and recorded.
    @Test
    public void postCommitCallbackFailureDoesNotStopLaterCallbacks() {
        List<String> ran = new ArrayList<>();
        var registry = ThreadLocalContext.get().getCompletionRegistry();
        registry.registerAfterCommit(() -> {
            throw new IllegalStateException("first after-commit callback failure");
        });
        registry.registerAfterCommit(() -> ran.add("second-after-commit"));
        registry.registerAfterCommit(() -> ran.add("third-after-commit"));
        registry.registerAfterCompletion(() -> ran.add("after-completion"));

        Outcome outcome = TransactionBoundary.complete(DurableState.COMMITTED);

        Assertions.assertEquals(List.of("second-after-commit", "third-after-commit", "after-completion"), ran,
                "a throwing callback must not abort the remaining callbacks of its phase or the completion phase");
        Assertions.assertNotNull(outcome.getDeferredFailure(), "the first failure must be recorded on the outcome");
    }

    // (j) The favorite-recovery rotation (the shared helper FavoriteDao.beginFreshTransaction routes
    // through): the finished transaction's events are discarded at the rollback boundary and do NOT
    // fire when the fresh transaction commits — only the fresh transaction's own work fires.
    @Test
    public void rollbackRotationDiscardsFinishedTxEventsThenFreshTxFiresAlone() {
        ThreadLocalContext context = ThreadLocalContext.get();
        context.addAsyncEvent(new ProbeEvent("tx1-poisoned"));

        // Rotate the poisoned transaction out as rolled back, exactly as FavoriteDao.beginFreshTransaction
        // does after rolling back the constraint-violating insert.
        TransactionBoundary.rotate(DurableState.ROLLED_BACK);

        Assertions.assertTrue(recorder.received.isEmpty(),
                "tx1's events are discarded at the rollback rotation, firing none");

        context.addAsyncEvent(new ProbeEvent("tx2-work"));
        TransactionBoundary.complete(DurableState.COMMITTED);

        Assertions.assertEquals(List.of("tx2-work"), tags(recorder.received),
                "tx1's discarded event must not fire on tx2's commit; only tx2's own work fires");
    }

    // Re-entrancy: an after-commit callback that queues a new event DURING dispatch must have that event
    // fired within the SAME completion (append-and-drain), never dropped onto a frame about to be popped.
    @Test
    public void eventQueuedByCallbackDuringDispatchStillFires() {
        ThreadLocalContext context = ThreadLocalContext.get();
        boolean[] sawCompleting = {false};
        context.getCompletionRegistry().registerAfterCommit(() -> {
            sawCompleting[0] = ThreadLocalContext.get().isCurrentFrameCompleting();
            ThreadLocalContext.get().addAsyncEvent(new ProbeEvent("queued-during-dispatch"));
        });

        TransactionBoundary.complete(DurableState.COMMITTED);

        Assertions.assertTrue(sawCompleting[0], "a callback running during dispatch observes the frame as COMPLETING");
        Assertions.assertEquals(List.of("queued-during-dispatch"), tags(recorder.received),
                "an event queued during dispatch fires within the same completion (append-and-drain), not dropped");
    }

    // (k) tx.commit() throws -> IN_DOUBT: neither callback set runs, no event fires, and the failure is
    // reported on the classified outcome (the caller propagates it).
    @Test
    public void commitThrowIsInDoubtAndFiresNothing() {
        List<String> ran = new ArrayList<>();
        var registry = ThreadLocalContext.get().getCompletionRegistry();
        registry.registerAfterCommit(() -> ran.add("after-commit"));
        registry.registerAfterRollback(() -> ran.add("after-rollback"));
        ThreadLocalContext.get().addAsyncEvent(new ProbeEvent("in-doubt"));

        FakeTransactionSupport.FakeTx tx = new FakeTransactionSupport.FakeTx();
        tx.active = true;
        tx.commitThrows = true;
        ClassifiedOutcome co = TransactionBoundary.tryCommit(tx);
        Assertions.assertEquals(DurableState.IN_DOUBT, co.getState(), "a thrown commit is IN_DOUBT, not COMMITTED");
        Assertions.assertNotNull(co.getFailure(), "the commit failure is carried on the outcome");

        Outcome outcome = TransactionBoundary.complete(DurableState.IN_DOUBT);
        Assertions.assertEquals(DurableState.IN_DOUBT, outcome.getDurableState());
        Assertions.assertTrue(ran.isEmpty(), "IN_DOUBT runs neither after-commit nor after-rollback callbacks");
        Assertions.assertTrue(recorder.received.isEmpty(), "IN_DOUBT fires no async events");
    }

    // (l) tx.rollback() throws -> IN_DOUBT (not a false ROLLED_BACK); the ORIGINAL work failure stays
    // primary, and no after-rollback compensation runs.
    @Test
    public void rollbackThrowIsInDoubtWithOriginalPrimary() {
        List<String> ran = new ArrayList<>();
        ThreadLocalContext.get().getCompletionRegistry().registerAfterRollback(() -> ran.add("after-rollback"));

        RuntimeException original = new IllegalStateException("original work failure");
        FakeTransactionSupport.FakeTx tx = new FakeTransactionSupport.FakeTx();
        tx.active = true;
        tx.rollbackThrows = true;
        boolean[] closed = {false};
        boolean[] open = {true};
        EntityManager em = FakeTransactionSupport.fakeEntityManager(tx, closed, open);

        OwnedResult result = TransactionBoundary.finalizeOwned(em, tx, false, original);

        Assertions.assertEquals(DurableState.IN_DOUBT, result.getState(),
                "a thrown rollback is IN_DOUBT, never a false ROLLED_BACK");
        Assertions.assertSame(original, result.getPropagate(),
                "the original work failure stays primary over the rollback failure");
        Assertions.assertTrue(closed[0], "the entity manager is closed");
        Assertions.assertTrue(ran.isEmpty(), "no after-rollback compensation runs on IN_DOUBT");
    }

    // (m) Checkpoint re-begin failure after a durable commit propagates and closes the orphaned entity
    // manager, while tx1's COMMITTED outcome (its fired events) is preserved, not relabelled.
    @Test
    public void checkpointReBeginFailurePropagatesClosesOrphanKeepingTx1Committed() {
        ThreadLocalContext.get().addAsyncEvent(new ProbeEvent("tx1"));
        TransactionBoundary.rotate(DurableState.COMMITTED);
        Assertions.assertEquals(List.of("tx1"), tags(recorder.received),
                "tx1's durable commit fired its events at the rotation");

        FakeTransactionSupport.FakeTx tx = new FakeTransactionSupport.FakeTx();
        tx.active = false;
        tx.beginThrows = true;
        boolean[] closed = {false};
        boolean[] open = {true};
        EntityManager em = FakeTransactionSupport.fakeEntityManager(tx, closed, open);

        Assertions.assertThrows(RuntimeException.class,
                () -> TransactionBoundary.beginContinuation(em, tx),
                "a continuation re-begin failure must propagate so the caller aborts");
        Assertions.assertTrue(closed[0], "the orphaned entity manager is closed on a continuation-begin failure");
        Assertions.assertEquals(List.of("tx1"), tags(recorder.received),
                "tx1's committed outcome is preserved; its fired events are not un-fired");
    }

    // (m, symmetric) FavoriteDao recovery: a re-begin failure after the deliberate tx1 rollback also
    // propagates and closes the orphan; tx1's events were discarded and none fire.
    @Test
    public void favoriteRecoveryReBeginFailurePropagatesClosesOrphanAfterRollback() {
        ThreadLocalContext.get().addAsyncEvent(new ProbeEvent("tx1-poisoned"));
        TransactionBoundary.rotate(DurableState.ROLLED_BACK);
        Assertions.assertTrue(recorder.received.isEmpty(), "tx1's events were discarded at the rollback rotation");

        FakeTransactionSupport.FakeTx tx = new FakeTransactionSupport.FakeTx();
        tx.active = false;
        tx.beginThrows = true;
        boolean[] closed = {false};
        boolean[] open = {true};
        EntityManager em = FakeTransactionSupport.fakeEntityManager(tx, closed, open);

        Assertions.assertThrows(RuntimeException.class,
                () -> TransactionBoundary.beginContinuation(em, tx),
                "the favorite recovery's continuation re-begin failure must propagate");
        Assertions.assertTrue(closed[0], "the orphaned entity manager is closed");
    }

    // (n) An after-completion callback that throws is a deferred observer failure: it does NOT mask the
    // primary exception the owner must propagate, and finalization still completes (em closed).
    @Test
    public void afterCompletionThrowDoesNotMaskPrimary() {
        RuntimeException original = new IllegalStateException("original work failure");
        ThreadLocalContext.get().getCompletionRegistry().registerAfterCompletion(() -> {
            throw new IllegalStateException("after-completion failure");
        });

        FakeTransactionSupport.FakeTx tx = new FakeTransactionSupport.FakeTx();
        tx.active = true;
        boolean[] closed = {false};
        boolean[] open = {true};
        EntityManager em = FakeTransactionSupport.fakeEntityManager(tx, closed, open);

        OwnedResult result = TransactionBoundary.finalizeOwned(em, tx, false, original);

        Assertions.assertEquals(DurableState.ROLLED_BACK, result.getState(), "the rollback succeeded");
        Assertions.assertSame(original, result.getPropagate(),
                "the after-completion failure (a deferred observer failure) must not mask the primary exception");
        Assertions.assertTrue(closed[0], "finalization still completes: the entity manager is closed");
        Assertions.assertEquals(1, tx.rollbackCount, "the rollback ran exactly once");
    }

    // (Issue 4) A post-durable-commit close failure is an observer failure: the outcome stays COMMITTED,
    // nothing is propagated, and the after-commit callbacks ran. A close that throws an Error, by
    // contrast, is NOT suppressed and propagates.
    @Test
    public void postCommitCloseRuntimeExceptionSuppressedOutcomeStaysCommitted() {
        List<String> ran = new ArrayList<>();
        ThreadLocalContext.get().getCompletionRegistry().registerAfterCommit(() -> ran.add("after-commit"));

        FakeTransactionSupport.FakeTx tx = new FakeTransactionSupport.FakeTx();
        tx.active = true;
        boolean[] closed = {false};
        boolean[] open = {true};
        EntityManager em = FakeTransactionSupport.fakeEntityManager(tx, closed, open,
                new IllegalStateException("injected close failure"));

        OwnedResult result = TransactionBoundary.finalizeOwned(em, tx, true, null);

        Assertions.assertTrue(result.isCommitted(),
                "a post-commit close RuntimeException must not flip the durable outcome");
        Assertions.assertNull(result.getPropagate(), "a committed outcome propagates nothing");
        Assertions.assertEquals(List.of("after-commit"), ran, "the after-commit callbacks ran despite the close failure");
    }

    @Test
    public void closeErrorIsNotSuppressed() {
        FakeTransactionSupport.FakeTx tx = new FakeTransactionSupport.FakeTx();
        tx.active = true;
        boolean[] closed = {false};
        boolean[] open = {true};
        EntityManager em = FakeTransactionSupport.fakeEntityManager(tx, closed, open,
                new OutOfMemoryError("injected close Error"));

        Assertions.assertThrows(OutOfMemoryError.class,
                () -> TransactionBoundary.finalizeOwned(em, tx, true, null),
                "an Error from close (OOM, etc.) must propagate, never be suppressed as an observer failure");
    }

    // (Issue 6) A per-event SUBMISSION failure must not abort the drain: later committed events still
    // fire. A null event makes Guava's EventBus.post() itself throw a NullPointerException (a real
    // submission failure, unlike a throwing subscriber which Guava routes to its exception handler and
    // never propagates). This drives fireAllAsyncEvents() DIRECTLY (not through complete(), whose
    // after-completion re-drain would retry an aborted drain and mask the regression): with the per-event
    // guard the drain continues and the following event fires; without it fireAllAsyncEvents() throws the
    // NPE and the following event is never delivered.
    @Test
    public void perEventSubmissionFailureDoesNotStopLaterEventsDraining() {
        ThreadLocalContext context = ThreadLocalContext.get();
        context.addAsyncEvent(null); // EventBus.post(null) throws NullPointerException
        context.addAsyncEvent(new ProbeEvent("after-failed-submission"));

        context.fireAllAsyncEvents();

        Assertions.assertEquals(List.of("after-failed-submission"), tags(recorder.received),
                "a submission failure on one event must not abort the drain; later committed events still fire");
    }

    // (Issue 3) The OIDC fresh-frame path must GUARANTEE the nested frame is popped and the request EM
    // restored even if closing the fresh manager throws an Error. Injecting a manager whose
    // getTransaction() throws (so the backstop close in the finally runs on an open manager) and whose
    // close() throws an Error reproduces the leak scenario: the frame must still be popped.
    @Test
    public void newFrameIsPoppedAndRequestEmRestoredEvenIfCloseThrowsError() {
        ThreadLocalContext context = ThreadLocalContext.get();
        EntityManager requestEm = context.peekEntityManager();
        int depthBefore = context.frameDepth();

        FakeTransactionSupport.FakeTx tx = new FakeTransactionSupport.FakeTx();
        boolean[] closed = {false};
        boolean[] open = {true};
        EntityManager freshEm = FakeTransactionSupport.fakeEntityManager(tx, closed, open,
                new OutOfMemoryError("injected close Error"),
                new IllegalStateException("injected getTransaction failure"));

        Assertions.assertThrows(Throwable.class,
                () -> TransactionBoundary.finalizeOwnedInNewFrame(freshEm, requestEm, em -> null),
                "the failure propagates");

        Assertions.assertEquals(depthBefore, context.frameDepth(),
                "the nested frame is popped even though close threw an Error");
        Assertions.assertSame(requestEm, context.peekEntityManager(),
                "the request frame's entity manager is restored even though close threw an Error");
    }

    private static List<String> tags(List<ProbeEvent> events) {
        List<String> out = new ArrayList<>();
        for (ProbeEvent event : events) {
            out.add(event.toString());
        }
        return out;
    }
}
