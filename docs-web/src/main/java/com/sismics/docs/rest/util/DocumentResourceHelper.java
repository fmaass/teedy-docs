package com.sismics.docs.rest.util;

import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.RelationDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.event.DocumentDeletedAsyncEvent;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.rest.exception.ClientException;
import com.sismics.util.JsonUtil;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import org.apache.commons.lang3.StringUtils;

import java.text.MessageFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helper methods extracted from {@link com.sismics.docs.rest.resource.DocumentResource}
 * to keep the REST endpoint methods thin. These are exact moves of logic that previously
 * lived as private methods on the resource; behavior is unchanged.
 *
 * @author bgamard
 */
public final class DocumentResourceHelper {
    private DocumentResourceHelper() {
        // Utility class
    }

    /**
     * Sanitize a string for use as a single ZIP path segment.
     *
     * @param value Raw value
     * @return Safe path segment
     */
    public static String sanitizePathSegment(String value) {
        if (StringUtils.isBlank(value)) {
            return "document";
        }
        return value.replaceAll("\\W+", "_");
    }

    /**
     * Reduce a file name to a safe single archive entry segment: strip any path
     * separators and traversal, keeping the base name (and its extension).
     *
     * @param value Raw file name
     * @return Safe file name
     */
    public static String sanitizeFileName(String value) {
        if (StringUtils.isBlank(value)) {
            return "file";
        }
        String name = value.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "file";
        }
        return name;
    }

    /**
     * Fire the file-deleted events for every file of a document followed by the
     * document-deleted event.
     *
     * @param fileDao File DAO
     * @param userId Acting user ID
     * @param documentId Document ID
     */
    public static void fireFileAndDocumentDeletedEvents(FileDao fileDao, String userId, String documentId) {
        List<File> fileList = fileDao.getAllByDocumentId(documentId);
        for (File file : fileList) {
            FileDeletedAsyncEvent fileDeletedAsyncEvent = new FileDeletedAsyncEvent();
            fileDeletedAsyncEvent.setUserId(userId);
            fileDeletedAsyncEvent.setFileId(file.getId());
            fileDeletedAsyncEvent.setFileSize(file.getSize());
            ThreadLocalContext.get().addAsyncEvent(fileDeletedAsyncEvent);
        }

        DocumentDeletedAsyncEvent documentDeletedAsyncEvent = new DocumentDeletedAsyncEvent();
        documentDeletedAsyncEvent.setUserId(userId);
        documentDeletedAsyncEvent.setDocumentId(documentId);
        ThreadLocalContext.get().addAsyncEvent(documentDeletedAsyncEvent);
    }

    /**
     * Update tags list on a document.
     *
     * @param documentId Document ID
     * @param tagList Tag ID list
     * @param targetIdList Acting user's ACL target ID list
     */
    public static void updateTagList(String documentId, List<String> tagList, List<String> targetIdList) {
        if (tagList != null) {
            TagDao tagDao = new TagDao();
            Set<String> tagSet = new HashSet<>();
            Set<String> visibleTagIdSet = new HashSet<>();
            List<TagDto> tagDtoList = tagDao.findByCriteria(new TagCriteria().setTargetIdList(targetIdList), null);
            for (TagDto tagDto : tagDtoList) {
                visibleTagIdSet.add(tagDto.getId());
            }
            for (String tagId : tagList) {
                if (!visibleTagIdSet.contains(tagId)) {
                    throw new ClientException("TagNotFound", MessageFormat.format("Tag not found: {0}", tagId));
                }
                tagSet.add(tagId);
            }
            // Only remove links to tags the acting user can see; tags invisible to them (e.g. an owner's
            // private tag on a shared document) must be preserved.
            tagDao.updateTagList(documentId, tagSet, visibleTagIdSet);
        }
    }

    /**
     * Update relations list on a document.
     *
     * @param documentId Document ID
     * @param relationList Relation ID list
     */
    public static void updateRelationList(String documentId, List<String> relationList) {
        if (relationList != null) {
            DocumentDao documentDao = new DocumentDao();
            RelationDao relationDao = new RelationDao();
            Set<String> documentIdSet = new HashSet<>();
            for (String targetDocId : relationList) {
                // ACL are not checked, because the editing user is not forced to view the target document
                Document document = documentDao.getById(targetDocId);
                if (document != null && !documentId.equals(targetDocId)) {
                    documentIdSet.add(targetDocId);
                }
            }
            relationDao.updateRelationList(documentId, documentIdSet);
        }
    }

    /**
     * Build the JSON object common to every document representation.
     *
     * @param documentDto Document DTO
     * @return JSON object builder
     */
    public static JsonObjectBuilder createDocumentObjectBuilder(DocumentDto documentDto) {
        return Json.createObjectBuilder()
                .add("create_date", documentDto.getCreateTimestamp())
                .add("description", JsonUtil.nullable(documentDto.getDescription()))
                .add("file_id", JsonUtil.nullable(documentDto.getFileId()))
                .add("id", documentDto.getId())
                .add("language", documentDto.getLanguage())
                .add("shared", documentDto.getShared())
                .add("title", documentDto.getTitle())
                .add("update_date", documentDto.getUpdateTimestamp());
    }

    /**
     * Build the JSON array of tags.
     *
     * @param tagDtoList Tag DTO list
     * @return JSON array builder
     */
    public static JsonArrayBuilder createTagsArrayBuilder(List<TagDto> tagDtoList) {
        JsonArrayBuilder tags = Json.createArrayBuilder();
        for (TagDto tagDto : tagDtoList) {
            tags.add(Json.createObjectBuilder()
                    .add("id", tagDto.getId())
                    .add("name", tagDto.getName())
                    .add("color", tagDto.getColor()));
        }
        return tags;
    }
}
