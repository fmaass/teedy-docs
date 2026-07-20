package com.sismics.docs.core.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Decision table for the inbox expunge strategy (#150/#160). A generic folder-wide {@code EXPUNGE}
 * finalizes EVERY {@code \Deleted} message in the folder — including one another IMAP client marked — so
 * the non-UIDPLUS fallback must be gated behind an explicit dedicated-folder acknowledgement. With
 * UIDPLUS a targeted {@code UID EXPUNGE} is always safe regardless of the acknowledgement.
 */
public class TestInboxExpungeMode {

    @Test
    public void uidPlusAlwaysUsesTargetedExpunge() {
        Assertions.assertEquals(InboxService.ExpungeMode.UID_EXPUNGE,
                InboxService.expungeMode(true, false),
                "with UIDPLUS a targeted UID EXPUNGE is used even without the dedicated-folder acknowledgement");
        Assertions.assertEquals(InboxService.ExpungeMode.UID_EXPUNGE,
                InboxService.expungeMode(true, true),
                "with UIDPLUS the acknowledgement is irrelevant — still a targeted UID EXPUNGE");
    }

    @Test
    public void noUidPlusWithoutAcknowledgementSkipsGenericExpunge() {
        Assertions.assertEquals(InboxService.ExpungeMode.SKIP,
                InboxService.expungeMode(false, false),
                "without UIDPLUS and without the dedicated-folder acknowledgement no generic expunge is issued");
    }

    @Test
    public void noUidPlusWithAcknowledgementIssuesGenericExpunge() {
        Assertions.assertEquals(InboxService.ExpungeMode.GENERIC_EXPUNGE,
                InboxService.expungeMode(false, true),
                "without UIDPLUS the generic expunge is issued only once the folder is acknowledged as dedicated");
    }
}
