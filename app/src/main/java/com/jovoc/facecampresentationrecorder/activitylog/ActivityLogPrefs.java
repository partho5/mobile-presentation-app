package com.jovoc.facecampresentationrecorder.activitylog;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small state of the activity log: the device id and the send clock. A plain SharedPreferences file
 * on purpose: the secure store is cleared at sign-out, and this file is excluded from backup and
 * device transfer (data_extraction_rules.xml) so a restored phone never reuses another phone's id.
 */
final class ActivityLogPrefs {

    static final String FILE = "activity_log_prefs";
    private static final String K_DEVICE_ID = "device_id";
    private static final String K_INSTALLED_AT = "installed_at";
    private static final String K_LAST_SEND_AT = "last_send_at";

    private final SharedPreferences sp;

    ActivityLogPrefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!sp.contains(K_INSTALLED_AT)) {
            sp.edit().putLong(K_INSTALLED_AT, System.currentTimeMillis()).apply();
        }
    }

    String deviceId() {
        return sp.getString(K_DEVICE_ID, null);
    }

    void setDeviceId(String id) {
        sp.edit().putString(K_DEVICE_ID, id).apply();
    }

    long installedAt() {
        return sp.getLong(K_INSTALLED_AT, System.currentTimeMillis());
    }

    /** When a batch was last accepted by the server; before the first send, when the app first ran. */
    long lastSendAt() {
        return sp.getLong(K_LAST_SEND_AT, installedAt());
    }

    void setLastSendAt(long millis) {
        sp.edit().putLong(K_LAST_SEND_AT, millis).apply();
    }
}
