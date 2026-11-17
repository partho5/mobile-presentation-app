package com.jovoc.facecampresentationrecorder.aiedit.engine.plan;

import java.util.Collections;
import java.util.List;

/** The text colours the server already resolved: the multi colours, or the one workspace colour. */
public final class Palette {
    /** {@code multi} or {@code brand}. */
    public final String mode;
    /** Hex colours such as {@code #FF3B30}; never empty. */
    public final List<String> colors;

    public Palette(String mode, List<String> colors) {
        if (colors.isEmpty()) throw new IllegalArgumentException("empty palette");
        this.mode = mode;
        this.colors = Collections.unmodifiableList(colors);
    }

    /** The colour for a plan item's {@code color_slot}; slots rotate through the colours. */
    public String colorFor(int slot) {
        return colors.get(Math.floorMod(slot, colors.size()));
    }
}
