package com.jovoc.facecampresentationrecorder.activitylog;

/**
 * When the buffered events are sent. Either rule sends everything that is waiting:
 * <ul>
 *   <li>volume: at least {@value #COUNT_TRIGGER} events are waiting and {@code COOLDOWN_MS} have
 *       passed since the last send (a heavy user may send several times a day, but a burst of clicks
 *       does not send every 10 clicks);</li>
 *   <li>time: at least {@code MAX_IDLE_MS} (3 days) have passed since the last send and anything
 *       at all is waiting (a light user still gets flushed).</li>
 * </ul>
 * "Last send" is the last time the server accepted a batch, or the first run of the app before that.
 */
final class UploadPolicy {

    static final int COUNT_TRIGGER = 10;
    static final long COOLDOWN_MS = 15L * 60 * 1000;
    static final long MAX_IDLE_MS = 3L * 24 * 60 * 60 * 1000;

    /** Most events in one request (the backend's limit). */
    static final int BATCH_MAX = 100;
    /** Requests one run of the worker makes before it hands the rest to a follow-up run. */
    static final int MAX_BATCHES_PER_RUN = 5;

    private UploadPolicy() {
    }

    static boolean isDue(int pending, long lastSendAt, long now) {
        if (pending <= 0) return false;
        long since = now - lastSendAt;
        // The clock went back past the last send: flush once, which also sets the clock right again.
        if (since < 0) since = MAX_IDLE_MS;
        if (pending >= COUNT_TRIGGER && since >= COOLDOWN_MS) return true;
        return since >= MAX_IDLE_MS;
    }

    /** Milliseconds until the volume rule can fire (0 when the cooldown is already over). */
    static long delayUntilCountTrigger(long lastSendAt, long now) {
        long wait = lastSendAt + COOLDOWN_MS - now;
        return wait < 0 || wait > COOLDOWN_MS ? 0 : wait;
    }
}
