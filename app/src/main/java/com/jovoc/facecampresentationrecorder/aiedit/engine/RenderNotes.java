package com.jovoc.facecampresentationrecorder.aiedit.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the painters did not draw as the plan asked, as codes and plan item ids only (never a text, a path or a
 * message). A render fills it; the flow turns it into the receipt it sends the server. Thread-safe, bounded, and
 * keeps one note per code and item.
 */
public final class RenderNotes {

    /** A text or icon fits nowhere clear of the facecam, so it was left out. */
    public static final String OVERLAY_SKIPPED_NO_ROOM = "overlay_skipped_no_room";
    /** A text was drawn smaller than asked to fit its box. */
    public static final String TEXT_SHRUNK = "text_shrunk";
    /** A text did not fit on one line and was put on two. */
    public static final String TEXT_WRAPPED = "text_wrapped";
    /** A text look has a property the phone cannot draw, so the overlay was drawn in the built-in plain look instead. */
    public static final String PROPERTY_NOT_DRAWN = "property_not_drawn";
    /** An icon's file is missing or cannot be read, so it was left out. */
    public static final String ASSET_MISSING = "asset_missing";

    /** Most notes kept (the server takes at most this many). */
    public static final int MAX_NOTES = 50;

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    /** One note: a code and the plan item it concerns (null when the id is not a plain id). */
    public static final class Note {
        public final String code;
        public final String itemId;

        Note(String code, String itemId) {
            this.code = code;
            this.itemId = itemId;
        }
    }

    private final List<Note> notes = new ArrayList<>();

    /** Records {@code code} for {@code itemId}; an unknown code, a repeat, or one past the limit is dropped. */
    public synchronized void add(String code, String itemId) {
        if (!OVERLAY_SKIPPED_NO_ROOM.equals(code) && !TEXT_SHRUNK.equals(code) && !TEXT_WRAPPED.equals(code)
                && !ASSET_MISSING.equals(code)
                && !PROPERTY_NOT_DRAWN.equals(code)) return;
        String id = itemId != null && ID.matcher(itemId).matches() ? itemId : null;
        if (notes.size() >= MAX_NOTES) return;
        for (Note n : notes) {
            if (n.code.equals(code) && (n.itemId == null ? id == null : n.itemId.equals(id))) return;
        }
        notes.add(new Note(code, id));
    }

    /** A copy of the notes, in the order they were made. */
    public synchronized List<Note> notes() {
        return new ArrayList<>(notes);
    }
}
