package com.jovoc.facecampresentationrecorder.activitylog;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The id that ties together what a user did in one visit. A visit ends when the app has been in
 * the background for {@link #IDLE_MS}; a new process starts a new visit too.
 */
final class SessionTracker {

    static final long IDLE_MS = 30L * 60 * 1000;

    private final LongSupplier clock;
    private final Supplier<String> newId;
    private String id;
    private long backgroundedAt = -1;

    SessionTracker(LongSupplier clock, Supplier<String> newId) {
        this.clock = clock;
        this.newId = newId;
    }

    /** The id of the running session; the first call after the process started opens one. */
    synchronized String current() {
        if (id == null) id = newId.get();
        return id;
    }

    /** The last screen went away. */
    synchronized void onBackground() {
        backgroundedAt = clock.getAsLong();
    }

    /** A screen came back; starts a new session when the app was away long enough. */
    synchronized void onForeground() {
        boolean idle = backgroundedAt >= 0 && clock.getAsLong() - backgroundedAt >= IDLE_MS;
        if (idle || id == null) id = newId.get();
        backgroundedAt = -1;
    }
}
