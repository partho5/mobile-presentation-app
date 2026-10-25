package com.jovoc.facecampresentationrecorder.edit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.jovoc.facecampresentationrecorder.edit.EditProject.Range;

/**
 * The editor screen's pure helpers: segment lookup, trim-handle clamping, split/delete rules and
 * the output file name. No Android classes, so they are unit-tested.
 */
public final class EditTimelineMath {

    /** Shortest segment a handle drag or a split may leave. */
    public static final long MIN_SEGMENT_MS = 300;

    /** Most segments a project may have: keeps the timeline drawing and the render quick. */
    public static final int MAX_SEGMENTS = 200;

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

    /** True when a split at {@code ms} would leave two segments of at least the minimum length (and the segment cap allows one more). */
    public static boolean canSplit(List<Range> ranges, long ms) {
        if (ranges.size() >= MAX_SEGMENTS) return false;
        int i = indexAt(ranges, ms);
        if (i < 0) return false;
        Range r = ranges.get(i);
        return ms - r.startMs >= MIN_SEGMENT_MS && r.endMs - ms >= MIN_SEGMENT_MS;
    }

    /** Delete would leave something: there must be another segment besides the chosen one. */
    public static boolean canDelete(List<Range> ranges, int index) {
        return index >= 0 && index < ranges.size() && ranges.size() > 1;
    }

    /**
     * Moves the handles of segment {@code index} to {@code newStart}/{@code newEnd}. A segment can
     * grow back into a deleted stretch but never past its neighbours, never outside the file, and
     * never shrinks below {@link #MIN_SEGMENT_MS}. When only one handle was dragged the other stays.
     */
    public static List<Range> resize(List<Range> ranges, int index, long newStart, long newEnd,
                                     long durationMs) {
        Range old = ranges.get(index);
        long lo = index > 0 ? ranges.get(index - 1).endMs : 0;
        long hi = index < ranges.size() - 1 ? ranges.get(index + 1).startMs : durationMs;
        long min = Math.min(MIN_SEGMENT_MS, Math.max(0, hi - lo));
        boolean startMoved = newStart != old.startMs;

        long start = Math.max(lo, Math.min(newStart, hi));
        long end = Math.max(lo, Math.min(newEnd, hi));
        if (end - start < min) {
            if (startMoved) start = end - min; else end = start + min;
            start = Math.max(lo, start);
            end = Math.min(hi, end);
        }
        List<Range> out = new ArrayList<>(ranges);
        out.set(index, new Range(start, end));
        return out;
    }

    /**
     * The project with its kept ranges replaced; everything else is carried over. A single range
     * covering the whole file is stored as "no cuts" (an empty list), so dragging a handle back to
     * where it started leaves the project equal to what it was.
     */
    public static EditProject withRanges(EditProject p, List<Range> ranges, long durationMs) {
        List<Range> keep = ranges;
        if (ranges.size() == 1 && ranges.get(0).startMs == 0 && ranges.get(0).endMs == durationMs) {
            keep = new ArrayList<>();
        }
        return new EditProject(p.getMasterPath(), keep, p.getOriginal(), p.getAdded());
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
}
