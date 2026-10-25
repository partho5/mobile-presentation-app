package com.jovoc.facecampresentationrecorder.util;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.entitlements.FeatureSnapshot;

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
    /** "Auto-publish new recordings" (Settings); off by default, per device. */
    public static final String KEY_AUTO_PUBLISH = "key_auto_publish";
    /** "AI captions" (Auto upload and Publish screens); on by default, needs the ai_captions feature. */
    public static final String KEY_AI_CAPTIONS = "key_ai_captions";
    /** Seconds an auto-publish waits before uploading so it can be cancelled: 0 (Off), 30 or 60; default 30. */
    public static final String KEY_AUTO_PUBLISH_UNDO_SECONDS = "key_auto_publish_undo_seconds";

    /** "Upload on Wi-Fi only" (Settings): publish uploads wait for an unmetered network; default off. */
    public static final String KEY_PUBLISH_WIFI_ONLY = "key_publish_wifi_only";

    /** "Show video editor after recording" (Settings); off by default, per device. */
    public static final String KEY_SHOW_EDITOR_AFTER_RECORDING = "key_show_editor_after_recording";

    /** Background replacement prefs, read by BgReplaceService / FaceBgReplaceTask. */
    public static final String BG_PREF_NAME = "facecam_prefs";
    public static final String KEY_BG_REPLACE_ENABLED = "bg_replace_enabled";
    public static final String KEY_BG_ASSET_PATH = "bg_asset_path";

    /**
     * Gated getters: the stored choice AND the plan. A lapsed plan keeps the stored value but it
     * reads as off, so it comes back by itself if the plan does. Without a snapshot they follow the
     * running recording's (see {@link FeatureSnapshot#allows}), else the plan as it is now; the
     * post-recording job passes the snapshot its recording started with.
     */
    public static boolean autoCrop916(Context context) {
        return autoCrop916(context, null);
    }

    public static boolean autoCrop45(Context context) {
        return autoCrop45(context, null);
    }

    public static boolean autoCrop11(Context context) {
        return autoCrop11(context, null);
    }

    public static boolean autoCrop916(Context context, FeatureSnapshot features) {
        return appPrefs(context).getBoolean(KEY_AUTOCROP_9_16, true) && allows(features, Feature.CROP_9_16);
    }

    public static boolean autoCrop45(Context context, FeatureSnapshot features) {
        return appPrefs(context).getBoolean(KEY_AUTOCROP_4_5, false) && allows(features, Feature.CROP_4_5);
    }

    public static boolean autoCrop11(Context context, FeatureSnapshot features) {
        return appPrefs(context).getBoolean(KEY_AUTOCROP_1_1, false) && allows(features, Feature.CROP_1_1);
    }

    /** "Replace face cam background" (Settings), on by default; it also needs the bg_replace feature. */
    public static boolean bgReplaceEnabled(Context context, FeatureSnapshot features) {
        return context.getSharedPreferences(BG_PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BG_REPLACE_ENABLED, true) && allows(features, Feature.BG_REPLACE);
    }

    public static boolean showEditorAfterRecording(Context context) {
        return appPrefs(context).getBoolean(KEY_SHOW_EDITOR_AFTER_RECORDING, false);
    }

    /** The ratios to crop to, 9:16 first (the order of the Settings list). */
    public static List<int[]> autoCropRatios(Context context, FeatureSnapshot features) {
        return ratios(autoCrop916(context, features), autoCrop45(context, features),
                autoCrop11(context, features));
    }

    static List<int[]> ratios(boolean r916, boolean r45, boolean r11) {
        List<int[]> ratios = new ArrayList<>();
        if (r916) ratios.add(new int[]{9, 16});
        if (r45) ratios.add(new int[]{4, 5});
        if (r11) ratios.add(new int[]{1, 1});
        return ratios;
    }

    private static boolean allows(FeatureSnapshot features, String feature) {
        return features != null ? features.has(feature) : FeatureSnapshot.allows(feature);
    }

    private static SharedPreferences appPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

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
