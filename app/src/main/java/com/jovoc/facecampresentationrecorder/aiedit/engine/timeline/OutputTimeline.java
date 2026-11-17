package com.jovoc.facecampresentationrecorder.aiedit.engine.timeline;

import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Maps between the SOURCE timeline (the file that was sent to the server) and the OUTPUT timeline of the plan
 * (after cuts and speed segments), and answers where the facecam is. Pure maths, no Android classes.
 *
 * The source is cut into pieces that survive; each piece has one playback rate. A cut removes its stretch, a speed
 * segment at rate r makes its stretch take 1/r of the time. Times are whole milliseconds; a stretch at a
 * non-integer rate keeps its exact length inside, and only the answers are rounded.
 */
public final class OutputTimeline {

    private final long sourceDurationMs;
    private final long[] pieceSrcStart;
    private final long[] pieceSrcEnd;
    private final double[] pieceRate;
    private final double[] pieceOutStart;
    private final double totalOut;
    private final List<Plan.FacecamKey> facecam;

    /**
     * @param sourceDurationMs length of the source file
     * @param cuts             stretches of the source to remove; may overlap or run past the ends
     * @param speedSegments    stretches of the source with a rate; a stretch inside a cut is ignored, and where
     *                         two overlap the earlier one wins
     * @param facecamTrack     the facecam on the output timeline, sorted by time (as the plan has it)
     */
    public OutputTimeline(long sourceDurationMs, List<Plan.Cut> cuts, List<Plan.SpeedSegment> speedSegments,
                          List<Plan.FacecamKey> facecamTrack) {
        if (sourceDurationMs < 0) throw new IllegalArgumentException("negative duration");
        this.sourceDurationMs = sourceDurationMs;
        this.facecam = Collections.unmodifiableList(new ArrayList<>(facecamTrack));

        // Every place where something changes; between two neighbours nothing does.
        TreeSet<Long> edges = new TreeSet<>();
        edges.add(0L);
        edges.add(sourceDurationMs);
        for (Plan.Cut c : cuts) {
            edges.add(clamp(c.srcStartMs, sourceDurationMs));
            edges.add(clamp(c.srcEndMs, sourceDurationMs));
        }
        List<Plan.SpeedSegment> speeds = new ArrayList<>(speedSegments);
        Collections.sort(speeds, new java.util.Comparator<Plan.SpeedSegment>() {
            @Override
            public int compare(Plan.SpeedSegment a, Plan.SpeedSegment b) {
                return Long.compare(a.srcStartMs, b.srcStartMs);
            }
        });
        for (Plan.SpeedSegment s : speeds) {
            edges.add(clamp(s.srcStartMs, sourceDurationMs));
            edges.add(clamp(s.srcEndMs, sourceDurationMs));
        }

        List<long[]> spans = new ArrayList<>();
        List<Double> rates = new ArrayList<>();
        Long prev = null;
        for (Long edge : edges) {
            if (prev != null && edge > prev) {
                if (!insideCut(cuts, prev)) {
                    double rate = rateAt(speeds, prev);
                    int last = spans.size() - 1;
                    // Join with the piece before when it touches and plays at the same rate.
                    if (last >= 0 && spans.get(last)[1] == prev && rates.get(last) == rate) {
                        spans.get(last)[1] = edge;
                    } else {
                        spans.add(new long[]{prev, edge});
                        rates.add(rate);
                    }
                }
            }
            prev = edge;
        }

        int n = spans.size();
        pieceSrcStart = new long[n];
        pieceSrcEnd = new long[n];
        pieceRate = new double[n];
        pieceOutStart = new double[n];
        double out = 0;
        for (int i = 0; i < n; i++) {
            pieceSrcStart[i] = spans.get(i)[0];
            pieceSrcEnd[i] = spans.get(i)[1];
            pieceRate[i] = rates.get(i);
            pieceOutStart[i] = out;
            out += (pieceSrcEnd[i] - pieceSrcStart[i]) / pieceRate[i];
        }
        totalOut = out;
    }

    /** The timeline of a plan over a source of {@code sourceDurationMs}. */
    public static OutputTimeline fromPlan(Plan plan, long sourceDurationMs) {
        return new OutputTimeline(sourceDurationMs, plan.cuts, plan.speedSegments, plan.facecamTrack);
    }

    private static long clamp(long v, long max) {
        return Math.max(0, Math.min(max, v));
    }

    private static boolean insideCut(List<Plan.Cut> cuts, long t) {
        for (Plan.Cut c : cuts) if (t >= c.srcStartMs && t < c.srcEndMs) return true;
        return false;
    }

    private static double rateAt(List<Plan.SpeedSegment> speeds, long t) {
        for (Plan.SpeedSegment s : speeds) if (t >= s.srcStartMs && t < s.srcEndMs) return s.rate;
        return 1.0;
    }

    public long getSourceDurationMs() {
        return sourceDurationMs;
    }

    /** Length of the output in milliseconds. */
    public long outputDurationMs() {
        return Math.round(totalOut);
    }

    /** Whether the source millisecond survives (it is not inside a cut). */
    public boolean isKept(long srcMs) {
        int i = pieceAtSource(srcMs);
        return i >= 0 && srcMs < pieceSrcEnd[i];
    }

    /**
     * The output time of a source time. A time inside a cut answers the output time of the cut point (where
     * the next surviving stretch starts); a time past the end answers the output length.
     */
    public long sourceToOutput(long srcMs) {
        if (pieceSrcStart.length == 0) return 0;
        long t = clamp(srcMs, sourceDurationMs);
        int i = pieceAtSource(t);
        if (i < 0) return Math.round(pieceOutStart[0]);
        if (t < pieceSrcEnd[i]) return Math.round(pieceOutStart[i] + (t - pieceSrcStart[i]) / pieceRate[i]);
        if (i + 1 < pieceSrcStart.length) return Math.round(pieceOutStart[i + 1]);
        return Math.round(totalOut);
    }

    /** The source time shown at an output time; outside the output it answers the first or last source time. */
    public long outputToSource(long outMs) {
        int n = pieceSrcStart.length;
        if (n == 0) return 0;
        if (outMs <= 0) return pieceSrcStart[0];
        if (outMs >= totalOut) return pieceSrcEnd[n - 1];
        int i = pieceAtOutput(outMs);
        long src = pieceSrcStart[i] + Math.round((outMs - pieceOutStart[i]) * pieceRate[i]);
        return Math.min(src, pieceSrcEnd[i]);
    }

    /** How many surviving pieces the source is played as, in order. */
    public int pieceCount() {
        return pieceSrcStart.length;
    }

    /** Source start of piece {@code i} in ms. */
    public long pieceSourceStartMs(int i) {
        return pieceSrcStart[i];
    }

    /** Source end (exclusive) of piece {@code i} in ms. */
    public long pieceSourceEndMs(int i) {
        return pieceSrcEnd[i];
    }

    /** Playback rate of piece {@code i}. */
    public double pieceRate(int i) {
        return pieceRate[i];
    }

    /** Output start of piece {@code i} in ms, unrounded. */
    public double pieceOutputStartMs(int i) {
        return pieceOutStart[i];
    }

    /** Exact output length in ms, unrounded. */
    public double exactOutputDurationMs() {
        return totalOut;
    }

    /** The playback rate at an output time (1 outside the output and where no speed segment applies). */
    public double rateAtOutput(long outMs) {
        int n = pieceSrcStart.length;
        if (n == 0 || outMs < 0 || outMs >= totalOut) return 1.0;
        return pieceRate[pieceAtOutput(outMs)];
    }

    /** The last piece that starts at or before the source time, or -1 when the time is before the first piece. */
    private int pieceAtSource(long t) {
        int lo = 0;
        int hi = pieceSrcStart.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (pieceSrcStart[mid] <= t) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** The last piece that starts at or before the output time (0 when before the first). */
    private int pieceAtOutput(double t) {
        int lo = 0;
        int hi = pieceOutStart.length - 1;
        int found = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (pieceOutStart[mid] <= t) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /**
     * Where the facecam is at an output time: the latest key at or before it, or the first key before any.
     * The track is a step function (a key is a move the user made). Null when the plan has no facecam.
     */
    public Plan.FacecamKey facecamAt(long outputMs) {
        if (facecam.isEmpty()) return null;
        Plan.FacecamKey found = facecam.get(0);
        for (Plan.FacecamKey k : facecam) {
            if (k.tMs <= outputMs) found = k;
            else break;
        }
        return found;
    }

    /**
     * The top-anchored, full-width crop of a master for an output ratio such as {@code 9:16}: only rows are
     * dropped from the bottom (the recorder's own crop, see VideoCropHelper). Heights are rounded down to even.
     * A master too short for the ratio (a landscape video) is cropped centred on the full height instead.
     *
     * @throws IllegalArgumentException for a ratio that is not {@code a:b}
     */
    public static CropRect cropFor(String ratio, int masterW, int masterH) {
        int colon = ratio == null ? -1 : ratio.indexOf(':');
        if (colon <= 0 || masterW <= 0 || masterH <= 0) throw new IllegalArgumentException("bad ratio or size");
        long a;
        long b;
        try {
            a = Long.parseLong(ratio.substring(0, colon));
            b = Long.parseLong(ratio.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad ratio", e);
        }
        if (a <= 0 || b <= 0) throw new IllegalArgumentException("bad ratio");
        int w = masterW;
        long h = (long) masterW * b / a;
        if (h > masterH) {
            h = masterH;
            w = (int) (h * a / b);
        }
        int ch = (int) (h & ~1L);
        int cw = w & ~1;
        int x = ((masterW - cw) / 2) & ~1;
        return new CropRect(x, 0, cw, ch);
    }
}
