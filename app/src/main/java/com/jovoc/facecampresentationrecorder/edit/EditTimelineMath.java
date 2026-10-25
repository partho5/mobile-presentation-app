package com.jovoc.facecampresentationrecorder.edit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.jovoc.facecampresentationrecorder.edit.EditProject.Range;

/**
 * The editor screen's pure helpers: segment lookup, the remove-section rule on the edited
 * timeline, join positions and the output file name. No Android classes, so they are unit-tested.
 */
public final class EditTimelineMath {

    private EditTimelineMath() {}

    /**
     * Index of the range holding {@code ms} ({@code start <= ms < end}); the last range also owns
     * its end point so a playhead parked at the very end still has a segment. -1 when {@code ms}
     * falls in a deleted stretch.
     */
    public static int indexAt(List<Range> ranges, long ms) {
        for (int i = 0; i < ranges.size(); i++) {
            Range r = ranges.get(i);
            if (ms >= r.startMs && ms < r.endMs) return i;
        }
        if (!ranges.isEmpty() && ms == ranges.get(ranges.size() - 1).endMs) return ranges.size() - 1;
        return -1;
    }

    /** {@code ms} itself when kept, else the start of the next kept range, else the end of the last. */
    public static long snapToKept(List<Range> ranges, long ms) {
        if (ranges.isEmpty()) return 0;
        if (indexAt(ranges, ms) >= 0) return ms;
        for (Range r : ranges) {
            if (r.startMs > ms) return r.startMs;
        }
        return ranges.get(ranges.size() - 1).endMs;
    }

    /** Start of the first kept range that begins after {@code ms}, or -1. */
    public static long nextStart(List<Range> ranges, long ms) {
        for (Range r : ranges) {
            if (r.startMs > ms) return r.startMs;
        }
        return -1;
    }

    /**
     * True when {@code selection} is a complete range inside the edited timeline that leaves
     * something behind: selecting the whole timeline would remove everything, which is refused.
     */
    public static boolean canRemove(EditProject p, SectionSelection selection, long durationMs) {
        if (!selection.hasRange()) return false;
        long outDur = p.outputDurationMs(durationMs);
        if (selection.getStartMs() < 0 || selection.getEndMs() > outDur) return false;
        return !coversAll(p, selection, durationMs);
    }

    /** True when the selection spans the whole edited timeline (nothing would be left). */
    public static boolean coversAll(EditProject p, SectionSelection selection, long durationMs) {
        return selection.hasRange() && selection.getStartMs() <= 0
                && selection.getEndMs() >= p.outputDurationMs(durationMs);
    }

    /**
     * The project with the selected stretch of the edited timeline cut out and the rest joined,
     * or null when {@link #canRemove} says no. The selection is on the edited timeline, so both
     * ends go through {@link EditProject#outputToSource} first; a selection that spans an earlier
     * join simply removes the kept footage on either side of it.
     */
    public static EditProject removeSection(EditProject p, SectionSelection selection,
                                            long durationMs) {
        if (!canRemove(p, selection, durationMs)) return null;
        long from = p.outputToSource(selection.getStartMs(), durationMs);
        long to = p.outputToSource(selection.getEndMs(), durationMs);
        if (to <= from) return null;
        return p.withDeleted(from, to, durationMs);
    }

    /**
     * Where the joins are on the edited timeline: the output position at which each kept stretch
     * after the first begins. Touching ranges (a leftover split) are one stretch, so no join.
     */
    public static long[] seams(EditProject p, long durationMs) {
        List<Range> ranges = p.renderRanges(durationMs);
        if (ranges.size() < 2) return new long[0];
        long[] out = new long[ranges.size() - 1];
        long acc = 0;
        for (int i = 0; i < out.length; i++) {
            acc += ranges.get(i).lengthMs();
            out[i] = acc;
        }
        return out;
    }

    /**
     * First free {@code name_edit.mp4}, then {@code name_edit_2.mp4}, ... beside the master. A name
     * also counts as taken when one of its crops ({@code name_edit_9x16.mp4}, ...) is left over, so
     * the new crops never overwrite another group's file.
     */
    public static File nextOutputFile(File master) {
        String name = master.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File dir = master.getAbsoluteFile().getParentFile();
        File candidate = new File(dir, base + "_edit.mp4");
        int n = 2;
        while (isTaken(candidate)) {
            candidate = new File(dir, base + "_edit_" + n + ".mp4");
            n++;
        }
        return candidate;
    }

    /** True when both name the same file, also through a relative path or a symlink. */
    public static boolean isSameFile(File a, File b) {
        if (a == null || b == null) return false;
        if (a.getAbsoluteFile().equals(b.getAbsoluteFile())) return true;
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static boolean isTaken(File candidate) {
        if (candidate.exists()) return true;
        String name = candidate.getName();
        String base = name.substring(0, name.length() - ".mp4".length());
        for (String ratio : new String[]{"9x16", "4x5", "1x1"}) {
            if (new File(candidate.getParentFile(), base + "_" + ratio + ".mp4").exists()) return true;
        }
        return false;
    }

    /** m:ss, or h:mm:ss from an hour up. */
    public static String formatTime(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        }
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** m:ss.s (rounded to a tenth), or h:mm:ss.s from an hour up. */
    public static String formatPrecise(long ms) {
        long tenths = (Math.max(0, ms) + 50) / 100;
        long s = tenths / 10;
        String base = s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        return base + "." + (tenths % 10);
    }

    /** A length as seconds with one decimal, e.g. {@code 2.5 s}; minutes are not split out. */
    public static String formatSeconds(long ms) {
        long tenths = (Math.max(0, ms) + 50) / 100;
        return String.format(Locale.US, "%d.%d s", tenths / 10, tenths % 10);
    }
}
