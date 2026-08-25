package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.dto.CommentDto;
import com.sismics.docs.core.model.jpa.Comment;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.NoResultException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

/**
 * Unit tests for {@link CommentDao}, focused on the #285 edit audit trail.
 *
 * <p>The contract under test: a comment carries TWO dates, and an edit only ever writes the second
 * one. A never-edited comment has a null edit date — that absence is what the UI reads to decide
 * whether to show an "edited" marker — and editing stamps it without moving the creation date. Both
 * dates survive into the DTO the list endpoint is built from.
 */
public class TestCommentDao extends BaseTransactionalTest {
    private String createDocument(User user, String title) {
        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date());
        return documentDao.create(document, user.getId());
    }

    private String createComment(User user, String documentId, String content) {
        Comment comment = new Comment();
        comment.setDocumentId(documentId);
        comment.setUserId(user.getId());
        comment.setContent(content);
        return new CommentDao().create(comment, user.getId());
    }

    private CommentDto findDto(List<CommentDto> list, String id) {
        return list.stream().filter(dto -> dto.getId().equals(id)).findFirst().orElse(null);
    }

    @Test
    public void aNewCommentIsNotMarkedEdited() throws Exception {
        User user = createUser("comment_new");
        String documentId = createDocument(user, "Comment new doc");
        CommentDao dao = new CommentDao();
        String commentId = createComment(user, documentId, "Fresh");

        Assertions.assertNull(dao.getActiveById(commentId).getUpdateDate(),
                "a comment that was never edited carries no edit date");

        // The DTO the list endpoint reads carries the same absence.
        ThreadLocalContext.get().getEntityManager().flush();
        CommentDto dto = findDto(dao.getByDocumentId(documentId), commentId);
        Assertions.assertNotNull(dto);
        Assertions.assertNotNull(dto.getCreateTimestamp());
        Assertions.assertNull(dto.getUpdateTimestamp(), "never edited -> no edit timestamp in the DTO");
    }

    @Test
    public void editingStampsTheEditDateAndLeavesTheCreationDateAlone() throws Exception {
        User user = createUser("comment_edit");
        String documentId = createDocument(user, "Comment edit doc");
        CommentDao dao = new CommentDao();
        String commentId = createComment(user, documentId, "Teh first version");
        long createTime = dao.getActiveById(commentId).getCreateDate().getTime();

        Comment updated = dao.update(commentId, "The first version", user.getId());

        Assertions.assertEquals("The first version", updated.getContent());
        Assertions.assertNotNull(updated.getUpdateDate(), "an edit must stamp the edit date");
        Assertions.assertTrue(updated.getUpdateDate().getTime() >= createTime,
                "the edit date cannot predate the creation date");
        Assertions.assertEquals(createTime, updated.getCreateDate().getTime(),
                "an edit must not move the creation date");

        // Re-read from the DAO rather than trusting the returned instance.
        Comment reread = dao.getActiveById(commentId);
        Assertions.assertEquals("The first version", reread.getContent());
        Assertions.assertEquals(updated.getUpdateDate().getTime(), reread.getUpdateDate().getTime());
        Assertions.assertEquals(createTime, reread.getCreateDate().getTime());

        // And both dates reach the DTO the list endpoint is assembled from.
        ThreadLocalContext.get().getEntityManager().flush();
        CommentDto dto = findDto(dao.getByDocumentId(documentId), commentId);
        Assertions.assertNotNull(dto);
        Assertions.assertEquals("The first version", dto.getContent());
        Assertions.assertEquals(createTime, dto.getCreateTimestamp().longValue());
        Assertions.assertEquals(updated.getUpdateDate().getTime(), dto.getUpdateTimestamp().longValue());
    }

    @Test
    public void repeatedEditsKeepOneCreationDateAndAdvanceTheEditDate() throws Exception {
        User user = createUser("comment_reedit");
        String documentId = createDocument(user, "Comment re-edit doc");
        CommentDao dao = new CommentDao();
        String commentId = createComment(user, documentId, "v1");
        long createTime = dao.getActiveById(commentId).getCreateDate().getTime();

        long firstEdit = dao.update(commentId, "v2", user.getId()).getUpdateDate().getTime();
        Thread.sleep(5);
        long secondEdit = dao.update(commentId, "v3", user.getId()).getUpdateDate().getTime();

        Assertions.assertTrue(secondEdit > firstEdit, "a later edit must advance the edit date");
        Assertions.assertEquals(createTime, dao.getActiveById(commentId).getCreateDate().getTime(),
                "repeated edits still leave the creation date where it was");
        Assertions.assertEquals("v3", dao.getActiveById(commentId).getContent());
    }

    @Test
    public void editingOneCommentLeavesTheOthersUntouched() throws Exception {
        User user = createUser("comment_sibling");
        String documentId = createDocument(user, "Comment sibling doc");
        CommentDao dao = new CommentDao();
        String editedId = createComment(user, documentId, "edited one");
        String untouchedId = createComment(user, documentId, "untouched one");

        dao.update(editedId, "edited one, corrected", user.getId());

        ThreadLocalContext.get().getEntityManager().flush();
        List<CommentDto> dtoList = dao.getByDocumentId(documentId);
        Assertions.assertEquals(2, dtoList.size());
        Assertions.assertNotNull(findDto(dtoList, editedId).getUpdateTimestamp());
        Assertions.assertEquals("untouched one", findDto(dtoList, untouchedId).getContent());
        Assertions.assertNull(findDto(dtoList, untouchedId).getUpdateTimestamp(),
                "editing a comment must not stamp its siblings as edited");
    }

    @Test
    public void aDeletedCommentIsNotEditable() throws Exception {
        User user = createUser("comment_deleted");
        String documentId = createDocument(user, "Comment deleted doc");
        CommentDao dao = new CommentDao();
        String commentId = createComment(user, documentId, "Doomed");
        dao.delete(commentId, user.getId());

        // The DAO selects ACTIVE comments only, so a soft-deleted comment has nothing to update; the
        // resource's own precheck (getActiveById) is what turns this into a 404 rather than a 500.
        Assertions.assertNull(dao.getActiveById(commentId));
        Assertions.assertThrows(NoResultException.class,
                () -> dao.update(commentId, "Back from the dead", user.getId()));
    }
}
