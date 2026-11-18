package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * {@code v_punch_in}: on a key word the picture jumps to {@code scale_to} (105 to 125 %) with an out-back settle,
 * holds, and eases back to 100 %. A beat effect, so the whole move lasts inside the contract's {@code beat_ms}
 * whatever the item's length; the zoom centre is the output centre unless the item gives {@code cx} / {@code cy}.
 */
public final class PunchIn implements VideoEffect {

    public static final String ID = "v_punch_in";

    /** Share of the move spent jumping in, and where the way back starts. */
    static final double IN_SHARE = 0.45;
    static final double OUT_FROM = 0.75;

    private static final double DEFAULT_SCALE = 1.15;

    final long startMs;
    final long durationMs;
    final double scaleTo;
    final String ease;
    private final double cx;
    private final double cy;

    public PunchIn(PlanItem item, RegistryEntry entry, EngineContract.SpeedRules speed) {
        startMs = item.startMs;
        durationMs = clamp(item.durationMs(), speed.beatMs[0], speed.beatMs[1]);
        double fallback = entry != null ? entry.defaultNumber("scale_to", DEFAULT_SCALE) : DEFAULT_SCALE;
        double s = item.paramNumber("scale_to", fallback);
        double[] limit = entry != null ? entry.paramLimits.get("scale_to") : null;
        if (limit != null) s = Math.max(limit[0], Math.min(limit[1], s));
        scaleTo = Math.max(1, s);
        String defaultEase = entry != null && entry.defaultParams.get("ease") instanceof String
                ? (String) entry.defaultParams.get("ease") : "out_back";
        ease = item.paramString("ease", defaultEase);
        cx = item.paramNumber("cx", 0.5);
        cy = item.paramNumber("cy", 0.5);
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The zoom at {@code outputMs}: 1 outside the move. */
    double scaleAt(long outputMs) {
        if (outputMs < startMs || outputMs >= startMs + durationMs) return 1;
        double p = Ease.progress(outputMs, startMs, durationMs);
        if (p < IN_SHARE) return 1 + (scaleTo - 1) * Ease.byName(ease, p / IN_SHARE);
        if (p < OUT_FROM) return scaleTo;
        return Ease.lerp(scaleTo, 1, Ease.outCubic((p - OUT_FROM) / (1 - OUT_FROM)));
    }

    @Override
    public boolean apply(long outputMs, VideoTransform transform) {
        if (outputMs < startMs || outputMs >= startMs + durationMs) return false;
        transform.zoom(scaleAt(outputMs), cx, cy);
        return true;
    }
}
