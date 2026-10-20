package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;

import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;

/** The one call at a gated entry point: do the thing, or show why it is locked. */
public final class Gate {

    private Gate() {
    }

    /**
     * Runs {@code action} now when the feature is on. Otherwise shows the upgrade sheet; the action
     * runs later only if the user upgrades and the plan then covers the feature. Main thread only.
     */
    public static void run(Activity activity, String feature, Runnable action) {
        if (Entitlements.has(feature)) {
            if (action != null) action.run();
            return;
        }
        PaywallSheet.show(activity, feature, action);
    }
}
