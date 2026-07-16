package com.sismics.docs.infrastructure.persistence;

import com.sismics.docs.application.document.Clock;
import com.sismics.docs.application.document.DocumentFileAccessException;
import com.sismics.docs.application.document.DocumentNotFoundException;
import com.sismics.docs.application.document.DocumentRepository;
import com.sismics.docs.application.document.DocumentValidationException;
import com.sismics.docs.application.document.DocumentView;
import com.sismics.docs.application.document.DocumentView.AclView;
import com.sismics.docs.application.document.DocumentView.ContributorView;
import com.sismics.docs.application.document.DocumentView.FileView;
import com.sismics.docs.application.document.DocumentView.InheritedAclView;
import com.sismics.docs.application.document.DocumentView.MetadataView;
import com.sismics.docs.application.document.DocumentView.RelationView;
import com.sismics.docs.application.document.DocumentView.RouteStepView;
import com.sismics.docs.application.document.DocumentView.TagView;
import com.sismics.docs.application.document.GetDocumentQuery;
import com.sismics.docs.application.document.UpdateDocumentCommand;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.ContributorDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.DocumentMetadataDao;
import com.sismics.docs.core.dao.FavoriteDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.MetadataDao;
import com.sismics.docs.core.dao.RelationDao;
import com.sismics.docs.core.dao.RouteStepDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.criteria.MetadataCriteria;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.AclDto;
import com.sismics.docs.core.dao.dto.ContributorDto;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.dao.dto.DocumentMetadataDto;
import com.sismics.docs.core.dao.dto.MetadataDto;
import com.sismics.docs.core.dao.dto.RelationDto;
import com.sismics.docs.core.dao.dto.RouteStepDto;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.util.DescriptionSanitizer;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.MetadataUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;

import java.io.IOException;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence adapter for the document slice. It delegates to the existing DAOs and reproduces the
 * legacy resource's read shape as plain {@link DocumentView} records and its POST mutation semantics
 * — deliberately WITHOUT the JSON-facing legacy utilities (AclUtil/MetadataUtil.addMetadata/RestUtil/
 * RouteStepDto.toJson), which build JSON directly: the edge mapper owns JSON, and the golden corpus
 * guards the reproduction against drift. DAO construction is confined here, the one package the
 * design permits it in.
 */
public class JpaDocumentRepository implements DocumentRepository {

    private final Clock clock;

    public JpaDocumentRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<DocumentView> load(GetDocumentQuery query) {
        String documentId = query.id();
        boolean anonymous = query.anonymous();

        DocumentDto documentDto = new DocumentDao().getDocument(documentId, PermType.READ, query.readTargetIds());
        if (documentDto == null) {
            return Optional.empty();
        }

        // Tags: none for the anonymous share principal; otherwise the caller's visible tags on this
        // document. Keep the tag DTO list to drive inherited_acls (present-but-possibly-empty for an
        // authenticated caller, OMITTED for anonymous).
        List<TagDto> tagDtoList = null;
        List<TagView> tags;
        if (anonymous) {
            tags = List.of();
        } else {
            tagDtoList = new TagDao().findByCriteria(
                    new TagCriteria()
                            .setTargetIdList(query.userTargetIds())
                            .setDocumentId(documentId),
                    new SortCriteria(1, true));
            tags = new ArrayList<>();
            for (TagDto tagDto : tagDtoList) {
                tags.add(new TagView(tagDto.getId(), tagDto.getName(), tagDto.getColor()));
            }
        }

        // Direct ACLs + the writable flag (the WRITE check against the READ target set, so a share
        // WRITE ACL can make it true).
        AclDao aclDao = new AclDao();
        List<AclView> acls = new ArrayList<>();
        for (AclDto aclDto : aclDao.getBySourceId(documentId, AclType.USER)) {
            acls.add(new AclView(aclDto.getPerm().name(), aclDto.getTargetId(),
                    aclDto.getTargetName(), aclDto.getTargetType()));
        }
        boolean writable = aclDao.checkPermission(documentId, PermType.WRITE, query.readTargetIds());

        // Inherited ACLs: the USER ACLs of each visible tag. OMITTED for anonymous.
        Optional<List<InheritedAclView>> inheritedAcls;
        if (tagDtoList != null) {
            List<InheritedAclView> inherited = new ArrayList<>();
            for (TagDto tagDto : tagDtoList) {
                for (AclDto aclDto : new AclDao().getBySourceId(tagDto.getId(), AclType.USER)) {
                    inherited.add(new InheritedAclView(aclDto.getPerm().name(), tagDto.getId(),
                            tagDto.getName(), tagDto.getColor(), aclDto.getTargetId(),
                            aclDto.getTargetName(), aclDto.getTargetType()));
                }
            }
            inheritedAcls = Optional.of(inherited);
        } else {
            inheritedAcls = Optional.empty();
        }

        // Contributors.
        List<ContributorView> contributors = new ArrayList<>();
        for (ContributorDto contributorDto : new ContributorDao().getByDocumentId(documentId)) {
            contributors.add(new ContributorView(contributorDto.getUsername(), contributorDto.getEmail()));
        }

        // Outgoing/incoming relations.
        List<RelationView> relations = new ArrayList<>();
        for (RelationDto relationDto : new RelationDao().getByDocumentId(documentId)) {
            relations.add(new RelationView(relationDto.getId(), relationDto.getTitle(), relationDto.isSource()));
        }

        // Current route step: OMITTED unless one exists AND the caller is not anonymous.
        Optional<RouteStepView> routeStep;
        RouteStepDto routeStepDto = new RouteStepDao().getCurrentStep(documentId);
        if (routeStepDto != null && !anonymous) {
            boolean transitionable = query.userTargetIds().contains(routeStepDto.getTargetId());
            routeStep = Optional.of(new RouteStepView(routeStepDto.getId(), routeStepDto.getName(),
                    routeStepDto.getType().name(), routeStepDto.getComment(),
                    routeStepDto.getEndDateTimestamp(), routeStepDto.getValidatorUserName(),
                    routeStepDto.getTargetId(), routeStepDto.getTargetName(), routeStepDto.getTargetType(),
                    routeStepDto.getTransition(), transitionable));
        } else {
            routeStep = Optional.empty();
        }

        List<MetadataView> metadata = loadMetadata(documentId);

        boolean favorite = !anonymous
                && new FavoriteDao().getByUserAndDocument(query.userId(), documentId) != null;

        // Files: OMITTED unless files=true.
        Optional<List<FileView>> files;
        if (query.includeFiles()) {
            List<FileView> fileViews = new ArrayList<>();
            for (File file : new FileDao().getByDocumentsIds(Collections.singleton(documentId))) {
                fileViews.add(toFileView(file));
            }
            files = Optional.of(fileViews);
        } else {
            files = Optional.empty();
        }

        return Optional.of(new DocumentView(
                documentDto.getCreateTimestamp(),
                documentDto.getDescription(),
                documentDto.getFileId(),
                documentDto.getFileRotation(),
                documentDto.getId(),
                documentDto.getLanguage(),
                documentDto.getShared(),
                documentDto.getTitle(),
                documentDto.getUpdateTimestamp(),
                documentDto.getCreator(),
                documentDto.getCoverage(),
                documentDto.getFormat(),
                documentDto.getIdentifier(),
                documentDto.getPublisher(),
                documentDto.getRights(),
                documentDto.getSource(),
                documentDto.getSubject(),
                documentDto.getType(),
                documentDto.getFileCount(),
                tags,
                acls,
                writable,
                inheritedAcls,
                contributors,
                relations,
                routeStep,
                metadata,
                favorite,
                files));
    }

    /**
     * Enumerates EVERY metadata definition (sorted by name) with this document's value, if any. The
     * raw string value and its type are carried; the edge mapper renders the typed JSON value.
     */
    private List<MetadataView> loadMetadata(String documentId) {
        List<MetadataDto> definitions = new MetadataDao().findByCriteria(new MetadataCriteria(), new SortCriteria(1, true));
        List<DocumentMetadataDto> values = new DocumentMetadataDao().getByDocumentId(documentId);
        List<MetadataView> metadata = new ArrayList<>();
        for (MetadataDto definition : definitions) {
            Optional<String> value = Optional.empty();
            for (DocumentMetadataDto documentMetadataDto : values) {
                if (documentMetadataDto.getMetadataId().equals(definition.getId())
                        && documentMetadataDto.getValue() != null) {
                    value = Optional.of(documentMetadataDto.getValue());
                    break;
                }
            }
            metadata.add(new MetadataView(definition.getId(), definition.getName(), definition.getType(),
                    Optional.ofNullable(definition.getVocabulary()), value));
        }
        return metadata;
    }

    /**
     * Reproduces {@code RestUtil.fileToJsonObjectBuilder}'s size resolution: a legacy UNKNOWN_SIZE row
     * falls back to the physical on-disk size, and an I/O failure there surfaces as the 500 FileError.
     */
    private FileView toFileView(File file) {
        long size;
        if (File.UNKNOWN_SIZE.equals(file.getSize())) {
            try {
                size = Files.size(DirectoryUtil.getStorageDirectory().resolve(file.getId()));
            } catch (IOException e) {
                throw new DocumentFileAccessException("Unable to get the size of " + file.getId(), e);
            }
        } else {
            size = file.getSize();
        }
        return new FileView(file.getId(), FileUtil.isProcessingFile(file.getId()), file.getName(),
                file.getVersion(), file.getMimeType(), file.getDocumentId(),
                file.getCreateDate().getTime(), file.getRotation(), size);
    }

    @Override
    public void update(UpdateDocumentCommand command) {
        DocumentDao documentDao = new DocumentDao();
        Document document = documentDao.getById(command.id());
        if (document == null) {
            throw new DocumentNotFoundException();
        }

        // Partial update: title and language are always set; every other scalar is applied only when
        // it was submitted (a null value means "preserve", matching the legacy post-validation signal).
        document.setTitle(command.title());
        document.setLanguage(command.language());
        if (command.description() != null) {
            // The edge already sanitized; re-sanitizing at this entity writer is idempotent and keeps
            // the stored-XSS chokepoint intrinsic to every Document description write.
            document.setDescription(DescriptionSanitizer.sanitize(command.description()));
        }
        if (command.subject() != null) {
            document.setSubject(command.subject());
        }
        if (command.identifier() != null) {
            document.setIdentifier(command.identifier());
        }
        if (command.publisher() != null) {
            document.setPublisher(command.publisher());
        }
        if (command.format() != null) {
            document.setFormat(command.format());
        }
        if (command.source() != null) {
            document.setSource(command.source());
        }
        if (command.type() != null) {
            document.setType(command.type());
        }
        if (command.coverage() != null) {
            document.setCoverage(command.coverage());
        }
        if (command.rights() != null) {
            document.setRights(command.rights());
        }
        if (command.createDatePresent()) {
            // An empty create_date resets it to now; a valid one uses the parsed value.
            document.setCreateDate(command.createDate() != null ? command.createDate() : clock.now());
        }

        documentDao.update(document, command.actorUserId());

        if (command.applyTags()) {
            updateTagList(command.id(), command.tagIds(), command.writeTargetIds());
        }
        if (command.applyRelations()) {
            updateRelationList(command.id(), command.relationIds());
        }
        if (command.metadataIdPresent()) {
            try {
                MetadataUtil.updateMetadata(command.id(), command.metadataIdList(), command.metadataValueList());
            } catch (Exception e) {
                throw new DocumentValidationException("ValidationError", e.getMessage());
            }
        } else if (command.metadataReset()) {
            MetadataUtil.clearMetadata(command.id());
        }
    }

    /**
     * Replaces the document's tag links within the caller's visible tag set: every submitted id must
     * be visible (else a TagNotFound 400), duplicates collapse, and links to tags invisible to the
     * caller are preserved.
     */
    private void updateTagList(String documentId, List<String> tagList, List<String> targetIdList) {
        TagDao tagDao = new TagDao();
        Set<String> tagSet = new HashSet<>();
        Set<String> visibleTagIdSet = new HashSet<>();
        for (TagDto tagDto : tagDao.findByCriteria(new TagCriteria().setTargetIdList(targetIdList), null)) {
            visibleTagIdSet.add(tagDto.getId());
        }
        for (String tagId : tagList) {
            if (!visibleTagIdSet.contains(tagId)) {
                throw new DocumentValidationException("TagNotFound", MessageFormat.format("Tag not found: {0}", tagId));
            }
            tagSet.add(tagId);
        }
        tagDao.updateTagList(documentId, tagSet, visibleTagIdSet);
    }

    /**
     * Replaces the document's OUTGOING relations: unknown or self targets are silently dropped, no ACL
     * check is applied to targets, and duplicates collapse.
     */
    private void updateRelationList(String documentId, List<String> relationList) {
        DocumentDao documentDao = new DocumentDao();
        RelationDao relationDao = new RelationDao();
        Set<String> documentIdSet = new HashSet<>();
        for (String targetDocId : relationList) {
            Document target = documentDao.getById(targetDocId);
            if (target != null && !documentId.equals(targetDocId)) {
                documentIdSet.add(targetDocId);
            }
        }
        relationDao.updateRelationList(documentId, documentIdSet);
    }
}
