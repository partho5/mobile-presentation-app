package com.jovoc.facecampresentationrecorder.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/** Preference keys shared by the recorder screen and the Settings screen, plus the permission check both use. */
public final class RecordSettings {

    private RecordSettings() {}

    public static final String PREF_NAME = "app_prefs";
    public static final String KEY_COUNTDOWN_SECONDS = "key_countdown_seconds";
    public static final String KEY_RECORD_AUDIO = "key_record_audio";
    public static final String KEY_AUTOCROP_9_16 = "key_autocrop_9_16";
    public static final String KEY_AUTOCROP_4_5 = "key_autocrop_4_5";
    public static final String KEY_AUTOCROP_1_1 = "key_autocrop_1_1";

    /** Background replacement prefs, read by BgReplaceService / FaceBgReplaceTask. */
    public static final String BG_PREF_NAME = "facecam_prefs";
    public static final String KEY_BG_REPLACE_ENABLED = "bg_replace_enabled";
    public static final String KEY_BG_ASSET_PATH = "bg_asset_path";

    /** Runtime permissions the app needs that aren't granted yet. */
    public static List<String> missingPermissions(Context context) {
        List<String> missing = new ArrayList<>();
        if (!granted(context, Manifest.permission.CAMERA)) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (!granted(context, Manifest.permission.RECORD_AUDIO)) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return missing;
    }

    private static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }
}
