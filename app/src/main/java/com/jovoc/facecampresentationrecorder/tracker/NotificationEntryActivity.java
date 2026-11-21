package com.jovoc.facecampresentationrecorder.tracker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * A transparent trampoline for notification taps: it records the entry, opens the real screen and
 * finishes at once. It is never a screen of its own (AutoTracker skips it).
 */
public class NotificationEntryActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            ActivityTracker.setEntry("notification", getIntent().getStringExtra(TrackerNotifications.EXTRA_NOTIF));
            @SuppressWarnings("deprecation")
            Intent target = getIntent().getParcelableExtra(TrackerNotifications.EXTRA_TARGET);
            if (target != null) {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(target); // the trampoline has its own task affinity
            }
        } catch (Throwable ignored) {
            // a tap must always get through; tracking is only the bonus
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
