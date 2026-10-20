package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import com.jovoc.facecampresentationrecorder.R;
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

    /**
     * For a locked control that is on screen only while recording (drawing board, pen): the sheet
     * would end up in the video, so a short toast says what it needs. The plans sheet in the
     * account screen is where to upgrade afterwards.
     */
    public static void lockedWhileRecording(Context context, String feature) {
        String tier = TierBadge.name(Entitlements.lowestPlanFor(feature));
        Toast.makeText(context, context.getString(R.string.plans_locked_recording,
                FeatureLabels.name(context, feature), tier), Toast.LENGTH_SHORT).show();
    }
}
