package com.sismics.docs.core.util;

import com.google.common.eventbus.Subscribe;
import com.sismics.BaseTest;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.util.TransactionBoundary.DurableState;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the REAL transaction owners end-to-end — {@link TransactionUtil#handle(Runnable)} on its owner
 * path and the checkpoint {@link TransactionUtil#commit()} — so the failure / re-entrancy / cleanup
 * wiring is exercised through the actual entry points (not the boundary helper in isolation). The
 * handle owner path needs NO transactional context installed, so this extends {@link BaseTest} and
 * manages the thread-local context itself.
 */
public class TestTransactionOwners extends BaseTest {

    /** Marker event; unknown to every production listener. */
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

    public static final class Recorder {
        final List<ProbeEvent> received = new ArrayList<>();

        @Subscribe
        public void on(ProbeEvent event) {
            received.add(event);
        }
    }

    private Recorder recorder;

    @BeforeEach
    public void setUp() {
        ThreadLocalContext.cleanup();
        recorder = new Recorder();
        AppContext.getInstance().getAsyncEventBus().register(recorder);
    }

    @AfterEach
    public void tearDown() {
        AppContext.getInstance().getAsyncEventBus().unregister(recorder);
        ThreadLocalContext.cleanup();
    }

    // (Issue 1) A nested handle() reached DURING the outer's after-commit callback must run in its OWN
    // pushed frame — it must NOT reuse the completing frame and re-dispatch its registry (which would
    // recurse or run the outer committed frame's after-rollback set).
    @Test
    public void nestedHandleDuringAfterCommitRunsInItsOwnFrame() {
        AtomicInteger outerAfterCommit = new AtomicInteger();
        AtomicInteger outerAfterRollback = new AtomicInteger();
        AtomicInteger nestedRuns = new AtomicInteger();
        AtomicInteger depthDuringNested = new AtomicInteger();

        TransactionUtil.handle(() -> {
            var registry = ThreadLocalContext.get().getCompletionRegistry();
            registry.registerAfterCommit(() -> {
                outerAfterCommit.incrementAndGet();
                TransactionUtil.handle(() -> {
                    nestedRuns.incrementAndGet();
                    depthDuringNested.set(ThreadLocalContext.get().frameDepth());
                });
            });
            registry.registerAfterRollback(outerAfterRollback::incrementAndGet);
        });

        Assertions.assertEquals(1, outerAfterCommit.get(),
                "the outer after-commit callback runs exactly once (no re-dispatch / recursion)");
        Assertions.assertEquals(0, outerAfterRollback.get(),
                "the outer committed frame's after-rollback callbacks must NEVER run");
        Assertions.assertEquals(1, nestedRuns.get(), "the nested unit ran");
        Assertions.assertEquals(2, depthDuringNested.get(),
                "the nested handle pushed its OWN frame (did not reuse the completing frame)");
        Assertions.assertEquals(1, ThreadLocalContext.get().frameDepth(),
                "the nested frame was popped; back to the base frame");
        Assertions.assertNull(ThreadLocalContext.get().peekEntityManager(),
                "the outer owner cleaned the thread-local context");
    }

    // (Issue 1) A nested unit that FAILS during the outer's after-commit callback must not run the outer
    // committed frame's after-rollback compensation.
    @Test
    public void nestedHandleFailureDuringAfterCommitDoesNotRunOuterAfterRollback() {
        AtomicInteger outerAfterCommit = new AtomicInteger();
        AtomicInteger outerAfterRollback = new AtomicInteger();

        TransactionUtil.handle(() -> {
            var registry = ThreadLocalContext.get().getCompletionRegistry();
            registry.registerAfterCommit(() -> {
                outerAfterCommit.incrementAndGet();
                Assertions.assertThrows(IllegalStateException.class,
                        () -> TransactionUtil.handle(() -> {
                            throw new IllegalStateException("nested unit failure");
                        }),
                        "the nested unit's failure must propagate out of the nested handle");
            });
            registry.registerAfterRollback(outerAfterRollback::incrementAndGet);
        });

        Assertions.assertEquals(1, outerAfterCommit.get(), "the outer after-commit ran once");
        Assertions.assertEquals(0, outerAfterRollback.get(),
                "a NESTED unit failing must NOT run the OUTER (committed) frame's after-rollback compensation");
    }

    // (Issue 1) An after-completion callback that queues an async event must have it fired within the
    // same completion (not stranded on the frame about to be cleaned up).
    @Test
    public void afterCompletionCallbackEventStillFires() {
        TransactionUtil.handle(() ->
                ThreadLocalContext.get().getCompletionRegistry().registerAfterCompletion(() ->
                        ThreadLocalContext.get().addAsyncEvent(new ProbeEvent("from-after-completion"))));

        Assertions.assertEquals(List.of("from-after-completion"), tags(recorder.received),
                "an event queued by an after-completion callback must still be drained and fired");
    }

    // (Issue 3) A failed unit of work is rethrown AND the thread-local context is cleaned (no frame / EM
    // leak onto the pooled thread).
    @Test
    public void handleWorkFailureRethrowsAndCleansContext() {
        IllegalStateException boom = new IllegalStateException("work failure");
        IllegalStateException caught = Assertions.assertThrows(IllegalStateException.class,
                () -> TransactionUtil.handle(() -> {
                    throw boom;
                }));
        Assertions.assertSame(boom, caught, "the original work failure is rethrown, unwrapped");
        Assertions.assertNull(ThreadLocalContext.get().peekEntityManager(),
                "the context is cleaned after a failed unit of work");
        Assertions.assertEquals(1, ThreadLocalContext.get().frameDepth(), "no leaked frame remains");
    }

    // (Issue 2) The checkpoint commit() routes through the three-state classifier: a thrown commit is
    // IN_DOUBT — the frame is marked terminal, the failure propagates, NO rollback compensation runs,
    // NO events fire, and a subsequent completion by the enclosing owner is a no-op.
    @Test
    public void checkpointCommitInDoubtMarksTerminalPropagatesNoCompensation() {
        FakeTransactionSupport.FakeTx tx = new FakeTransactionSupport.FakeTx();
        tx.active = true;
        tx.commitThrows = true;
        boolean[] closed = {false};
        boolean[] open = {true};
        EntityManager em = FakeTransactionSupport.fakeEntityManager(tx, closed, open);
        ThreadLocalContext.get().setEntityManager(em);

        AtomicInteger afterRollback = new AtomicInteger();
        ThreadLocalContext.get().getCompletionRegistry().registerAfterRollback(afterRollback::incrementAndGet);
        ThreadLocalContext.get().addAsyncEvent(new ProbeEvent("checkpoint"));

        Assertions.assertThrows(RuntimeException.class, TransactionUtil::commit,
                "a checkpoint commit failure must propagate");

        Assertions.assertTrue(ThreadLocalContext.get().isCurrentFrameInDoubt(),
                "a checkpoint commit failure marks the frame terminal IN_DOUBT");
        Assertions.assertEquals(0, afterRollback.get(),
                "no after-rollback compensation on a possibly-committed checkpoint");
        Assertions.assertTrue(recorder.received.isEmpty(), "no events fire on an IN_DOUBT checkpoint");

        // The enclosing owner's later finalization of the same frame must be a no-op.
        TransactionBoundary.complete(DurableState.ROLLED_BACK);
        Assertions.assertEquals(0, afterRollback.get(),
                "a terminal IN_DOUBT frame: the enclosing complete() runs no compensation");
    }

    private static List<String> tags(List<ProbeEvent> events) {
        List<String> out = new ArrayList<>();
        for (ProbeEvent event : events) {
            out.add(event.toString());
        }
        return out;
    }
}
