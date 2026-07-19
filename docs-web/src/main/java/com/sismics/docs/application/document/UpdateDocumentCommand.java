package com.sismics.docs.application.document;

import java.util.Date;
import java.util.List;

/**
 * Input to {@link UpdateDocumentHandler}: the validated, partial-update instructions for
 * {@code POST /document/{id}}. All scalar fields are already validated at the edge; a null scalar
 * means "not submitted, preserve" — the same signal the legacy resource derived from the
 * post-validation value.
 *
 * <p>Presence is explicit where null cannot carry it: {@code createDatePresent} is the raw
 * form-param presence (an empty create_date maps to "now", so its parsed {@code createDate} is null
 * while the field WAS submitted); {@code applyTags}/{@code applyRelations} fold the
 * {@code containsKey || *_reset} decision; {@code metadataIdPresent} and {@code metadataReset} drive
 * the metadata branch. {@code writeTargetIds} is the caller's full ACL target set (user + groups),
 * used both for the WRITE authorization and for tag visibility during the tag replacement — the
 * legacy resource used {@code getTargetIdList(null)} for both.</p>
 */
public record UpdateDocumentCommand(
        String id,
        String actorUserId,
        List<String> writeTargetIds,
        String title,
        String language,
        String description,
        String subject,
        String identifier,
        String publisher,
        String format,
        String source,
        String type,
        String coverage,
        String rights,
        boolean createDatePresent,
        Date createDate,
        boolean applyTags,
        List<String> tagIds,
        boolean applyRelations,
        List<String> relationIds,
        boolean metadataIdPresent,
        List<String> metadataIdList,
        List<String> metadataValueList,
        boolean metadataReset) {
}
