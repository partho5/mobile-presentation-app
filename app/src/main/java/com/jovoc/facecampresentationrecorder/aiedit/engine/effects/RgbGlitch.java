package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * {@code l_rgb_glitch}: the red and blue channels split sideways by {@code offset} (a fraction of the width), the
 * split flipping direction every 50 ms and shrinking towards the end of the item.
 */
public final class RgbGlitch implements VideoEffect {

    public static final String ID = "l_rgb_glitch";

    /** How long the split keeps one direction. */
    static final long FLIP_MS = 50;
    private static final double BACK_SWING = 0.7;
    private static final double FADE = 0.6;

    final long startMs;
    final long endMs;
    final double offset;

    public RgbGlitch(PlanItem item, RegistryEntry entry) {
        startMs = item.startMs;
        endMs = item.endMs;
        offset = Params.number(item, entry, "offset", 0.012);
    }

    /** The signed channel split at {@code outputMs} (fraction of the width): 0 outside the item. */
    double offsetAt(long outputMs) {
        if (outputMs < startMs || outputMs >= endMs) return 0;
        double p = Ease.progress(outputMs, startMs, endMs - startMs);
        double swing = ((outputMs - startMs) / FLIP_MS) % 2 == 0 ? 1 : -BACK_SWING;
        return offset * swing * (1 - FADE * p);
    }

    @Override
    public boolean apply(long outputMs, VideoTransform transform) {
        if (outputMs < startMs || outputMs >= endMs) return false;
        transform.glitch += offsetAt(outputMs);
        return true;
    }
}
