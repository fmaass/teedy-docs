package com.sismics.docs.core.listener.async;

/**
 * The terminal state of one run of the post-upload file processing pipeline (issue #159).
 *
 * <p>The pipeline deliberately swallows step failures so a cosmetic failure (e.g. thumbnail
 * rasterisation) never aborts the search-critical work. That makes "processFile returned" a poor proxy
 * for "processing succeeded". This outcome captures what actually happened so the durable completion
 * marker is written ONLY on a real terminal state:
 *
 * <ul>
 *   <li>{@link #COMPLETE} — content resolved (extracted, or legitimately empty for an unhandled format),
 *       the index write committed, and auto-tagging applied. Record completion.</li>
 *   <li>{@link #RETRYABLE_FAILURE} — a step failed in a way a later run may recover (a handler threw, the
 *       index write failed, auto-tagging threw, or the row/owner vanished mid-run). Do NOT record
 *       completion; the reconciliation lease expires and the row is reclaimed on a later cycle.</li>
 *   <li>{@link #TERMINAL_SKIP} — the work can never succeed (undecryptable/owner-less content). Record
 *       completion anyway so the row stops being re-selected forever.</li>
 * </ul>
 */
public enum FileProcessingOutcome {
    COMPLETE,
    RETRYABLE_FAILURE,
    TERMINAL_SKIP
}
