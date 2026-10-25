package com.jovoc.facecampresentationrecorder.edit;

/**
 * The editor timeline's zoom maths: which stretch of the edited timeline is visible (a start and a
 * window length), time <-> pixel mapping inside it, clamping and the ruler's tick spacing.
 * Zoom 1 shows everything (fit); zoom z shows {@code duration / z} milliseconds. No Android
 * classes, so it is unit-tested.
 */
public final class TimelineZoom {

    /** The tightest window: at full zoom about this much of the video is on screen. */
    public static final long MIN_WINDOW_MS = 1000;

    /** Tick spacings the ruler may use, finest first. */
    private static final long[] TICK_STEPS_MS = {
            100, 200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000,
            300_000, 600_000, 900_000, 1_800_000, 3_600_000
    };

    private TimelineZoom() {}

    /** Largest zoom: a {@link #MIN_WINDOW_MS} window, or 1 (fit) when the video is that short or shorter. */
    public static float maxZoom(long durationMs) {
        if (durationMs <= MIN_WINDOW_MS) return 1f;
        return durationMs / (float) MIN_WINDOW_MS;
    }

    /** {@code zoom} limited to 1..{@link #maxZoom}; NaN counts as 1. */
    public static float clampZoom(float zoom, long durationMs) {
        if (Float.isNaN(zoom)) return 1f;
        return Math.max(1f, Math.min(zoom, maxZoom(durationMs)));
    }

    /** Length of the visible window at {@code zoom}; the whole duration at fit. */
    public static long windowMs(long durationMs, float zoom) {
        if (durationMs <= 0) return 0;
        long w = Math.round(durationMs / (double) clampZoom(zoom, durationMs));
        return Math.max(1, Math.min(durationMs, w));
    }

    /** {@code startMs} limited so the window stays inside 0..duration. */
    public static long clampStart(long startMs, long windowMs, long durationMs) {
        if (windowMs >= durationMs) return 0;
        return Math.max(0, Math.min(startMs, durationMs - windowMs));
    }

    /**
     * The window start after zooming from {@code oldZoom} to {@code newZoom} so that the moment
     * under {@code focusFrac} (0 = left edge of the strip, 1 = right edge) stays under it.
     */
    public static long zoomAround(long startMs, float oldZoom, float newZoom, long durationMs,
                                  float focusFrac) {
        float f = Math.max(0f, Math.min(1f, focusFrac));
        long oldW = windowMs(durationMs, oldZoom);
        long newW = windowMs(durationMs, newZoom);
        long focusMs = startMs + Math.round(f * oldW);
        return clampStart(focusMs - Math.round(f * newW), newW, durationMs);
    }

    /** Time at {@code px} pixels from the strip's left edge; clamped to the visible window. */
    public static long msAt(float px, float widthPx, long startMs, long windowMs) {
        if (widthPx <= 0 || windowMs <= 0) return startMs;
        float f = Math.max(0f, Math.min(1f, px / widthPx));
        return startMs + Math.round(f * windowMs);
    }

    /** Pixels from the strip's left edge to {@code ms}; negative / past the width when off screen. */
    public static float pxAt(long ms, float widthPx, long startMs, long windowMs) {
        if (windowMs <= 0) return 0f;
        return (ms - startMs) * widthPx / (float) windowMs;
    }

    /**
     * Window start that keeps {@code playheadMs} visible while it moves. Unchanged while the
     * playhead is in the first 90% of the window; otherwise the window flips so the playhead sits
     * 10% from the left edge.
     */
    public static long followStart(long startMs, long windowMs, long playheadMs, long durationMs) {
        if (windowMs >= durationMs) return 0;
        if (playheadMs >= startMs && playheadMs <= startMs + windowMs * 9 / 10) return startMs;
        return clampStart(playheadMs - windowMs / 10, windowMs, durationMs);
    }

    /** Smallest tick spacing that leaves at least {@code minPx} between neighbouring ticks. */
    public static long tickStepMs(long windowMs, float widthPx, float minPx) {
        if (windowMs <= 0 || widthPx <= 0) return TICK_STEPS_MS[TICK_STEPS_MS.length - 1];
        for (long step : TICK_STEPS_MS) {
            if (step * widthPx / windowMs >= minPx) return step;
        }
        return TICK_STEPS_MS[TICK_STEPS_MS.length - 1];
    }

    /** Ruler label: tenths below a one-second spacing, whole seconds otherwise. */
    public static String formatTick(long ms, long stepMs) {
        return stepMs < 1000 ? EditTimelineMath.formatPrecise(ms) : EditTimelineMath.formatTime(ms);
    }
}
