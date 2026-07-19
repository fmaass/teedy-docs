package com.sismics.docs.application.document;

import java.util.List;

/**
 * Input to {@link GetDocumentHandler}: everything the read needs, already resolved from the HTTP
 * request at the edge.
 *
 * <p>Two distinct ACL target lists are carried, matching the legacy resource: {@code readTargetIds}
 * (groups + principal id + share id) drives the READ lookup and the {@code writable}/{@code acls}
 * checks; {@code userTargetIds} (groups + principal id, no share) drives tag visibility and the route
 * step's {@code transitionable} flag. {@code anonymous} is the share/anonymous principal flag; it
 * suppresses tags, {@code inherited_acls}, the route step, and favorites exactly as the legacy path.</p>
 *
 * @param id             Document ID
 * @param shareId        Share token (nullable)
 * @param includeFiles   True to include the files section ({@code files=true})
 * @param readTargetIds  ACL targets for the READ check and direct ACLs (includes the share id)
 * @param userTargetIds  ACL targets for tag visibility and route-step transitionability (no share)
 * @param userId         Acting user id, used for the favorite lookup (may be null for anonymous)
 * @param anonymous      True when the principal is the anonymous share principal
 */
public record GetDocumentQuery(
        String id,
        String shareId,
        boolean includeFiles,
        List<String> readTargetIds,
        List<String> userTargetIds,
        String userId,
        boolean anonymous) {
}
