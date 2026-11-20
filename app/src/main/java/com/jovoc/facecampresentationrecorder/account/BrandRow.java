package com.jovoc.facecampresentationrecorder.account;

import java.util.Locale;

/**
 * What the brand row shows: the name, its avatar letters, and whether it can be changed right
 * now. Plain Java with no Android types, so the rules are tested on the JVM; the row itself is
 * {@code ui/BrandRowBinder}.
 */
public final class BrandRow {

    /** The name to show. */
    public final String name;
    /** One or two capital letters for the avatar. */
    public final String initials;
    /** False while a recording runs: the row shows the name but no "Change", and opens nothing. */
    public final boolean changeable;

    private BrandRow(String name, String initials, boolean changeable) {
        this.name = name;
        this.initials = initials;
        this.changeable = changeable;
    }

    /**
     * The row for the current state.
     *
     * @param pickedId      the brand stored for this phone, or null when none is stored
     * @param pickedName    that brand's name, or null
     * @param defaultName   the server's default brand's name, or null when unknown (offline)
     * @param placeholder   the name to show when neither is known
     * @param recordingNow  whether a recording is running
     */
    public static BrandRow of(String pickedId, String pickedName, String defaultName,
                              String placeholder, boolean recordingNow) {
        String name = label(pickedId, pickedName, defaultName, placeholder);
        return new BrandRow(name, initials(name), !recordingNow);
    }

    /**
     * The picked brand's name when one is stored. Without a stored pick, the server default's
     * name when it is known, else the placeholder. A stored pick never falls back to the
     * default: the row must name the brand the phone really posts to.
     */
    public static String label(String pickedId, String pickedName, String defaultName, String placeholder) {
        if (!isEmpty(pickedId)) {
            return isEmpty(pickedName) ? placeholder : pickedName;
        }
        return isEmpty(defaultName) ? placeholder : defaultName;
    }

    /** The first letter of the first two words, capitalised; "?" for an empty name. */
    public static String initials(String name) {
        if (isEmpty(name)) return "?";
        String[] words = name.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length && i < 2; i++) {
            out.appendCodePoint(words[i].codePointAt(0));
        }
        return out.toString().toUpperCase(Locale.ROOT);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
