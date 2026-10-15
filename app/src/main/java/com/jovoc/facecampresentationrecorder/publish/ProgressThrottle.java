package com.jovoc.facecampresentationrecorder.publish;

import java.util.function.LongSupplier;

/**
 * Decides which byte counts are worth reporting: progress only moves forward, by at least a
 * small step and not more often than an interval, so a fast uplink does not flood the
 * notification and the database. The first and the final count always pass.
 */
final class ProgressThrottle {

    static final long MIN_STEP_BYTES = 64 * 1024;
    static final long MIN_INTERVAL_MS = 250;

    private final long total;
    private final long step;
    private final long intervalMs;
    private final LongSupplier clockMs;
    private boolean any;
    private long lastSent;
    private long lastAtMs;

    ProgressThrottle(long total, LongSupplier clockMs) {
        this(total, Math.max(MIN_STEP_BYTES, total / 200), MIN_INTERVAL_MS, clockMs);
    }

    ProgressThrottle(long total, long step, long intervalMs, LongSupplier clockMs) {
        this.total = total;
        this.step = step;
        this.intervalMs = intervalMs;
        this.clockMs = clockMs;
    }

    /** True when {@code sent} should be reported now; remembers it when so. */
    synchronized boolean accept(long sent) {
        long now = clockMs.getAsLong();
        boolean pass;
        if (!any) {
            pass = true;
        } else if (sent <= lastSent) {
            pass = false;
        } else if (sent >= total) {
            pass = true;
        } else {
            pass = sent - lastSent >= step && now - lastAtMs >= intervalMs;
        }
        if (pass) {
            any = true;
            lastSent = sent;
            lastAtMs = now;
        }
        return pass;
    }
}
