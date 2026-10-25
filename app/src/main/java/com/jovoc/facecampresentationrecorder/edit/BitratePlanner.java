package com.jovoc.facecampresentationrecorder.edit;

/**
 * Picks the video bitrate for a re-encode: 1.5x the source so the one generation loss is not
 * visible, lowered when that would push the result past the direct-upload cap. Crops are stream
 * copies, so the cropped versions are as big as the edited master.
 */
public final class BitratePlanner {

    /** The server's MOBILE_UPLOAD_MAX_BYTES (4 GiB). */
    public static final long UPLOAD_MAX_BYTES = 4L * 1024 * 1024 * 1024;
    /** Encoders overshoot their target and the container adds a little: aim well below the cap. */
    private static final double SAFETY = 0.9;
    public static final double QUALITY_FACTOR = 1.5;
    public static final int DEFAULT_SOURCE_BPS = 8_000_000;
    public static final int MIN_VIDEO_BPS = 1_000_000;
    private static final int MAX_VIDEO_BPS = 40_000_000;

    private BitratePlanner() {
    }

    /**
     * @param sourceBps   bitrate of the master's video, or 0 when unknown
     * @param durationMs  length of the result
     * @param audioBps    bitrate of the audio that goes into the same file (0 for none)
     * @return bits per second for the video, or 0 when even {@link #MIN_VIDEO_BPS} would not fit
     */
    public static int videoBitrate(long sourceBps, long durationMs, int audioBps) {
        long base = sourceBps > 0 ? sourceBps : DEFAULT_SOURCE_BPS;
        long target = Math.min(MAX_VIDEO_BPS, Math.round(base * QUALITY_FACTOR));
        if (durationMs <= 0) return (int) target;
        long budgetBytes = (long) (UPLOAD_MAX_BYTES * SAFETY);
        long videoBudget = budgetBytes * 8 * 1000 / durationMs - audioBps;
        if (videoBudget < MIN_VIDEO_BPS) return 0;
        return (int) Math.min(target, videoBudget);
    }
}
