package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AccessTargetType;
import com.sismics.docs.core.dao.AccessEventDao;
import com.sismics.docs.core.model.jpa.AccessEvent;
import com.sismics.util.context.ThreadLocalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records access events (#300) WITHOUT ever putting the read that produced them at risk.
 *
 * <h2>Why the write is isolated</h2>
 *
 * <p>An access event is telemetry about a read that has already been authorized and answered. It must
 * therefore be best-effort in the strongest sense: no failure of the insert — a missing table on a
 * half-applied upgrade, a read-only database, a full disk — may turn an authorized
 * {@code GET /document/:id} or file read into a 500.</p>
 *
 * <p>Persisting into the READ's own entity manager cannot give that guarantee, and a {@code try/catch}
 * around {@code persist} does not fix it: the row would join the request's persistence context and be
 * written at FLUSH time, so the failure surfaces at the request transaction's commit, long after the
 * catch — where {@code RequestContextFilter} classifies it IN_DOUBT and sends a 500.</p>
 *
 * <h2>The mechanism</h2>
 *
 * <p>The insert is deferred to an AFTER-COMMIT callback on the current transaction frame and performed
 * there in its OWN short transaction ({@link TransactionUtil#handle}). Three properties of the existing
 * transaction machinery make this airtight, and none of them is new code:</p>
 * <ul>
 *   <li>{@code RequestContextFilter.commitAndFinalize} CLOSES the request's entity manager — releasing
 *       its pooled connection — BEFORE the completion is dispatched. The isolated insert therefore
 *       acquires its connection after the read has given one back, instead of holding two at once.
 *       That is the reason this is not simply a second transaction opened inline in the resource: N
 *       concurrent readers each holding one connection while asking for a second is the classic pool
 *       deadlock, and the deployment's pool is the thing a document read must never be wedged on.</li>
 *   <li>{@link com.sismics.util.context.TransactionCompletionRegistry} already guards every callback:
 *       one that throws is logged and its siblings still run, and it never flips the durable outcome.</li>
 *   <li>{@code TransactionBoundary.complete} never throws — it carries an observer failure out on its
 *       result object, which {@code RequestContextFilter} discards. So nothing here can reach the
 *       response.</li>
 * </ul>
 *
 * <p>Inside the callback, {@link TransactionUtil#handle} sees a CLOSED entity manager on a frame that is
 * mid-completion, so it takes its documented nested-owner path: a fresh isolated frame with its own
 * entity manager and transaction, popped afterwards. The read's frame is never touched.</p>
 *
 * <p>An async event on the {@code AppContext} bus was rejected for this: that bus is SYNCHRONOUS under
 * {@code EnvironmentUtil.isUnitTest()} and asynchronous in production, so the recording would be
 * ordered before the client's next request in every test and racing it in production — green tests
 * hiding the one behaviour under test.</p>
 *
 * <h2>What happens on failure</h2>
 *
 * <p>The event is DROPPED and a warning is logged. There is no retry and no queue: a lost counter tick
 * is a smaller harm than a failed or delayed read, and the event stream is an append-only record of
 * what the server served, not a ledger that must balance. {@link Error} is deliberately NOT swallowed —
 * an {@code OutOfMemoryError} is not a recording problem.</p>
 *
 * <p>Because after-commit callbacks run only on a DURABLE COMMIT, a refused read (403/404 rolls the
 * request transaction back) records nothing even though the call site sits after the authorization
 * check. The two protections are independent on purpose.</p>
 */
public final class AccessRecordingUtil {
    private static final Logger log = LoggerFactory.getLogger(AccessRecordingUtil.class);

    private AccessRecordingUtil() {
        // Utility class
    }

    /**
     * Records that a user was served a document.
     *
     * @param userId Acting user ID, resolved from the authenticated principal by the caller
     * @param documentId Accessed document ID
     */
    public static void recordDocumentAccess(String userId, String documentId) {
        record(AccessTargetType.DOCUMENT, documentId, documentId, userId);
    }

    /**
     * Records that a user was served a file's own content.
     *
     * @param userId Acting user ID, resolved from the authenticated principal by the caller
     * @param fileId Accessed file ID
     * @param documentId Owning document ID at access time (may be null for an unattached file)
     */
    public static void recordFileAccess(String userId, String fileId, String documentId) {
        record(AccessTargetType.FILE, fileId, documentId, userId);
    }

    /**
     * Schedules one access event for the current transaction's durable commit.
     *
     * @param type Target kind
     * @param targetId Accessed target ID
     * @param documentId Owning document ID at access time
     * @param userId Acting user ID
     */
    private static void record(AccessTargetType type, String targetId, String documentId, String userId) {
        if (userId == null) {
            // No identity to attribute the read to (an anonymous share reader). Callers already screen
            // for this; the guard is here so the invariant holds wherever this is called from next.
            return;
        }

        // The identity and the target are captured NOW, in the frame the read was authorized in, and the
        // callback closes over nothing but these immutable values. It runs on this same thread today, so
        // there is no hand-off to get wrong — capturing anyway is what keeps that true if the insert is
        // ever moved to a worker, where an ambient principal would already have moved on.
        try {
            ThreadLocalContext.get().getCompletionRegistry()
                    .registerAfterCommit(() -> insert(type, targetId, documentId, userId));
        } catch (Exception e) {
            log.warn("Could not schedule the {} access event for {}; dropping it", type, targetId, e);
        }
    }

    /**
     * Performs the insert in its own short transaction, dropping the event on any failure.
     *
     * @param type Target kind
     * @param targetId Accessed target ID
     * @param documentId Owning document ID at access time
     * @param userId Acting user ID
     */
    private static void insert(AccessTargetType type, String targetId, String documentId, String userId) {
        try {
            TransactionUtil.handle(() -> new AccessEventDao().create(new AccessEvent()
                    .setType(type)
                    .setTargetId(targetId)
                    .setDocumentId(documentId)
                    .setUserId(userId)));
        } catch (Exception e) {
            log.warn("Could not record the {} access event for {}; dropping it", type, targetId, e);
        }
    }
}
