package com.sismics.docs.rest.util;

import com.sismics.docs.core.dao.CommentDao;
import com.sismics.docs.core.model.jpa.Comment;

/**
 * Shared logic for the comment REST endpoints (#285).
 *
 * <p>Why the persistence lives here rather than in {@code CommentResource}: the
 * {@code rest.resource -> core.dao} dependency web is frozen by
 * {@code DocumentSliceArchitectureTest#legacy_resource_dao_frozen} and may only SHRINK, so new
 * endpoints must not reach into a DAO from the resource class. This helper plays the same role
 * {@link DocumentResourceHelper} plays for the document endpoints.
 */
public final class CommentResourceHelper {
    private CommentResourceHelper() {
        // Utility class
    }

    /**
     * Edit a comment on behalf of its author.
     *
     * <p>The authorization rule of slice 1 is deliberately narrower than the delete rule: only the
     * comment's AUTHOR may edit it. A collaborator holding WRITE on the document may still delete the
     * comment (moderation), but rewriting someone else's words under their name is a different act, so
     * it is not permitted at all in this slice — not even for an admin.
     *
     * <p>Both refusals — no such active comment, and not the author — return null so the caller cannot
     * accidentally leak which of the two it was; the endpoint answers NOT_FOUND either way, the same
     * convention the delete endpoint uses.
     *
     * @param commentId Comment ID
     * @param content New content (already validated by the caller)
     * @param userId ID of the acting user
     * @return The updated comment, or null when there is no such active comment or the acting user is
     *         not its author
     */
    public static Comment updateOwnComment(String commentId, String content, String userId) {
        CommentDao commentDao = new CommentDao();
        Comment comment = commentDao.getActiveById(commentId);
        if (comment == null || !comment.getUserId().equals(userId)) {
            return null;
        }
        return commentDao.update(commentId, content, userId);
    }
}
