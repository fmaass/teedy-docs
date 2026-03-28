package com.sismics.docs.rest.util;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.criteria.DocumentCriteria;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.model.jpa.User;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class DocumentSearchCriteriaUtil {
    private static final DateTimeFormatter YEAR_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy")
            .parseDefaulting(ChronoField.MONTH_OF_YEAR, 1)
            .parseDefaulting(ChronoField.DAY_OF_MONTH, 1)
            .toFormatter();

    private static final DateTimeFormatter MONTH_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM")
            .parseDefaulting(ChronoField.DAY_OF_MONTH, 1)
            .toFormatter();

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter DATES_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy")
            .optionalStart()
            .appendPattern("-MM")
            .optionalStart()
            .appendPattern("-dd")
            .optionalEnd()
            .optionalEnd()
            .parseDefaulting(ChronoField.MONTH_OF_YEAR, 1)
            .parseDefaulting(ChronoField.DAY_OF_MONTH, 1)
            .toFormatter();

    private static final String PARAMETER_WITH_MULTIPLE_VALUES_SEPARATOR = ",";
    private static final String WORKFLOW_ME = "me";

    /**
     * Parse a query according to the specified syntax, eg.:
     * tag:assurance tag:other before:2012 after:2011-09 shared:yes lang:fra thing
     *
     * @param search        Search query
     * @param allTagDtoList List of tags
     * @return DocumentCriteria
     */
    public static DocumentCriteria parseSearchQuery(String search, List<TagDto> allTagDtoList) {
        DocumentCriteria documentCriteria = new DocumentCriteria();
        if (Strings.isNullOrEmpty(search)) {
            return documentCriteria;
        }

        String[] criteriaList = search.split(" +");
        List<String> simpleQuery = new ArrayList<>();
        List<String> fullQuery = new ArrayList<>();
        for (String criteria : criteriaList) {
            String[] params = criteria.split(":");
            if (params.length != 2 || Strings.isNullOrEmpty(params[0]) || Strings.isNullOrEmpty(params[1])) {
                // This is not a special criteria, do a fulltext search on it
                fullQuery.add(criteria);
                continue;
            }
            String paramName = params[0];
            String paramValue = params[1];

            switch (paramName) {
                case "tag":
                case "!tag":
                    parseTagCriteria(documentCriteria, paramValue, allTagDtoList, paramName.startsWith("!"));
                    break;
                case "after":
                case "before":
                case "uafter":
                case "ubefore":
                    parseDateCriteria(documentCriteria, paramValue, DATES_FORMATTER, paramName.startsWith("u"), paramName.endsWith("before"));
                    break;
                case "uat":
                case "at":
                    parseDateAtCriteria(documentCriteria, paramValue, params[0].startsWith("u"));
                    break;
                case "shared":
                    documentCriteria.setShared(paramValue.equals("yes"));
                    break;
                case "lang":
                    parseLangCriteria(documentCriteria, paramValue);
                    break;
                case "mime":
                    documentCriteria.setMimeType(paramValue);
                    break;
                case "by":
                    parseByCriteria(documentCriteria, paramValue);
                    break;
                case "workflow":
                    documentCriteria.setActiveRoute(paramValue.equals(WORKFLOW_ME));
                    break;
                case "simple":
                    simpleQuery.add(paramValue);
                    break;
                case "full":
                    fullQuery.add(paramValue);
                    break;
                case "title":
                    documentCriteria.getTitleList().add(paramValue);
                    break;
                default:
                    fullQuery.add(criteria);
                    break;
            }
        }

        documentCriteria.setSimpleSearch(Joiner.on(" ").join(simpleQuery));
        documentCriteria.setFullSearch(Joiner.on(" ").join(fullQuery));
        return documentCriteria;
    }


    /**
     * Fill the document criteria with various possible parameters
     *
     * @param documentCriteria    structure to be filled
     * @param searchBy            author
     * @param searchCreatedAfter  creation moment after
     * @param searchCreatedBefore creation moment before
     * @param searchFull          full search
     * @param searchLang          lang
     * @param searchMime          mime type
     * @param searchShared        share state
     * @param searchSimple        search in
     * @param searchTag           tags or parent tags
     * @param searchNotTag        tags or parent tags to ignore
     * @param searchTitle         title
     * @param searchUpdatedAfter  update moment after
     * @param searchUpdatedBefore update moment before
     * @param searchWorkflow      exiting workflow
     * @param allTagDtoList       list of existing tags
     */
    public static void addHttpSearchParams(
            DocumentCriteria documentCriteria,
            String searchBy,
            String searchCreatedAfter,
            String searchCreatedBefore,
            String searchFull,
            String searchLang,
            String searchMime,
            Boolean searchShared,
            String searchSimple,
            String searchTag,
            String searchNotTag,
            String searchTitle,
            String searchUpdatedAfter,
            String searchUpdatedBefore,
            String searchWorkflow,
            List<TagDto> allTagDtoList
    ) {
        if (searchBy != null) {
            parseByCriteria(documentCriteria, searchBy);
        }
        if (searchCreatedAfter != null) {
            parseDateCriteria(documentCriteria, searchCreatedAfter, DAY_FORMATTER, false, false);
        }
        if (searchCreatedBefore != null) {
            parseDateCriteria(documentCriteria, searchCreatedBefore, DAY_FORMATTER, false, true);
        }
        if (searchFull != null) {
            documentCriteria.setFullSearch(Joiner.on(" ").join(searchFull.split(PARAMETER_WITH_MULTIPLE_VALUES_SEPARATOR)));
        }
        if (searchLang != null) {
            parseLangCriteria(documentCriteria, searchLang);
        }
        if (searchMime != null) {
            documentCriteria.setMimeType(searchMime);
        }
        if ((searchShared != null) && searchShared) {
            documentCriteria.setShared(true);
        }
        if (searchSimple != null) {
            documentCriteria.setSimpleSearch(Joiner.on(" ").join(searchSimple.split(PARAMETER_WITH_MULTIPLE_VALUES_SEPARATOR)));
        }
        if (searchTitle != null) {
            documentCriteria.getTitleList().addAll(Arrays.asList(searchTitle.split(PARAMETER_WITH_MULTIPLE_VALUES_SEPARATOR)));
        }
        if (searchTag != null) {
            for (String tag : searchTag.split(PARAMETER_WITH_MULTIPLE_VALUES_SEPARATOR)) {
                parseTagCriteria(documentCriteria, tag, allTagDtoList, false);
            }
        }
        if (searchNotTag != null) {
            for (String tag : searchNotTag.split(PARAMETER_WITH_MULTIPLE_VALUES_SEPARATOR)) {
                parseTagCriteria(documentCriteria, tag, allTagDtoList, true);
            }
        }
        if (searchUpdatedAfter != null) {
            parseDateCriteria(documentCriteria, searchUpdatedAfter, DAY_FORMATTER, true, false);
        }
        if (searchUpdatedBefore != null) {
            parseDateCriteria(documentCriteria, searchUpdatedBefore, DAY_FORMATTER, true, true);
        }
        if ((WORKFLOW_ME.equals(searchWorkflow))) {
            documentCriteria.setActiveRoute(true);
        }
    }

    private static Date toDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static void parseDateCriteria(DocumentCriteria documentCriteria, String value, DateTimeFormatter formatter, boolean isUpdated, boolean isBefore) {
        try {
            Date date = toDate(LocalDate.parse(value, formatter));
            if (isBefore) {
                if (isUpdated) {
                    documentCriteria.setUpdateDateMax(date);
                } else {
                    documentCriteria.setCreateDateMax(date);
                }
            } else {
                if (isUpdated) {
                    documentCriteria.setUpdateDateMin(date);
                } else {
                    documentCriteria.setCreateDateMin(date);
                }
            }
        } catch (DateTimeParseException e) {
            documentCriteria.setCreateDateMin(new Date(0));
            documentCriteria.setCreateDateMax(new Date(0));
        }
    }

    private static void parseDateAtCriteria(DocumentCriteria documentCriteria, String value, boolean isUpdated) {
        try {
            LocalDate date;
            LocalDate endDate;
            switch (value.length()) {
                case 10: {
                    date = LocalDate.parse(value, DATES_FORMATTER);
                    endDate = date.plusDays(1);
                    break;
                }
                case 7: {
                    date = LocalDate.parse(value, MONTH_FORMATTER);
                    endDate = date.plusMonths(1);
                    break;
                }
                case 4: {
                    date = LocalDate.parse(value, YEAR_FORMATTER);
                    endDate = date.plusYears(1);
                    break;
                }
                default: {
                    documentCriteria.setCreateDateMin(new Date(0));
                    documentCriteria.setCreateDateMax(new Date(0));
                    return;
                }
            }
            ZonedDateTime startZdt = date.atStartOfDay(ZoneId.systemDefault());
            ZonedDateTime endZdt = endDate.atStartOfDay(ZoneId.systemDefault()).minusSeconds(1);
            if (isUpdated) {
                documentCriteria.setUpdateDateMin(Date.from(startZdt.toInstant()));
                documentCriteria.setUpdateDateMax(Date.from(endZdt.toInstant()));
            } else {
                documentCriteria.setCreateDateMin(Date.from(startZdt.toInstant()));
                documentCriteria.setCreateDateMax(Date.from(endZdt.toInstant()));
            }
        } catch (DateTimeParseException e) {
            documentCriteria.setCreateDateMin(new Date(0));
            documentCriteria.setCreateDateMax(new Date(0));
        }
    }

    private static void parseTagCriteria(DocumentCriteria documentCriteria, String value, List<TagDto> allTagDtoList, boolean exclusion) {
        List<TagDto> tagDtoList = TagUtil.findByName(value, allTagDtoList);
        if (tagDtoList.isEmpty()) {
            // No tag found, the request must return nothing
            documentCriteria.getTagIdList().add(Lists.newArrayList(UUID.randomUUID().toString()));
        } else {
            List<String> tagIdList = Lists.newArrayList();
            for (TagDto tagDto : tagDtoList) {
                tagIdList.add(tagDto.getId());
                List<TagDto> childrenTagDtoList = TagUtil.findChildren(tagDto, allTagDtoList);
                for (TagDto childrenTagDto : childrenTagDtoList) {
                    tagIdList.add(childrenTagDto.getId());
                }
            }
            if (exclusion) {
                documentCriteria.getExcludedTagIdList().add(tagIdList);
            } else {
                documentCriteria.getTagIdList().add(tagIdList);
            }
        }
    }

    private static void parseLangCriteria(DocumentCriteria documentCriteria, String value) {
        // New language criteria
        if (Constants.SUPPORTED_LANGUAGES.contains(value)) {
            documentCriteria.setLanguage(value);
        } else {
            // Unsupported language, returns no documents
            documentCriteria.setLanguage(UUID.randomUUID().toString());
        }
    }

    private static void parseByCriteria(DocumentCriteria documentCriteria, String value) {
        User user = new UserDao().getActiveByUsername(value);
        if (user == null) {
            // This user doesn't exist, return nothing
            documentCriteria.setCreatorId(UUID.randomUUID().toString());
        } else {
            // This user exists, search its documents
            documentCriteria.setCreatorId(user.getId());
        }
    }
}
