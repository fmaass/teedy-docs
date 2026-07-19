package com.sismics.docs.infrastructure.persistence;

import com.sismics.docs.application.document.DocumentAuthorizationService;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;

import java.util.List;

/**
 * ACL-backed {@link DocumentAuthorizationService}. Delegates to {@link AclDao#checkPermission}, which
 * carries the admin bypass (a target list containing {@code admin}/{@code administrators} skips the
 * ACL check).
 */
public class JpaDocumentAuthorizationService implements DocumentAuthorizationService {

    @Override
    public boolean canWrite(String documentId, List<String> targetIds) {
        return new AclDao().checkPermission(documentId, PermType.WRITE, targetIds);
    }
}
