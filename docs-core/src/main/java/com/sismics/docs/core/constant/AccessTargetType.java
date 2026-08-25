package com.sismics.docs.core.constant;

/**
 * What kind of thing an access event was recorded against (#300).
 *
 * <p>Deliberately NOT a view/download split: the issue's use-definition is one "frequency" per
 * document and one per file, with a preview and a download counting the same. The distinction that
 * matters is only the target's kind, because that decides which id column
 * {@code T_ACCESS_EVENT.ACC_IDTARGET_C} holds.</p>
 */
public enum AccessTargetType {
    /** The accessed target is a document; the target id is a {@code T_DOCUMENT} id. */
    DOCUMENT,

    /** The accessed target is a file; the target id is a {@code T_FILE} id. */
    FILE
}
