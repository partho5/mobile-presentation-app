package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

/**
 * A text look has a field, key or value the painter has no handler for, so the whole overlay must fall back to a known
 * plain look instead of being drawn half styled (gate 3). It carries a code and the name of the field, never a text:
 * the code is what the render receipt reports ({@link #PROPERTY_NOT_DRAWN}).
 */
public final class UnsupportedLookException extends Exception {

    /** The receipt code for a look property the phone cannot draw. */
    public static final String PROPERTY_NOT_DRAWN = "property_not_drawn";

    /** The look key (or {@code block.key}) or enum field that has no handler. */
    public final String field;

    public UnsupportedLookException(String field) {
        super(PROPERTY_NOT_DRAWN + ":" + field);
        this.field = field;
    }

    /** The receipt code. */
    public String code() {
        return PROPERTY_NOT_DRAWN;
    }
}
