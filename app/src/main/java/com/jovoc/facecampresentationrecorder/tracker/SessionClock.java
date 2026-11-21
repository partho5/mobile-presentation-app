package com.jovoc.facecampresentationrecorder.tracker;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Decides which app session a record belongs to. A session ends after {@link TrackerConfig#SESSION_GAP_MS}
 * without activity. Pure Java.
 */
public final class SessionClock {

    /** Where the current session id and its last activity time are kept. */
    public interface Store {
        String sessionId();

        long lastActiveAt();

        void save(String id, long at);
    }

    private final Store store;
    private final Supplier<String> ids;

    public SessionClock(Store store, Supplier<String> ids) {
        this.store = store;
        this.ids = ids;
    }

    /** 8 lower case hex characters from a random UUID. */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** The session for an action at {@code now}. Starts a new one when the gap is too long or the clock went back. */
    public String current(long now) {
        String id = store.sessionId();
        long last = store.lastActiveAt();
        if (id == null || now - last > TrackerConfig.SESSION_GAP_MS || now < last) {
            id = ids.get();
        }
        store.save(id, now);
        return id;
    }
}
