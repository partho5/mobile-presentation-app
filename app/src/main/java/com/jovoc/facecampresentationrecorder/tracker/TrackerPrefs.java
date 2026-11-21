package com.jovoc.facecampresentationrecorder.tracker;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The tracker's small state: device id, upload clock, record sequence and session. Lives in
 * {@code tracker_prefs}, which is excluded from backup and device transfer (the device id must not
 * travel to another phone).
 */
public final class TrackerPrefs implements SessionClock.Store {

    private static final String FILE = "tracker_prefs";
    private static final String K_DEVICE = "device_id";
    private static final String K_LAST_SENT = "last_sent_at";
    private static final String K_SEQ = "next_seq";
    private static final String K_SESSION = "session_id";
    private static final String K_ACTIVE = "last_active_at";
    private static final String K_EXIT_CHECK = "last_exit_check";
    private static final String K_BACKGROUND = "background_since";
    private static final String K_FIRST_OPEN = "first_open_done";

    private final SharedPreferences p;

    public TrackerPrefs(Context context) {
        this.p = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String deviceId() {
        return p.getString(K_DEVICE, null);
    }

    public void setDeviceId(String id) {
        p.edit().putString(K_DEVICE, id).apply();
    }

    /** When the last successful upload happened. On first use it is set to now, so the 5-day clock starts then. */
    public long lastSentAt() {
        long v = p.getLong(K_LAST_SENT, 0);
        if (v != 0) return v;
        long now = System.currentTimeMillis();
        p.edit().putLong(K_LAST_SENT, now).apply();
        return now;
    }

    public void setLastSentAt(long at) {
        p.edit().putLong(K_LAST_SENT, at).apply();
    }

    /** Returns the next record sequence number and stores the one after it. */
    public synchronized long reserveSeq() {
        long v = p.getLong(K_SEQ, 1);
        p.edit().putLong(K_SEQ, v + 1).apply();
        return v;
    }

    @Override
    public String sessionId() {
        return p.getString(K_SESSION, null);
    }

    @Override
    public long lastActiveAt() {
        return p.getLong(K_ACTIVE, 0);
    }

    @Override
    public void save(String id, long at) {
        p.edit().putString(K_SESSION, id).putLong(K_ACTIVE, at).apply();
    }

    public long lastExitCheck() {
        return p.getLong(K_EXIT_CHECK, 0);
    }

    public void setLastExitCheck(long at) {
        p.edit().putLong(K_EXIT_CHECK, at).apply();
    }

    /** When the app went to the background, or 0 when it is in the foreground. */
    public long backgroundSince() {
        return p.getLong(K_BACKGROUND, 0);
    }

    public void setBackgroundSince(long at) {
        p.edit().putLong(K_BACKGROUND, at).apply();
    }

    public boolean firstOpenDone() {
        return p.getBoolean(K_FIRST_OPEN, false);
    }

    public void setFirstOpenDone() {
        p.edit().putBoolean(K_FIRST_OPEN, true).apply();
    }
}
