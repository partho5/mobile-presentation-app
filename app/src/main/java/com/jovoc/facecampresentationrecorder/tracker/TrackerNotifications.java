package com.jovoc.facecampresentationrecorder.tracker;

import android.content.Context;
import android.content.Intent;

/**
 * Wraps the Intent of a notification so the tap is recorded as an entry (source "notification") before
 * the real screen opens. The wrapper is {@link NotificationEntryActivity}, which forwards and finishes.
 */
public final class TrackerNotifications {

    static final String EXTRA_TARGET = "tracker_target";
    static final String EXTRA_NOTIF = "tracker_notif";

    private TrackerNotifications() { }

    /**
     * The Intent to hand to PendingIntent for a notification that opens {@code target}. A null target is
     * returned unchanged. Never throws.
     */
    public static Intent wrap(Context c, Intent target, String notif) {
        try {
            if (target == null) return null;
            return new Intent(c, NotificationEntryActivity.class)
                    .putExtra(EXTRA_TARGET, target)
                    .putExtra(EXTRA_NOTIF, notif)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Throwable ignored) {
            return target;
        }
    }
}
