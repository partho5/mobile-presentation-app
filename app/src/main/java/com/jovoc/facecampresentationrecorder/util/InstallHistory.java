package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Remembers which app version ran last, so the app can tell a fresh install from an update and name the version
 * it was updated from. Android does not report the replaced version, so it is recorded on every launch from v2 on.
 * v1 (versionCode 4, on Google Play since 2026-09-23) recorded nothing: an install with no record yet that was
 * updated, or that already holds app settings, came from v1.
 */
public final class InstallHistory {

    /** The first Play Store release, which kept no version record. */
    public static final int V1_VERSION_CODE = 4;
    /** "Previous version" of a fresh install. */
    public static final int NONE = 0;
    /** {@link #record} result when nothing changed since the last launch. */
    public static final int NO_CHANGE = -1;

    static final String PREF_NAME = "install_history";
    private static final String KEY_LAST_VERSION = "last_version_code";
    private static final String KEY_UPGRADED_FROM = "upgraded_from_version_code";

    private InstallHistory() {}

    /**
     * Call once per process, first thing on start, before anything writes settings. Returns the version this launch
     * came from ({@link #NONE} for a fresh install) when this is the first launch of the current version, otherwise
     * {@link #NO_CHANGE}.
     */
    public static int record(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int stored = prefs.getInt(KEY_LAST_VERSION, NO_CHANGE);
        long firstInstall = 0;
        long lastUpdate = 0;
        int current = 0;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            firstInstall = info.firstInstallTime;
            lastUpdate = info.lastUpdateTime;
            current = Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            return NO_CHANGE; // our own package is always found
        }
        int from = transition(stored, current, lastUpdate > firstInstall, hasOldSettings(context));
        if (from != NO_CHANGE) {
            prefs.edit()
                    .putInt(KEY_LAST_VERSION, current)
                    .putInt(KEY_UPGRADED_FROM, from)
                    .apply();
        }
        return from;
    }

    /** The version the app was last updated from, or {@link #NONE} if it was installed fresh. */
    public static int previousVersionCode(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_UPGRADED_FROM, NONE);
    }

    /** True when this install was updated from an earlier version rather than installed fresh. */
    public static boolean isUpgrade(Context context) {
        return previousVersionCode(context) != NONE;
    }

    /**
     * Pure rule (unit-testable). {@code stored} is the last recorded version or {@link #NO_CHANGE} if none.
     * Returns the version this launch came from, or {@link #NO_CHANGE} when the current version already ran.
     */
    static int transition(int stored, int current, boolean wasUpdated, boolean hasOldSettings) {
        if (stored == NO_CHANGE) return (wasUpdated || hasOldSettings) ? V1_VERSION_CODE : NONE;
        if (stored != current) return stored;
        return NO_CHANGE;
    }

    private static boolean hasOldSettings(Context context) {
        return !context.getSharedPreferences(RecordSettings.PREF_NAME, Context.MODE_PRIVATE).getAll().isEmpty()
                || !context.getSharedPreferences(RecordSettings.BG_PREF_NAME, Context.MODE_PRIVATE).getAll().isEmpty();
    }
}
