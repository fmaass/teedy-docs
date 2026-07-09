package com.sismics.docs.core.dao.dto;

/**
 * A single entry of the tag co-occurrence matrix: the two tags that appear together
 * on {@code count} non-deleted documents. {@code tagIdA} is lexicographically less than
 * {@code tagIdB} so each unordered pair appears exactly once.
 *
 * @param tagIdA First tag ID (lexicographically smaller)
 * @param tagIdB Second tag ID (lexicographically larger)
 * @param count Number of documents carrying both tags
 */
public record TagCoOccurrence(String tagIdA, String tagIdB, long count) {
}
