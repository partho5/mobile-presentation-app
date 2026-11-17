package com.jovoc.facecampresentationrecorder.edit;

import java.util.Objects;

/**
 * The stretch of the edited timeline the user has picked to remove: nothing, just a start marker,
 * or a start and an end. Immutable and free of Android classes, so it is unit-testable. Times are
 * milliseconds on the edited timeline (what is left after earlier removals).
 *
 * Taps drive it through {@link #tap}: the first sets the start, the second the end, later ones
 * move whichever marker is nearer. Two points are always put in order (a tap before the start
 * becomes the start), kept inside {@code [0, duration]} and at least {@link #MIN_LENGTH_MS} apart,
 * so a selection is never invalid.
 */
public final class SectionSelection {

    /** Shortest stretch that can be selected. */
    public static final long MIN_LENGTH_MS = 100;

    public static final SectionSelection EMPTY = new SectionSelection(-1, -1);

    private final long startMs;
    private final long endMs;

    private SectionSelection(long startMs, long endMs) {
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public boolean isEmpty() {
        return startMs < 0;
    }

    /** True once a start marker exists (with or without an end). */
    public boolean hasStart() {
        return startMs >= 0;
    }

    /** True when both markers are set. */
    public boolean hasRange() {
        return startMs >= 0 && endMs >= 0;
    }

    /** The earlier marker, or -1 when nothing is selected. */
    public long getStartMs() {
        return startMs;
    }

    /** The later marker, or -1 until it is set. */
    public long getEndMs() {
        return endMs;
    }

    public long lengthMs() {
        return hasRange() ? endMs - startMs : 0;
    }

    /** True when every marker lies within {@code [0, durationMs]}. */
    public boolean fitsIn(long durationMs) {
        return isEmpty() || (startMs <= durationMs && endMs <= durationMs);
    }

    /** The selection after a tap at {@code ms} on a timeline {@code durationMs} long. */
    public SectionSelection tap(long ms, long durationMs) {
        if (durationMs <= 0) return this;
        long t = Math.max(0, Math.min(durationMs, ms));
        if (!hasStart()) return new SectionSelection(t, -1);
        if (durationMs < MIN_LENGTH_MS) return this;
        if (!hasRange()) return pair(startMs, t, durationMs);
        // Both set: move the nearer marker; a tie moves the end
        return Math.abs(t - startMs) < Math.abs(t - endMs)
                ? pair(endMs, t, durationMs)
                : pair(startMs, t, durationMs);
    }

    /**
     * The selection after "Set start" at {@code ms} (the playhead). Without a range, or when
     * {@code ms} is at or past the old end, the selection starts over from this point; otherwise
     * only the start moves.
     */
    public SectionSelection setStart(long ms, long durationMs) {
        if (durationMs <= 0) return this;
        long t = Math.max(0, Math.min(durationMs, ms));
        if (hasRange() && t <= endMs - MIN_LENGTH_MS) return new SectionSelection(t, endMs);
        return new SectionSelection(t, -1);
    }

    /**
     * The selection after "Set end" at {@code ms} (the playhead). Needs a start at least
     * {@link #MIN_LENGTH_MS} before it; otherwise nothing changes (the caller says why).
     */
    public SectionSelection setEnd(long ms, long durationMs) {
        if (durationMs <= 0 || !hasStart()) return this;
        long t = Math.max(0, Math.min(durationMs, ms));
        if (t < startMs + MIN_LENGTH_MS) return this;
        return new SectionSelection(startMs, t);
    }

    /**
     * The selection after one marker was dragged to {@code ms}. The marker stays inside the
     * timeline and on its side of the other one, at least {@link #MIN_LENGTH_MS} away from it, so
     * it stops at the other marker instead of swapping. Without an end marker only the start can
     * move; asking for the end then, or on an empty selection, changes nothing.
     */
    public SectionSelection moveMarker(boolean start, long ms, long durationMs) {
        if (durationMs <= 0 || !hasStart()) return this;
        long t = Math.max(0, Math.min(durationMs, ms));
        if (start) {
            if (!hasRange()) return new SectionSelection(t, -1);
            return new SectionSelection(Math.min(t, endMs - MIN_LENGTH_MS), endMs);
        }
        if (!hasRange()) return this;
        return new SectionSelection(startMs, Math.max(t, startMs + MIN_LENGTH_MS));
    }

    /** Puts the fixed marker and the tapped one in order, at least the minimum apart and in range. */
    private static SectionSelection pair(long fixed, long tapped, long durationMs) {
        long lo, hi;
        if (tapped >= fixed) {
            lo = fixed;
            hi = tapped;
            if (hi - lo < MIN_LENGTH_MS) {
                hi = lo + MIN_LENGTH_MS;
                if (hi > durationMs) {
                    hi = durationMs;
                    lo = durationMs - MIN_LENGTH_MS;
                }
            }
        } else {
            hi = fixed;
            lo = tapped;
            if (hi - lo < MIN_LENGTH_MS) {
                lo = hi - MIN_LENGTH_MS;
                if (lo < 0) {
                    lo = 0;
                    hi = MIN_LENGTH_MS;
                }
            }
        }
        return new SectionSelection(lo, hi);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SectionSelection)) return false;
        SectionSelection s = (SectionSelection) o;
        return startMs == s.startMs && endMs == s.endMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(startMs, endMs);
    }

    @Override
    public String toString() {
        return "Selection[" + startMs + "," + endMs + ")";
    }
}
