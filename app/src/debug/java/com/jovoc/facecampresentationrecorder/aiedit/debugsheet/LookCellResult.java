package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the pixel probes found for one cell (look, string, ratio). Pure: ids, codes and numbers only, never the text.
 */
public final class LookCellResult {

    /** How the cell came out. */
    public enum Status {
        /** The overlay was drawn; the probes ran. */
        DRAWN,
        /** The text fits nowhere (no ellipsis, no overlay). */
        SKIPPED,
        /** The look has a property the painter has no handler for. */
        UNSUPPORTED
    }

    public final String lookId;
    public final String stringId;
    public final String ratio;
    public final Status status;
    /** True when the string is over the caps: skipping it is the right answer. */
    public final boolean expectSkip;
    public final List<LookProbes.Result> probes;
    /** The receipt codes the painter noted for the item ({@code text_wrapped}, {@code property_not_drawn}...). */
    public final List<String> notes;
    /** The field named by an {@link Status#UNSUPPORTED} look, else null. */
    public final String unsupportedField;

    public LookCellResult(String lookId, String stringId, String ratio, Status status, boolean expectSkip,
                          List<LookProbes.Result> probes, List<String> notes, String unsupportedField) {
        this.lookId = lookId;
        this.stringId = stringId;
        this.ratio = ratio;
        this.status = status;
        this.expectSkip = expectSkip;
        this.probes = Collections.unmodifiableList(new ArrayList<>(probes));
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        this.unsupportedField = unsupportedField;
    }

    /** The probes that failed. */
    public List<LookProbes.Result> failed() {
        List<LookProbes.Result> f = new ArrayList<>();
        for (LookProbes.Result r : probes) if (!r.pass) f.add(r);
        return f;
    }

    /** True when the painter fell back to the plain look for this overlay (the look was not drawn as asked). */
    public boolean fellBack() {
        return notes.contains("property_not_drawn");
    }

    /**
     * True when nothing is wrong: drawn with every probe passing and no fall back, or skipped where skipping is right.
     * A skip of a string that should fit, an unsupported look, a fall back and any failed probe are all not clean.
     */
    public boolean clean() {
        switch (status) {
            case SKIPPED:
                return expectSkip;
            case UNSUPPORTED:
                return false;
            default:
                return !fellBack() && failed().isEmpty();
        }
    }

    /** A one-line verdict for a tile. */
    public String summary() {
        switch (status) {
            case SKIPPED:
                return expectSkip ? "skipped (expected)" : "SKIPPED (should fit)";
            case UNSUPPORTED:
                return "UNSUPPORTED " + unsupportedField;
            default:
                int fails = failed().size();
                String s = (probes.size() - fails) + "/" + probes.size() + " probes";
                return fellBack() ? s + ", FELL BACK to plain" : s;
        }
    }

    /** The cell's name for a list: {@code string_id ratio}. */
    public String where() {
        return stringId + " " + ratio;
    }
}
