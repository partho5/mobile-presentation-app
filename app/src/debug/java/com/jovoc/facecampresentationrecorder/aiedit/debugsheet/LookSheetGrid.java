package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Palette;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanComponent;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.TextLookSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which look, string and ratio sit where on the debug look sheet, and the plan each cell draws. Pure (no Android classes,
 * no clock, no randomness), like {@link MotionSheetGrid}: the same indexes always give the same cell, and a JVM test pins
 * it.
 *
 * <p>The grid is look-major: every look of the contract, then every string of the matrix, then the three ratios (9:16
 * first). The screen shows one page at a time, a (string, ratio) pair with a tile per look, like the reference sheets. A
 * tick belongs to a look: the owner pages through the strings and ratios and ticks each look once.
 */
public final class LookSheetGrid {

    /** The ratios of the sheet, 9:16 first (the same list as the motion sheet). */
    public static final List<String> RATIOS = MotionSheetGrid.RATIOS;

    /** The moment the full frame is read: past every entry, before the exit. */
    public static final long FRAME_MS = 1200;
    /** The effect the item names (only its defaults are read). */
    public static final String EFFECT = MotionSheetGrid.EFFECT;
    public static final String ZONE = MotionSheetGrid.ZONE;
    /** Tiles per row on a page, as on the reference sheets. */
    public static final int COLUMNS = 2;
    /** The accent palette the plan carries: the look's own slot picks one, so the sheet shows several accents. */
    public static final List<String> ACCENTS = Collections.unmodifiableList(Arrays.asList("#FF3B30", "#FFD60A", "#34C759"));

    /** One cell: indexes into the three axes, resolved. */
    public static final class Cell {
        public final int index;
        public final int lookIdx;
        public final String lookId;
        public final MotionSheetStrings.Entry string;
        public final String ratio;

        Cell(int index, int lookIdx, String lookId, MotionSheetStrings.Entry string, String ratio) {
            this.index = index;
            this.lookIdx = lookIdx;
            this.lookId = lookId;
            this.string = string;
            this.ratio = ratio;
        }
    }

    private LookSheetGrid() {}

    public static int stringCount() {
        return MotionSheetStrings.ALL.size();
    }

    public static int ratioCount() {
        return RATIOS.size();
    }

    /** The number of cells: looks x strings x ratios. */
    public static int size(int lookCount) {
        return lookCount * stringCount() * ratioCount();
    }

    /** The flat position of a cell: look-major, then string, then ratio. */
    public static int index(int lookIdx, int stringIdx, int ratioIdx) {
        return (lookIdx * stringCount() + stringIdx) * ratioCount() + ratioIdx;
    }

    /** The cell at (look, string, ratio) indexes. */
    public static Cell cell(List<String> lookIds, int lookIdx, int stringIdx, int ratioIdx) {
        if (lookIdx < 0 || lookIdx >= lookIds.size() || stringIdx < 0 || stringIdx >= stringCount() || ratioIdx < 0
                || ratioIdx >= ratioCount()) {
            throw new IndexOutOfBoundsException(lookIdx + "," + stringIdx + "," + ratioIdx);
        }
        return new Cell(index(lookIdx, stringIdx, ratioIdx), lookIdx, lookIds.get(lookIdx),
                MotionSheetStrings.ALL.get(stringIdx), RATIOS.get(ratioIdx));
    }

    /** The cell at a flat position. */
    public static Cell cell(List<String> lookIds, int flatIndex) {
        if (flatIndex < 0 || flatIndex >= size(lookIds.size())) throw new IndexOutOfBoundsException("" + flatIndex);
        int ratioIdx = flatIndex % ratioCount();
        int rest = flatIndex / ratioCount();
        return cell(lookIds, rest / stringCount(), rest % stringCount(), ratioIdx);
    }

    /** The tiles of one page: every look, in contract order, for one string and ratio. */
    public static List<Cell> page(List<String> lookIds, int stringIdx, int ratioIdx) {
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < lookIds.size(); i++) cells.add(cell(lookIds, i, stringIdx, ratioIdx));
        return cells;
    }

    /** The row of a tile on a page of {@link #COLUMNS} columns. */
    public static int row(int lookIdx) {
        return lookIdx / COLUMNS;
    }

    /** The column of a tile on a page. */
    public static int column(int lookIdx) {
        return lookIdx % COLUMNS;
    }

    /** The number of rows of a page. */
    public static int rows(int lookCount) {
        return (lookCount + COLUMNS - 1) / COLUMNS;
    }

    /** The accent slot of the plan palette a look draws in: its position modulo the palette, so neighbours differ. */
    public static int colorSlot(int lookIdx) {
        return lookIdx % ACCENTS.size();
    }

    /**
     * The tilt the sheet asks for: the end of the look's range that leans furthest (the worst case for clipping), 0 for a
     * look without tilt. A range that leans equally both ways gives its positive end.
     */
    public static double tilt(TextLook look) {
        if (look.tiltDeg == null) return 0;
        double a = look.tiltDeg[0];
        double b = look.tiltDeg[1];
        return Math.abs(a) > Math.abs(b) ? a : b;
    }

    /**
     * The plan language of a string: only the Turkish string names one (so capitals make a dotted capital i), the rest use
     * the language-neutral rules, as the conformance runner's probes of the same id do.
     */
    public static String language(MotionSheetStrings.Entry string) {
        return "turkish_i".equals(string.id) ? "tr" : null;
    }

    /** The one-item plan a cell draws, from 0 to {@link MotionSheetGrid#ITEM_MS}. */
    public static Plan plan(Cell cell, TextLook look) {
        Map<String, Object> params = new HashMap<>();
        params.put("tilt_deg", tilt(look));
        PlanItem item = new PlanItem("sheet", EFFECT, "text", "overlay", 0, MotionSheetGrid.ITEM_MS, -1, 0, ZONE,
                PlanComponent.styledText("keyword_echo", cell.string.text, cell.lookId, colorSlot(cell.lookIdx)), params, null,
                0, null);
        return new Plan(2, 1, "sheet", "standard", MotionSheetGrid.ITEM_MS, new ArrayList<Plan.Cut>(),
                new ArrayList<Plan.SpeedSegment>(), new ArrayList<Plan.FacecamKey>(), new Palette("multi", ACCENTS),
                Collections.singletonList(item), null, new ArrayList<String>(),
                new ArrayList<com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanAsset>());
    }

    /** True for a look every installed phone already draws; it needs no entry in the list the owner pastes. */
    public static boolean shipped(String lookId) {
        return TextLookSupport.SHIPPED_LOOKS.contains(lookId);
    }

    /** An empty verdict for every look, in contract order. */
    public static Map<String, MotionSheetGrid.Verdict> emptyVerdicts(List<String> lookIds) {
        Map<String, MotionSheetGrid.Verdict> m = new LinkedHashMap<>();
        for (String id : lookIds) m.put(id, MotionSheetGrid.Verdict.UNSET);
        return m;
    }

    /**
     * The text the sheet prints for the owner: the passed looks that are not shipped yet, with the features they need (the
     * server plans a look only when the phone lists the look and every feature it needs), the failed ones, and the looks
     * not ticked. Order is the contract's, so the same ticks always print the same text. A look's features come from the
     * same rule the capabilities use ({@link TextLookSupport#requiredFeatures}).
     */
    public static String report(List<TextLook> looks, Map<String, MotionSheetGrid.Verdict> verdictById) {
        List<String> pass = new ArrayList<>();
        List<String> fail = new ArrayList<>();
        List<String> open = new ArrayList<>();
        List<String> shippedPass = new ArrayList<>();
        List<String> shippedFail = new ArrayList<>();
        Set<String> features = new LinkedHashSet<>();
        List<String> perLook = new ArrayList<>();
        for (TextLook l : looks) {
            MotionSheetGrid.Verdict v = verdictById.get(l.id);
            if (shipped(l.id)) {
                if (v == MotionSheetGrid.Verdict.PASS) shippedPass.add(l.id);
                else if (v == MotionSheetGrid.Verdict.FAIL) shippedFail.add(l.id);
                continue;
            }
            if (v == MotionSheetGrid.Verdict.PASS) {
                pass.add(l.id);
                List<String> need = TextLookSupport.requiredFeatures(l);
                features.addAll(need);
                perLook.add(l.id + " needs " + (need.isEmpty() ? "none" : join(need)));
            } else if (v == MotionSheetGrid.Verdict.FAIL) {
                fail.add(l.id);
            } else {
                open.add(l.id);
            }
        }
        // The features in the server's order, whatever order the looks came in
        List<String> orderedFeatures = new ArrayList<>();
        for (String f : TextLookSupport.IMPLEMENTED_FEATURES) if (features.remove(f)) orderedFeatures.add(f);
        orderedFeatures.addAll(features);
        StringBuilder sb = new StringBuilder();
        sb.append("Passed on a device, looks: ").append(pass.isEmpty() ? "none" : join(pass)).append('\n');
        sb.append("Features those looks need: ").append(orderedFeatures.isEmpty() ? "none" : join(orderedFeatures)).append('\n');
        for (String p : perLook) sb.append("  ").append(p).append('\n');
        sb.append("Failed: ").append(fail.isEmpty() ? "none" : join(fail)).append('\n');
        sb.append("Not ticked: ").append(open.isEmpty() ? "none" : join(open)).append('\n');
        sb.append("Shipped looks (not listed above): passed ").append(shippedPass.isEmpty() ? "none" : join(shippedPass))
                .append("; failed ").append(shippedFail.isEmpty() ? "none" : join(shippedFail));
        return sb.toString();
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
