package com.sismics.docs.rest.util;

import com.sismics.docs.core.constant.AccessTargetType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AccessEventDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.dto.AccessUserCountDto;
import com.sismics.docs.core.dao.dto.DocumentAccessStatsDto;
import com.sismics.docs.core.model.jpa.File;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The access-counter feature's single DAO surface (#300), shared by the resources that record or read
 * access events.
 *
 * <p>Recording lives in {@code com.sismics.docs.core.util.AccessRecordingUtil} instead, because the
 * write is deliberately NOT part of the serving request's transaction.</p>
 *
 * <p>It exists as a helper rather than as calls inside the resources because the legacy
 * {@code rest.resource -> core.dao} dependency web is a frozen ArchUnit ratchet that may only shrink:
 * a new resource reaching for a DAO would re-widen it. Same precedent as
 * {@link DocumentResourceHelper} and {@code CommentResourceHelper}.</p>
 */
public final class AccessResourceHelper {
    /** Upper bound on the administrator ranking, so a hand-crafted limit cannot ask for the whole table. */
    private static final int RANKING_LIMIT_MAX = 100;

    private AccessResourceHelper() {
        // Utility class
    }

    /**
     * The CALLER's own access counts for one document and each of its files.
     *
     * <p>Every count is keyed on the calling user's id, which is taken from the authenticated principal
     * and never from the request — the endpoint has no parameter that could name another user, so there
     * is no shape of request that returns someone else's numbers.</p>
     *
     * <p>The file counts come back in ONE grouped query for the whole file list, so a document with N
     * files still costs two queries, not N.</p>
     *
     * @param userId Calling user ID
     * @param documentId Document ID
     * @param targetIdList Caller's ACL target list
     * @return {@code {count, files:[{id, count}]}}
     * @throws NotFoundException when the caller may not read the document (indistinguishable from a
     *         document that does not exist, exactly as the document endpoint itself answers)
     */
    public static JsonObject personalCounts(String userId, String documentId, List<String> targetIdList) {
        if (new DocumentDao().getDocument(documentId, PermType.READ, targetIdList) == null) {
            throw new NotFoundException();
        }

        AccessEventDao accessEventDao = new AccessEventDao();
        List<File> fileList = new FileDao().getByDocumentId(null, documentId);
        List<String> fileIds = new ArrayList<>();
        for (File file : fileList) {
            fileIds.add(file.getId());
        }
        Map<String, Long> fileCounts = accessEventDao.countByTargetsAndUser(AccessTargetType.FILE, fileIds, userId);

        JsonArrayBuilder files = Json.createArrayBuilder();
        for (String fileId : fileIds) {
            files.add(Json.createObjectBuilder()
                    .add("id", fileId)
                    .add("count", fileCounts.getOrDefault(fileId, 0L)));
        }

        return Json.createObjectBuilder()
                .add("count", accessEventDao.countByTargetAndUser(AccessTargetType.DOCUMENT, documentId, userId))
                .add("files", files)
                .build();
    }

    /**
     * The administrator view: global totals plus the most-accessed documents with their per-user
     * breakdown.
     *
     * <p>The ranking is ACL-scoped to the caller (see
     * {@link AccessEventDao#findMostAccessedDocuments(List, int)}), so this screen can never name a
     * document — or its title — that the caller could not open anyway. The two totals are pure
     * aggregates over every user and every target, so they carry no per-document or per-user detail.</p>
     *
     * @param targetIdList Caller's ACL target list
     * @param limit Requested ranking size, clamped to a sane range
     * @return {@code {total_document_accesses, total_file_accesses, documents:[{id,title,total,users:[…]}]}}
     */
    public static JsonObject adminStats(List<String> targetIdList, int limit) {
        int clamped = Math.max(1, Math.min(limit, RANKING_LIMIT_MAX));
        AccessEventDao accessEventDao = new AccessEventDao();

        JsonArrayBuilder documents = Json.createArrayBuilder();
        for (DocumentAccessStatsDto stats : accessEventDao.findMostAccessedDocuments(targetIdList, clamped)) {
            JsonArrayBuilder users = Json.createArrayBuilder();
            for (AccessUserCountDto userCount : stats.getUserCounts()) {
                users.add(Json.createObjectBuilder()
                        .add("username", userCount.getUsername())
                        .add("count", userCount.getCount()));
            }
            JsonObjectBuilder document = Json.createObjectBuilder()
                    .add("id", stats.getId())
                    .add("title", stats.getTitle())
                    .add("total", stats.getTotal())
                    .add("users", users);
            documents.add(document);
        }

        return Json.createObjectBuilder()
                .add("total_document_accesses", accessEventDao.countByType(AccessTargetType.DOCUMENT))
                .add("total_file_accesses", accessEventDao.countByType(AccessTargetType.FILE))
                .add("documents", documents)
                .build();
    }
}
