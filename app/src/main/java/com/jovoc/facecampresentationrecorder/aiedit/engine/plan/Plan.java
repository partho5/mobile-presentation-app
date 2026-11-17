package com.jovoc.facecampresentationrecorder.aiedit.engine.plan;

import java.util.Collections;
import java.util.List;

/**
 * An edit plan as the server sends it (spec section 16): structure on the source timeline, everything else
 * on the OUTPUT timeline after cuts, in integer milliseconds. Immutable, free of Android classes.
 */
public final class Plan {

    /** A stretch of the source that is removed. */
    public static final class Cut {
        public final long srcStartMs;
        public final long srcEndMs;
        public final String reason;

        public Cut(long srcStartMs, long srcEndMs, String reason) {
            this.srcStartMs = srcStartMs;
            this.srcEndMs = srcEndMs;
            this.reason = reason;
        }
    }

    /** A stretch of the source played at {@code rate} (above 1 is faster). */
    public static final class SpeedSegment {
        public final long srcStartMs;
        public final long srcEndMs;
        public final double rate;

        public SpeedSegment(long srcStartMs, long srcEndMs, double rate) {
            this.srcStartMs = srcStartMs;
            this.srcEndMs = srcEndMs;
            this.rate = rate;
        }
    }

    /** Where the facecam is from {@code tMs} (output time); {@code radius} is a fraction of the master width. */
    public static final class FacecamKey {
        public final long tMs;
        public final double cx;
        public final double cy;
        public final double radius;

        public FacecamKey(long tMs, double cx, double cy, double radius) {
            this.tMs = tMs;
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
        }
    }

    public final int contractVersion;
    public final int planVersion;
    public final String sessionId;
    public final String level;
    public final long outputDurationMs;
    public final List<Cut> cuts;
    public final List<SpeedSegment> speedSegments;
    public final List<FacecamKey> facecamTrack;
    public final Palette palette;
    /** Sorted by start time. */
    public final List<PlanItem> items;
    public final PlanCaptions captions;
    public final List<String> warnings;
    /** The files the plan names; empty when the response carried none. */
    public final List<PlanAsset> assets;

    public Plan(int contractVersion, int planVersion, String sessionId, String level, long outputDurationMs,
                List<Cut> cuts, List<SpeedSegment> speedSegments, List<FacecamKey> facecamTrack,
                Palette palette, List<PlanItem> items, PlanCaptions captions, List<String> warnings,
                List<PlanAsset> assets) {
        this.contractVersion = contractVersion;
        this.planVersion = planVersion;
        this.sessionId = sessionId;
        this.level = level;
        this.outputDurationMs = outputDurationMs;
        this.cuts = Collections.unmodifiableList(cuts);
        this.speedSegments = Collections.unmodifiableList(speedSegments);
        this.facecamTrack = Collections.unmodifiableList(facecamTrack);
        this.palette = palette;
        this.items = Collections.unmodifiableList(items);
        this.captions = captions;
        this.warnings = Collections.unmodifiableList(warnings);
        this.assets = Collections.unmodifiableList(assets);
    }

    /** The asset with this id, or null. */
    public PlanAsset asset(String id) {
        for (PlanAsset a : assets) if (a.id.equals(id)) return a;
        return null;
    }
}
