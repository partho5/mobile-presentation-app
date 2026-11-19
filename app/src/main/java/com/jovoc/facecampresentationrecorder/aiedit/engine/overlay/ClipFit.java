package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

/**
 * How a clip's own time maps to the slot (spec section 12): progress = time x fit factor, so a longer clip is
 * sped up, never cut; and never slower than native, so a longer slot holds the last frame. Pure maths.
 */
public final class ClipFit {

    private ClipFit() {}

    /**
     * The speed-up of a clip: the plan's {@code fitFactor} when it fits by {@code speed_up}, else 1 (native);
     * never below 1 (no slow motion) and never above {@code maxFactor} (the contract's {@code clip_max_factor}).
     */
    public static double factor(String fitMode, double fitFactor, double maxFactor) {
        if (!"speed_up".equals(fitMode) || Double.isNaN(fitFactor)) return 1;
        return Math.max(1, Math.min(Math.max(1, maxFactor), fitFactor));
    }

    /** The share of the clip shown {@code elapsedMs} after the item starts: 0..1, held at 1 once finished. */
    public static double progress(long elapsedMs, double nativeDurationMs, double factor) {
        if (nativeDurationMs <= 0 || elapsedMs <= 0) return 0;
        return Math.min(1, elapsedMs * Math.max(1, factor) / nativeDurationMs);
    }

    /** The frame shown {@code elapsedMs} after the item starts: 0..frameCount-1, the last one held once finished. */
    public static int frameIndex(long elapsedMs, int frameCount, int fps, double factor) {
        if (frameCount <= 0 || fps <= 0) return 0;
        double p = progress(elapsedMs, frameCount * 1000.0 / fps, factor);
        return Math.min(frameCount - 1, (int) (p * frameCount));
    }
}
