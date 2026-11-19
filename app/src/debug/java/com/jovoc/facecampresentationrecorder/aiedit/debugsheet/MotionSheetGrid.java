package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Palette;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanComponent;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which motion, string and ratio sit where on the debug motion sheet, and the plan each cell plays. Pure (no Android
 * classes, no clock, no randomness): the same indexes always give the same cell, so the owner's tick of a motion means
 * the same thing every time, and a JVM test can pin the whole grid.
 *
 * <p>The grid is motion-major: every motion, then every string of the matrix, then the three ratios (9:16 first).
 * A tick belongs to a motion (not a cell): the owner steps through the strings and ratios of one motion and ticks it once.
 */
public final class MotionSheetGrid {

    /** The ratios of the sheet, 9:16 first. */
    public static final List<String> RATIOS = Collections.unmodifiableList(Arrays.asList("9:16", "4:5", "1:1"));

    public static final int ENTRY = 0;
    public static final int HOLD = 1;
    public static final int EXIT = 2;

    /** How long every sheet item is on screen: room for the longest entry, a hold cycle and the exit. */
    public static final long ITEM_MS = 2400;
    /** The look every cell uses (no tilt, so the motion alone is judged). */
    public static final String LOOK = "label_pill";
    /** The registry effect the item names (only its defaults are read). */
    public static final String EFFECT = "x_label_tag";
    public static final String ZONE = "middle_center";

    /** One motion: its kind, its plan id ({@code fade}, {@code float}, ...) and whether every installed phone already draws it. */
    public static final class Motion {
        public final int kind;
        public final String id;
        public final boolean legacy;

        Motion(int kind, String id, boolean legacy) {
            this.kind = kind;
            this.id = id;
            this.legacy = legacy;
        }

        /** The id in the server's naming, as it goes into "Passed on a device": entry plain, {@code hold_<id>}, {@code exit_<id>}. */
        public String serverId() {
            return kind == HOLD ? "hold_" + id : kind == EXIT ? "exit_" + id : id;
        }

        @Override
        public String toString() {
            return serverId();
        }
    }

    /** One cell: indexes into the three axes, resolved. */
    public static final class Cell {
        public final int index;
        public final Motion motion;
        public final MotionSheetStrings.Entry string;
        public final String ratio;

        Cell(int index, Motion motion, MotionSheetStrings.Entry string, String ratio) {
            this.index = index;
            this.motion = motion;
            this.string = string;
            this.ratio = ratio;
        }
    }

    /** The motions in the order of the animation table: entries, then holds, then exits. */
    public static final List<Motion> MOTIONS;

    static {
        List<Motion> m = new ArrayList<>();
        for (String id : new String[]{"pop", "stamp", "word_by_word"}) m.add(new Motion(ENTRY, id, true));
        for (String id : new String[]{"fade", "slide_left", "slide_right", "slide_top", "slide_bottom", "typewriter",
                "wipe", "drop_bounce", "blur_in", "flip", "glitch"}) {
            m.add(new Motion(ENTRY, id, false));
        }
        for (String id : new String[]{"float", "pulse", "shake", "shimmer", "neon_flicker"}) {
            m.add(new Motion(HOLD, id, false));
        }
        for (String id : new String[]{"fade", "slide_out", "shrink", "pop_out", "typewriter_delete", "wipe_out"}) {
            m.add(new Motion(EXIT, id, false));
        }
        MOTIONS = Collections.unmodifiableList(m);
    }

    private MotionSheetGrid() {}

    public static int motionCount() {
        return MOTIONS.size();
    }

    public static int stringCount() {
        return MotionSheetStrings.ALL.size();
    }

    public static int ratioCount() {
        return RATIOS.size();
    }

    /** The number of cells: motions x strings x ratios. */
    public static int size() {
        return motionCount() * stringCount() * ratioCount();
    }

    /** The cell at (motion, string, ratio) indexes. */
    public static Cell cell(int motionIdx, int stringIdx, int ratioIdx) {
        if (motionIdx < 0 || motionIdx >= motionCount() || stringIdx < 0 || stringIdx >= stringCount()
                || ratioIdx < 0 || ratioIdx >= ratioCount()) {
            throw new IndexOutOfBoundsException(motionIdx + "," + stringIdx + "," + ratioIdx);
        }
        return new Cell(index(motionIdx, stringIdx, ratioIdx), MOTIONS.get(motionIdx),
                MotionSheetStrings.ALL.get(stringIdx), RATIOS.get(ratioIdx));
    }

    /** The flat position of a cell: motion-major, then string, then ratio. */
    public static int index(int motionIdx, int stringIdx, int ratioIdx) {
        return (motionIdx * stringCount() + stringIdx) * ratioCount() + ratioIdx;
    }

    /** The cell at a flat position. */
    public static Cell cell(int flatIndex) {
        if (flatIndex < 0 || flatIndex >= size()) throw new IndexOutOfBoundsException("" + flatIndex);
        int ratioIdx = flatIndex % ratioCount();
        int rest = flatIndex / ratioCount();
        return cell(rest / stringCount(), rest % stringCount(), ratioIdx);
    }

    /** The params the server would send for a motion: only the key of its kind (hold and exit keep the default entry). */
    public static Map<String, Object> params(Motion motion) {
        Map<String, Object> p = new HashMap<>();
        p.put("tilt_deg", 0L);
        if (motion.kind == ENTRY) p.put("entry", motion.id);
        else if (motion.kind == HOLD) p.put("hold", motion.id);
        else {
            p.put("exit", motion.id);
            if ("slide_out".equals(motion.id)) p.put("exit_dir", "left");
        }
        return p;
    }

    /** The one-item plan a cell plays, from 0 to {@link #ITEM_MS}. */
    public static Plan plan(Cell cell) {
        PlanItem item = new PlanItem("sheet", EFFECT, "text", "overlay", 0, ITEM_MS, -1, 0, ZONE,
                PlanComponent.styledText("keyword_echo", cell.string.text, LOOK, 0), params(cell.motion), null, 0, null);
        return new Plan(2, 1, "sheet", "standard", ITEM_MS, new ArrayList<Plan.Cut>(),
                new ArrayList<Plan.SpeedSegment>(), new ArrayList<Plan.FacecamKey>(),
                new Palette("multi", Arrays.asList("#FF3B30", "#FFD60A", "#34C759")),
                Collections.singletonList(item), null, new ArrayList<String>(), new ArrayList<com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanAsset>());
    }

    /** A tick of one motion. */
    public enum Verdict { UNSET, PASS, FAIL }

    /**
     * The text the sheet prints for the owner: the passed and failed ids in server naming, and a line to paste into
     * "Passed on a device". Legacy motions (already shipped on every phone) are left out of the pasted list. Order is the
     * grid's, so the same ticks always print the same text.
     */
    public static String report(Map<String, Verdict> verdictByServerId) {
        List<String> pass = new ArrayList<>();
        List<String> fail = new ArrayList<>();
        List<String> open = new ArrayList<>();
        for (Motion m : MOTIONS) {
            if (m.legacy) continue;
            Verdict v = verdictByServerId.get(m.serverId());
            if (v == Verdict.PASS) pass.add(m.serverId());
            else if (v == Verdict.FAIL) fail.add(m.serverId());
            else open.add(m.serverId());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Passed on a device, motions: ").append(pass.isEmpty() ? "none" : join(pass)).append('\n');
        sb.append("Failed: ").append(fail.isEmpty() ? "none" : join(fail)).append('\n');
        sb.append("Not ticked: ").append(open.isEmpty() ? "none" : join(open));
        return sb.toString();
    }

    /** Every motion's server id mapped to {@link Verdict#UNSET}, in grid order. */
    public static Map<String, Verdict> emptyVerdicts() {
        Map<String, Verdict> m = new LinkedHashMap<>();
        for (Motion mo : MOTIONS) m.put(mo.serverId(), Verdict.UNSET);
        return m;
    }

    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String s : ids) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }
}
