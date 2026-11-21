package com.jovoc.facecampresentationrecorder.tracker;

/** When a batch upload is due. Pure Java: the clock and the counts are passed in. */
final class UploadPolicy {

    static final long DAY_MS = 24L * 60 * 60 * 1000;

    private UploadPolicy() { }

    /** Whether a send is due now. pending ≤ 0 → never. */
    static boolean isDue(int pending, long lastSentAt, long now) {
        if (pending <= 0) return false;
        long since = now - lastSentAt;
        if (since < 0) return true;                                   // clock went back: send once, which resets it
        if (pending > TrackerConfig.SEND_WHEN_MORE_THAN && since >= TrackerConfig.MIN_GAP_MS) return true;
        return since >= TrackerConfig.SEND_EVERY_DAYS * DAY_MS;
    }

    /** ms to wait before the volume rule may fire (0 when it may fire now). */
    static long delayForVolume(long lastSentAt, long now) {
        long w = lastSentAt + TrackerConfig.MIN_GAP_MS - now;
        return (w < 0 || w > TrackerConfig.MIN_GAP_MS) ? 0 : w;
    }
}
