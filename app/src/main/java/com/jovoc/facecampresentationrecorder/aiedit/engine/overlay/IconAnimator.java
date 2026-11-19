package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.effects.Ease;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * The motion of one icon overlay ({@code i_scale_pop}) on the output timeline: a scale pop in (0 to a
 * small overshoot, then 1, out back), a hold, and the exit (scale 1 to 0.85 while the alpha falls, out cubic).
 * Entry and exit times are clamped to the contract's {@code speed} and to the item's length. Pure maths: it fills a
 * reusable {@link State}; the painter turns that into canvas calls.
 */
public final class IconAnimator {

    /** What the painter draws at one moment. Reused every frame. */
    public static final class State {
        public float scale = 1;
        public float alpha = 1;

        void reset() {
            scale = 1;
            alpha = 1;
        }
    }

    static final float EXIT_SCALE = 0.85f;
    /** The entry's alpha is full after this share of the entry. */
    private static final double FADE_IN_SHARE = 0.4;

    final long startMs;
    final long endMs;
    final long entryMs;
    final long exitMs;

    public IconAnimator(PlanItem item, RegistryEntry registry, EngineContract.SpeedRules speed) {
        startMs = item.startMs;
        endMs = item.endMs;
        long length = Math.max(0, endMs - startMs);
        long exit = clamp(Math.round(number(item, registry, "exit_ms", 150)), speed.exitMs[0], speed.exitMs[1]);
        exit = Math.min(exit, length / 2);
        long ent = Math.round(limit(registry, "entry_ms", number(item, registry, "entry_ms", 200)));
        ent = clamp(ent, speed.entryMs[0], speed.entryMs[1]);
        entryMs = Math.min(ent, Math.max(0, length - exit));
        exitMs = exit;
    }

    /**
     * Fills {@code out} for {@code outputMs}.
     *
     * @return false when the overlay is not on screen at that time
     */
    public boolean at(long outputMs, State out) {
        out.reset();
        if (outputMs < startMs || outputMs >= endMs) return false;
        if (outputMs - startMs < entryMs) {
            double p = Ease.progress(outputMs, startMs, entryMs);
            out.scale = (float) Ease.outBack(p);
            out.alpha = (float) Ease.clamp01(p / FADE_IN_SHARE);
        }
        if (exitMs > 0 && outputMs >= endMs - exitMs) {
            double p = Ease.outCubic(Ease.progress(outputMs, endMs - exitMs, exitMs));
            out.scale = (float) Ease.lerp(1, EXIT_SCALE, p);
            out.alpha = (float) (1 - p);
        }
        return true;
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double number(PlanItem item, RegistryEntry e, String key, double fallback) {
        return item.paramNumber(key, e != null ? e.defaultNumber(key, fallback) : fallback);
    }

    private static double limit(RegistryEntry e, String key, double v) {
        double[] r = e != null ? e.paramLimits.get(key) : null;
        return r == null ? v : Math.max(r[0], Math.min(r[1], v));
    }
}
