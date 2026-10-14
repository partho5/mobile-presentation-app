package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.jovoc.facecampresentationrecorder.util.RecordSettings;

/**
 * Decides whether the first-run onboarding is shown. It is shown once, and never to people who
 * already used the app before the version that introduced it.
 */
public final class OnboardingGate {

    private static final String KEY_SEEN = "key_onboarding_seen";

    private OnboardingGate() {}

    /** True when onboarding should open instead of the recorder. */
    public static boolean shouldShow(Context context) {
        boolean seen = prefs(context).getBoolean(KEY_SEEN, false);
        long firstInstall = 0;
        long lastUpdate = 0;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            firstInstall = info.firstInstallTime;
            lastUpdate = info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Our own package is always found; if not, treat it as a fresh install.
        }
        if (shouldShow(seen, firstInstall, lastUpdate)) return true;
        if (!seen) markSeen(context); // an existing user who got this version as an update
        return false;
    }

    /**
     * Pure rule (no Android calls, so it is unit-testable). An install that was later updated
     * belongs to an existing user: the two times differ only after an update.
     */
    static boolean shouldShow(boolean seen, long firstInstallTime, long lastUpdateTime) {
        return !seen && lastUpdateTime <= firstInstallTime;
    }

    /** Called when the user finishes or skips onboarding. */
    public static void markSeen(Context context) {
        prefs(context).edit().putBoolean(KEY_SEEN, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(RecordSettings.PREF_NAME, Context.MODE_PRIVATE);
    }
}
