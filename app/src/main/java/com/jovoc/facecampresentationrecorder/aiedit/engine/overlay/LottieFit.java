package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

/**
 * How a Lottie's own time maps to the slot (spec section 12): progress = time x fit factor, so a longer animation
 * is sped up, never cut; and never slower than native, so a longer slot holds the last frame. Pure maths.
 */
public final class LottieFit {

    private LottieFit() {}

    /**
     * The speed-up of a Lottie: the plan's {@code fitFactor} when it fits by {@code speed_up}, else 1 (native);
     * never below 1 (no slow motion) and never above {@code maxFactor} (the contract's {@code lottie_max_factor}).
     */
    public static double factor(String fitMode, double fitFactor, double maxFactor) {
        if (!"speed_up".equals(fitMode) || Double.isNaN(fitFactor)) return 1;
        return Math.max(1, Math.min(Math.max(1, maxFactor), fitFactor));
    }

    /** The share of the animation shown {@code elapsedMs} after the item starts: 0..1, held at 1 once finished. */
    public static double progress(long elapsedMs, double nativeDurationMs, double factor) {
        if (nativeDurationMs <= 0 || elapsedMs <= 0) return 0;
        return Math.min(1, elapsedMs * Math.max(1, factor) / nativeDurationMs);
    }
}
