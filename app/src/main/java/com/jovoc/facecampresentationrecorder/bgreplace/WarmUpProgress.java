package com.jovoc.facecampresentationrecorder.bgreplace;

/**
 * Overall progress of background replacement when the first part (GPU kernel / model warm-up,
 * about {@link #ASSUMED_WARM_UP_MS} when the on-disk cache is missing) reports nothing.
 *
 * <p>While warm-up runs the bar shows an estimate that eases out towards {@link #CAP_PERCENT}
 * and never reaches it; the real 0..100 progress is then mapped into the remaining range, so the
 * bar never goes backwards and never stalls at the cap. When no warm-up is needed the real
 * progress is passed through unchanged and no estimate is made. The 25 s guess is a constant;
 * nothing is measured or stored. Pure logic: time is passed in.
 */
public final class WarmUpProgress {

    public static final long ASSUMED_WARM_UP_MS = 25_000;
    /** Warm-up owns the first 0..30 % of the bar. */
    public static final int CAP_PERCENT = 30;
    /** exp(-3) left at the assumed duration: ~95 % of the cap is reached by then. */
    private static final double TIME_CONSTANT_MS = ASSUMED_WARM_UP_MS / 3.0;

    private final boolean warmUpNeeded;
    private boolean realStarted;
    private int last;

    public WarmUpProgress(boolean warmUpNeeded) {
        this.warmUpNeeded = warmUpNeeded;
    }

    /** True while the bar is an estimate, i.e. a ticker should keep calling {@link #estimate}. */
    public synchronized boolean isEstimating() {
        return warmUpNeeded && !realStarted;
    }

    /** Estimated percent {@code elapsedMs} after the warm-up began; 0 right away, always below the cap. */
    public synchronized int estimate(long elapsedMs) {
        if (!isEstimating()) return last;
        double t = Math.max(0, elapsedMs);
        int value = (int) (CAP_PERCENT * (1 - Math.exp(-t / TIME_CONSTANT_MS)));
        return raise(Math.min(value, CAP_PERCENT - 1));
    }

    /** Maps the real progress (0..100) onto the bar. */
    public synchronized int real(int realPercent) {
        int clamped = Math.max(0, Math.min(100, realPercent));
        if (!warmUpNeeded) return raise(clamped);
        realStarted = true;
        return raise(CAP_PERCENT + clamped * (100 - CAP_PERCENT) / 100);
    }

    private int raise(int value) {
        if (value > last) last = value;
        return last;
    }
}
