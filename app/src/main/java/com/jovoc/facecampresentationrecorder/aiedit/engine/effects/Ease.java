package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

/**
 * The fast-out easing curves the effects use (spec section 12: out cubic, out back with a small overshoot; no slow
 * ease in-out). Every curve takes a progress clamped to 0..1 and answers 0 at 0 and 1 at 1.
 */
public final class Ease {

    /** The out-back overshoot of about 10 % (the common 1.70158). */
    private static final double BACK = 1.70158;

    private Ease() {}

    public static double clamp01(double p) {
        return p < 0 ? 0 : (p > 1 ? 1 : p);
    }

    /** Progress of {@code t} through {@code [start, start + length)}, clamped; a zero length is already done. */
    public static double progress(long t, long start, long length) {
        if (length <= 0) return t >= start ? 1 : 0;
        return clamp01((double) (t - start) / length);
    }

    public static double outCubic(double p) {
        double q = 1 - clamp01(p);
        return 1 - q * q * q;
    }

    public static double outBack(double p) {
        double q = clamp01(p) - 1;
        return 1 + (BACK + 1) * q * q * q + BACK * q * q;
    }

    /** Linear blend from {@code a} to {@code b}. */
    public static double lerp(double a, double b, double p) {
        return a + (b - a) * p;
    }

    /** By id as the registry names them ({@code out_back}, {@code out_cubic}); anything else is out cubic. */
    public static double byName(String name, double p) {
        return "out_back".equals(name) ? outBack(p) : outCubic(p);
    }
}
