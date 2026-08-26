package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.TagIcon;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unit tests for the tag-icon storage layer (#287).
 *
 * <p>Three properties are load-bearing and none of them is the happy path:</p>
 *
 * <ul>
 *   <li>a tag's icon survives a write/read round trip through the NATIVE query the tag list is
 *       built from — the column is read positionally there, so a mis-ordered projection would
 *       silently hand every tag its neighbour's value;</li>
 *   <li>deleting an icon CLEARS it off the tags that used it, so a tag can never point at an icon
 *       that is gone (there is no foreign key to do it: the same column also holds emoji);</li>
 *   <li>a tag that never had an icon still reads back as having none, which is what every tag in
 *       an upgraded database is.</li>
 * </ul>
 *
 * @author fmaass
 */
public class TestTagIconDao extends BaseTransactionalTest {
    private String createTag(User user, String name, String icon) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor("#3399cc");
        tag.setUserId(user.getId());
        tag.setIcon(icon);
        return new TagDao().create(tag, user.getId());
    }

    private String createIcon(User user, String name) {
        return new TagIconDao().create(new TagIcon()
                .setName(name)
                .setMimeType("image/png")
                .setUserId(user.getId()));
    }

    private void flush() {
        ThreadLocalContext.get().getEntityManager().flush();
    }

    private TagDto readTag(String tagId) {
        List<TagDto> tags = new TagDao().findByCriteria(new TagCriteria().setId(tagId), null);
        Assertions.assertEquals(1, tags.size(), "the tag must be readable");
        return tags.get(0);
    }

    /** An emoji icon round-trips verbatim through the native tag-list projection. */
    @Test
    public void testEmojiIconRoundTripsThroughTheTagListQuery() throws Exception {
        User user = createUser("tagicon_emoji");
        // A ZWJ family: eleven UTF-16 code units and one emoji. If anything on the path
        // re-encoded or truncated the value, this is the shape that would show it.
        String family = "👨‍👩‍👧‍👦";
        String tagId = createTag(user, "family", TagIcon.EMOJI_PREFIX + family);
        flush();

        Assertions.assertEquals(TagIcon.EMOJI_PREFIX + family, readTag(tagId).getIcon(),
                "the tag list must return the emoji exactly as it was stored");
    }

    /** The projection is read positionally: prove the icon is not another column's value. */
    @Test
    public void testTheTagListProjectionKeepsIconAndColourApart() throws Exception {
        User user = createUser("tagicon_columns");
        String tagId = createTag(user, "positional", TagIcon.EMOJI_PREFIX + "⭐");
        flush();

        TagDto tag = readTag(tagId);
        Assertions.assertEquals("positional", tag.getName());
        Assertions.assertEquals("#3399cc", tag.getColor());
        Assertions.assertEquals(TagIcon.EMOJI_PREFIX + "⭐", tag.getIcon());
        Assertions.assertEquals("tagicon_columns", tag.getCreator());
    }

    /** A tag created without an icon has none — the state of every tag in an upgraded database. */
    @Test
    public void testATagWithoutAnIconReadsBackAsHavingNone() throws Exception {
        User user = createUser("tagicon_none");
        String tagId = createTag(user, "plain", null);
        flush();

        Assertions.assertNull(readTag(tagId).getIcon(),
                "a tag with no icon must not acquire one");
    }

    /** An icon can be changed, and cleared again. */
    @Test
    public void testUpdatingATagWritesAndClearsItsIcon() throws Exception {
        User user = createUser("tagicon_update");
        String tagId = createTag(user, "changing", TagIcon.EMOJI_PREFIX + "⭐");
        flush();

        TagDao tagDao = new TagDao();
        Tag tag = tagDao.getById(tagId);
        tag.setIcon(TagIcon.EMOJI_PREFIX + "🔥");
        tagDao.update(tag, user.getId());
        flush();
        Assertions.assertEquals(TagIcon.EMOJI_PREFIX + "🔥", readTag(tagId).getIcon());

        tag = tagDao.getById(tagId);
        tag.setIcon(null);
        tagDao.update(tag, user.getId());
        flush();
        Assertions.assertNull(readTag(tagId).getIcon(),
                "an update must be able to take an icon back off a tag");
    }

    /** The set is listed oldest-first, and a deleted icon is not in it. */
    @Test
    public void testFindAllReturnsTheLiveSetOnly() throws Exception {
        User user = createUser("tagicon_list");
        TagIconDao iconDao = new TagIconDao();
        String first = createIcon(user, "first");
        String second = createIcon(user, "second");
        flush();

        // MEMBERSHIP, not sequence. findAll() orders by createDate and breaks ties on id, and two
        // icons uploaded in the same millisecond tie — so their relative order is decided by two
        // random UUIDs, not by which was uploaded first. Asserting insertion order here passed only
        // by luck of the draw; what the DAO actually promises, and what this test is about, is
        // WHICH icons are in the set.
        Assertions.assertEquals(Set.of(first, second),
                iconDao.findAll().stream().map(TagIcon::getId).collect(Collectors.toSet()),
                "both icons are in the set");

        iconDao.delete(first).orElseThrow();
        flush();
        Assertions.assertEquals(List.of(second),
                iconDao.findAll().stream().map(TagIcon::getId).toList(),
                "a deleted icon leaves the set");
        Assertions.assertNull(iconDao.getActiveById(first),
                "a deleted icon is no longer readable by id");
    }

    /**
     * The one that matters: deleting an icon must leave the tags that used it with NO icon, not
     * with a reference to something that is gone. Without this, every document carrying such a
     * tag would draw a broken image.
     */
    @Test
    public void testDeletingAnIconClearsItOffTheTagsThatUsedIt() throws Exception {
        User user = createUser("tagicon_fallback");
        TagIconDao iconDao = new TagIconDao();
        String doomedIcon = createIcon(user, "doomed");
        String survivingIcon = createIcon(user, "surviving");
        flush();

        String usingDoomed = createTag(user, "uses-doomed", TagIcon.setReference(doomedIcon));
        String alsoUsingDoomed = createTag(user, "also-doomed", TagIcon.setReference(doomedIcon));
        String usingSurviving = createTag(user, "uses-other", TagIcon.setReference(survivingIcon));
        String usingEmoji = createTag(user, "uses-emoji", TagIcon.EMOJI_PREFIX + "⭐");
        flush();

        int cleared = iconDao.delete(doomedIcon).orElseThrow();
        flush();
        ThreadLocalContext.get().getEntityManager().clear();

        Assertions.assertEquals(2, cleared, "both tags pointing at the deleted icon were cleared");
        Assertions.assertNull(readTag(usingDoomed).getIcon(),
                "a tag using the deleted icon falls back to no icon");
        Assertions.assertNull(readTag(alsoUsingDoomed).getIcon(),
                "every tag using the deleted icon falls back, not just the first");
        Assertions.assertEquals(TagIcon.setReference(survivingIcon), readTag(usingSurviving).getIcon(),
                "a tag using a DIFFERENT icon is untouched");
        Assertions.assertEquals(TagIcon.EMOJI_PREFIX + "⭐", readTag(usingEmoji).getIcon(),
                "an emoji icon is untouched — it is not a reference into the set at all");
    }

    /**
     * A SOFT-DELETED tag holding the icon is cleared too.
     *
     * <p>A soft-deleted tag is not gone — the trash restores documents and their tag links, and an
     * admin screen can still read the row. Leaving its {@code TAG_ICON_C} pointing at an icon that
     * has been removed would strand exactly the dangling reference this whole path exists to
     * prevent, and it would surface later, on a restore, with nothing left to explain it.</p>
     */
    @Test
    public void testDeletingAnIconClearsItOffSoftDeletedTagsToo() throws Exception {
        User user = createUser("tagicon_softdel");
        TagIconDao iconDao = new TagIconDao();
        String iconId = createIcon(user, "doomed-soft");
        flush();

        String liveTag = createTag(user, "still-here", TagIcon.setReference(iconId));
        String deadTag = createTag(user, "in-the-bin", TagIcon.setReference(iconId));
        flush();

        // Soft-delete one of the two tags, exactly as the tag screen does.
        new TagDao().delete(deadTag, user.getId());
        flush();

        int cleared = iconDao.delete(iconId).orElseThrow();
        flush();
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        // The clear is a bulk JPQL update, which does not touch the persistence context.
        em.clear();

        Assertions.assertEquals(2, cleared,
                "both the live AND the soft-deleted tag were cleared");
        Assertions.assertNull(readTag(liveTag).getIcon(), "the live tag lost its icon");
        Assertions.assertNull(em.find(Tag.class, deadTag).getIcon(),
                "the soft-deleted tag lost its icon too — nothing is left pointing at a deleted icon");
    }

    /** Deleting an icon that is already gone reports absence rather than throwing. */
    @Test
    public void testDeletingAnAlreadyDeletedIconReportsAbsence() throws Exception {
        User user = createUser("tagicon_twice");
        TagIconDao iconDao = new TagIconDao();
        String iconId = createIcon(user, "gone-twice");
        flush();

        Assertions.assertTrue(iconDao.delete(iconId).isPresent(), "the first delete finds it");
        flush();
        Assertions.assertTrue(iconDao.delete(iconId).isEmpty(),
                "a second delete reports the icon absent instead of throwing");
        Assertions.assertTrue(iconDao.delete("no-such-icon").isEmpty(),
                "an unknown id is absent, not an error");
    }

    /** Deleting an icon nothing uses is not an error and clears nothing. */
    @Test
    public void testDeletingAnUnusedIconClearsNothing() throws Exception {
        User user = createUser("tagicon_unused");
        String iconId = createIcon(user, "unused");
        String tagId = createTag(user, "plain-tag", null);
        flush();

        Assertions.assertEquals(0, new TagIconDao().delete(iconId).orElseThrow());
        flush();
        Assertions.assertNull(readTag(tagId).getIcon());
    }
}
