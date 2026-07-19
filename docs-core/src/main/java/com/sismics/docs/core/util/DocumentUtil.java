package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.User;

/**
 * Document utilities.
 *
 * @author bgamard
 */
public class DocumentUtil {
    /**
     * Create a document and add the base ACLs.
     *
     * @param document Document
     * @param userId User creating the document
     * @return Created document
     */
    public static Document createDocument(Document document, String userId) {
        // #111 phantom-insert guard: lock the owner's user row FOR UPDATE (eligibility-scoped) before the
        // insert. A self-delete of this owner takes the SAME owner-row lock and then bulk-trashes the
        // owner's documents; without this lock a document created in the race window would survive active
        // under a now-deleted owner (an orphan the guard never saw). If the owner is no longer active the
        // creation aborts, fail closed.
        User owner = new UserDao().getActiveByIdForUpdate(userId);
        if (owner == null) {
            throw new IllegalStateException("Cannot create a document for an inactive owner: " + userId);
        }

        DocumentDao documentDao = new DocumentDao();
        String documentId = documentDao.create(document, userId);

        // Create read ACL
        AclDao aclDao = new AclDao();
        Acl acl = new Acl();
        acl.setPerm(PermType.READ);
        acl.setType(AclType.USER);
        acl.setSourceId(documentId);
        acl.setTargetId(userId);
        aclDao.create(acl, userId);

        // Create write ACL
        acl = new Acl();
        acl.setPerm(PermType.WRITE);
        acl.setType(AclType.USER);
        acl.setSourceId(documentId);
        acl.setTargetId(userId);
        aclDao.create(acl, userId);

        return document;
    }
}
