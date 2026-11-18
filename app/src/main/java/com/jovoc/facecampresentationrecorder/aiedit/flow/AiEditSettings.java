package com.jovoc.facecampresentationrecorder.aiedit.flow;

import android.content.Context;
import android.content.SharedPreferences;

import com.jovoc.facecampresentationrecorder.util.RecordSettings;

/**
 * What the user chose for AI edits (the Settings section "AI edit"): the default level, the text colours and
 * whether sound effects are on. Read when a job starts; the job keeps the values it was started with.
 */
public final class AiEditSettings {

    public static final String KEY_LEVEL = "key_ai_edit_level";
    public static final String KEY_TEXT_COLORS = "key_ai_edit_text_colors";
    public static final String KEY_SOUND_EFFECTS = "key_ai_edit_sound_effects";

    public static final String LEVEL_BASIC = "basic";
    public static final String LEVEL_STANDARD = "standard";
    public static final String LEVEL_HEAVY = "heavy";
    public static final String COLORS_MULTI = "multi";
    public static final String COLORS_BRAND = "brand";

    private AiEditSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(RecordSettings.PREF_NAME, Context.MODE_PRIVATE);
    }

    /** The level a tap on "AI Edit" uses; Standard until the user picks another. */
    public static String level(Context context) {
        String level = prefs(context).getString(KEY_LEVEL, LEVEL_STANDARD);
        return isLevel(level) ? level : LEVEL_STANDARD;
    }

    public static void setLevel(Context context, String level) {
        if (isLevel(level)) prefs(context).edit().putString(KEY_LEVEL, level).apply();
    }

    /** {@link #COLORS_MULTI} (the default) or {@link #COLORS_BRAND}. */
    public static String textColors(Context context) {
        return COLORS_BRAND.equals(prefs(context).getString(KEY_TEXT_COLORS, COLORS_MULTI))
                ? COLORS_BRAND : COLORS_MULTI;
    }

    public static void setTextColors(Context context, String mode) {
        if (COLORS_MULTI.equals(mode) || COLORS_BRAND.equals(mode)) {
            prefs(context).edit().putString(KEY_TEXT_COLORS, mode).apply();
        }
    }

    /** Sound effects in the edit; on by default. */
    public static boolean soundEffects(Context context) {
        return prefs(context).getBoolean(KEY_SOUND_EFFECTS, true);
    }

    public static void setSoundEffects(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_SOUND_EFFECTS, on).apply();
    }

    public static boolean isLevel(String level) {
        return LEVEL_BASIC.equals(level) || LEVEL_STANDARD.equals(level) || LEVEL_HEAVY.equals(level);
    }
}
