package com.sismics.docs.rest.document;

import com.sismics.docs.application.document.DocumentView;
import com.sismics.docs.application.document.DocumentView.AclView;
import com.sismics.docs.application.document.DocumentView.ContributorView;
import com.sismics.docs.application.document.DocumentView.FileView;
import com.sismics.docs.application.document.DocumentView.InheritedAclView;
import com.sismics.docs.application.document.DocumentView.MetadataView;
import com.sismics.docs.application.document.DocumentView.RelationView;
import com.sismics.docs.application.document.DocumentView.RouteStepView;
import com.sismics.docs.application.document.DocumentView.TagView;
import com.sismics.docs.application.document.UpdatedDocumentResult;
import com.sismics.docs.core.constant.MetadataType;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.core.Response;

/**
 * Turns the application read model into the exact legacy JSON wire shape. This is the ONLY place in
 * the slice that builds JSON. Fields the legacy path renders present-as-null are emitted with an
 * explicit JSON {@code null} (never omitted); sections it omits when absent are added only when the
 * view carries them.
 *
 * <p>{@code nullable*} helpers reproduce {@code JsonUtil.nullable} without importing it, keeping the
 * edge's dependency surface to {@code jakarta.json} — a JSON {@code null} for a null value.</p>
 */
public final class LegacyDocumentResponseMapper {

    private LegacyDocumentResponseMapper() {
        // Utility class.
    }

    /**
     * @param view The document read model
     * @return The 200 response with the full document JSON
     */
    public static Response toResponse(DocumentView view) {
        JsonObjectBuilder document = Json.createObjectBuilder()
                .add("create_date", view.createDate())
                .add("file_rotation", view.fileRotation())
                .add("id", view.id())
                .add("language", view.language())
                .add("shared", view.shared())
                .add("title", view.title())
                .add("update_date", view.updateDate())
                .add("creator", view.creator())
                .add("file_count", view.fileCount());
        nullableString(document, "description", view.description());
        nullableString(document, "file_id", view.fileId());
        nullableString(document, "coverage", view.coverage());
        nullableString(document, "format", view.format());
        nullableString(document, "identifier", view.identifier());
        nullableString(document, "publisher", view.publisher());
        nullableString(document, "rights", view.rights());
        nullableString(document, "source", view.source());
        nullableString(document, "subject", view.subject());
        nullableString(document, "type", view.type());

        JsonArrayBuilder tags = Json.createArrayBuilder();
        for (TagView tag : view.tags()) {
            tags.add(Json.createObjectBuilder()
                    .add("id", tag.id())
                    .add("name", tag.name())
                    .add("color", tag.color()));
        }
        document.add("tags", tags);

        JsonArrayBuilder acls = Json.createArrayBuilder();
        for (AclView acl : view.acls()) {
            JsonObjectBuilder aclBuilder = Json.createObjectBuilder()
                    .add("perm", acl.perm())
                    .add("id", acl.id())
                    .add("type", acl.type());
            nullableString(aclBuilder, "name", acl.name());
            acls.add(aclBuilder);
        }
        document.add("acls", acls)
                .add("writable", view.writable());

        view.inheritedAcls().ifPresent(inheritedList -> {
            JsonArrayBuilder inherited = Json.createArrayBuilder();
            for (InheritedAclView acl : inheritedList) {
                JsonObjectBuilder aclBuilder = Json.createObjectBuilder()
                        .add("perm", acl.perm())
                        .add("source_id", acl.sourceId())
                        .add("source_name", acl.sourceName())
                        .add("source_color", acl.sourceColor())
                        .add("id", acl.id())
                        .add("type", acl.type());
                nullableString(aclBuilder, "name", acl.name());
                inherited.add(aclBuilder);
            }
            document.add("inherited_acls", inherited);
        });

        JsonArrayBuilder contributors = Json.createArrayBuilder();
        for (ContributorView contributor : view.contributors()) {
            contributors.add(Json.createObjectBuilder()
                    .add("username", contributor.username())
                    .add("email", contributor.email()));
        }
        document.add("contributors", contributors);

        JsonArrayBuilder relations = Json.createArrayBuilder();
        for (RelationView relation : view.relations()) {
            relations.add(Json.createObjectBuilder()
                    .add("id", relation.id())
                    .add("title", relation.title())
                    .add("source", relation.source()));
        }
        document.add("relations", relations);

        view.routeStep().ifPresent(step -> document.add("route_step", routeStep(step)));

        JsonArrayBuilder metadata = Json.createArrayBuilder();
        for (MetadataView meta : view.metadata()) {
            JsonObjectBuilder metaBuilder = Json.createObjectBuilder()
                    .add("id", meta.id())
                    .add("name", meta.name())
                    .add("type", meta.type().name());
            meta.vocabulary().ifPresent(vocabulary -> metaBuilder.add("vocabulary", vocabulary));
            meta.value().ifPresent(value -> addTypedMetadataValue(metaBuilder, meta.type(), value));
            metadata.add(metaBuilder);
        }
        document.add("metadata", metadata);

        document.add("favorite", view.favorite());

        view.files().ifPresent(fileList -> {
            JsonArrayBuilder files = Json.createArrayBuilder();
            for (FileView file : fileList) {
                JsonObjectBuilder fileBuilder = Json.createObjectBuilder()
                        .add("id", file.id())
                        .add("processing", file.processing())
                        .add("version", file.version())
                        .add("mimetype", file.mimetype())
                        .add("create_date", file.createDate())
                        .add("rotation", file.rotation())
                        .add("size", file.size());
                nullableString(fileBuilder, "name", file.name());
                nullableString(fileBuilder, "document_id", file.documentId());
                files.add(fileBuilder);
            }
            document.add("files", files);
        });

        return Response.ok().entity(document.build()).build();
    }

    /**
     * @param result The update result
     * @return The 200 response echoing the document id
     */
    public static Response toResponse(UpdatedDocumentResult result) {
        return Response.ok().entity(Json.createObjectBuilder()
                .add("id", result.id())
                .build()).build();
    }

    private static JsonObjectBuilder routeStep(RouteStepView step) {
        JsonObjectBuilder target = Json.createObjectBuilder()
                .add("id", step.targetId())
                .add("type", step.targetType());
        nullableString(target, "name", step.targetName());

        JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("id", step.id())
                .add("name", step.name())
                .add("type", step.type())
                .add("target", target)
                .add("transitionable", step.transitionable());
        nullableString(builder, "comment", step.comment());
        nullableLong(builder, "end_date", step.endDate());
        nullableString(builder, "validator_username", step.validatorUsername());
        nullableString(builder, "transition", step.transition());
        return builder;
    }

    private static void addTypedMetadataValue(JsonObjectBuilder meta, MetadataType type, String value) {
        switch (type) {
            case STRING, VOCABULARY -> meta.add("value", value);
            case BOOLEAN -> meta.add("value", Boolean.parseBoolean(value));
            case DATE -> meta.add("value", Long.parseLong(value));
            case FLOAT -> meta.add("value", Double.parseDouble(value));
            case INTEGER -> meta.add("value", Integer.parseInt(value));
        }
    }

    private static void nullableString(JsonObjectBuilder builder, String key, String value) {
        if (value == null) {
            builder.addNull(key);
        } else {
            builder.add(key, value);
        }
    }

    private static void nullableLong(JsonObjectBuilder builder, String key, Long value) {
        if (value == null) {
            builder.addNull(key);
        } else {
            builder.add(key, (long) value);
        }
    }
}
