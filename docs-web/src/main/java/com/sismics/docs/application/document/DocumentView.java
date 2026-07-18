package com.sismics.docs.application.document;

import com.sismics.docs.core.constant.MetadataType;

import java.util.List;
import java.util.Optional;

/**
 * Immutable read model of a single document, carrying every section the {@code GET /document/{id}}
 * response exposes. The application layer produces this record graph; the REST edge's response
 * mapper is the only place that turns it into JSON, so no {@code jakarta.json} type leaks below the
 * edge.
 *
 * <p>Wire distinctions are encoded in the types: fields the wire renders as {@code null} when unset
 * are plain nullable references (the mapper emits JSON {@code null}); sections the wire OMITS when
 * absent are {@link Optional} (the mapper adds the member only when present). This keeps the
 * null-vs-omitted contract in the type system rather than in mapper conditionals.</p>
 */
public record DocumentView(
        long createDate,
        String description,
        String fileId,
        int fileRotation,
        String id,
        String language,
        boolean shared,
        String title,
        long updateDate,
        String creator,
        String coverage,
        String format,
        String identifier,
        String publisher,
        String rights,
        String source,
        String subject,
        String type,
        int fileCount,
        List<TagView> tags,
        List<AclView> acls,
        boolean writable,
        Optional<List<InheritedAclView>> inheritedAcls,
        List<ContributorView> contributors,
        List<RelationView> relations,
        Optional<RouteStepView> routeStep,
        List<MetadataView> metadata,
        boolean favorite,
        Optional<List<FileView>> files) {

    /**
     * A tag visible to the caller on this document.
     */
    public record TagView(String id, String name, String color) {
    }

    /**
     * A direct ACL on the document. {@code name} is nullable (rendered as JSON {@code null}).
     */
    public record AclView(String perm, String id, String name, String type) {
    }

    /**
     * An ACL inherited from one of the document's visible tags. {@code name} is nullable.
     */
    public record InheritedAclView(String perm, String sourceId, String sourceName, String sourceColor,
                                   String id, String name, String type) {
    }

    /**
     * A user who has contributed to the document.
     */
    public record ContributorView(String username, String email) {
    }

    /**
     * A relation to another document. {@code source} is true when this document is the relation's source.
     */
    public record RelationView(String id, String title, boolean source) {
    }

    /**
     * The current active route step. All nullable members are rendered as JSON {@code null}.
     */
    public record RouteStepView(String id, String name, String type, String comment, Long endDate,
                                String validatorUsername, String targetId, String targetName, String targetType,
                                String transition, boolean transitionable) {
    }

    /**
     * One metadata definition and its (optional) value on this document. {@code vocabulary} is OMITTED
     * when the definition is not vocabulary-typed; {@code value} is OMITTED when unset. The raw value is
     * carried as a string alongside its {@link MetadataType} so the edge mapper — not this layer —
     * renders the typed JSON value.
     */
    public record MetadataView(String id, String name, MetadataType type, Optional<String> vocabulary,
                               Optional<String> value) {
    }

    /**
     * A file of the document (present only when the caller requested {@code files=true}).
     * {@code name} and {@code documentId} are nullable. {@code size} is already resolved (legacy
     * UNKNOWN_SIZE rows fall back to the on-disk size in the repository). {@code creator} is the
     * current-version uploader's username, or null when it cannot be resolved.
     */
    public record FileView(String id, boolean processing, String name, int version, String mimetype,
                           String documentId, long createDate, int rotation, long size, String creator) {
    }
}
