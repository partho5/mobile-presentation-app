package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** User-facing name and one-line benefit of each gateable feature. */
final class FeatureLabels {

    /** Feature id to {name, benefit} string resources. */
    private static final Map<String, int[]> TEXT = new HashMap<>();

    static {
        TEXT.put(Feature.CROP_9_16, new int[]{R.string.feature_crop_9_16_name, R.string.feature_crop_9_16_benefit});
        TEXT.put(Feature.CROP_4_5, new int[]{R.string.feature_crop_4_5_name, R.string.feature_crop_4_5_benefit});
        TEXT.put(Feature.CROP_1_1, new int[]{R.string.feature_crop_1_1_name, R.string.feature_crop_1_1_benefit});
        TEXT.put(Feature.FLOATING_CAM, new int[]{R.string.feature_floating_cam_name, R.string.feature_floating_cam_benefit});
        TEXT.put(Feature.DRAWING_BOARD, new int[]{R.string.feature_drawing_board_name, R.string.feature_drawing_board_benefit});
        TEXT.put(Feature.SLIDE_INK, new int[]{R.string.feature_slide_ink_name, R.string.feature_slide_ink_benefit});
        TEXT.put(Feature.AUTO_PUBLISH, new int[]{R.string.feature_auto_publish_name, R.string.feature_auto_publish_benefit});
        TEXT.put(Feature.AI_CAPTIONS, new int[]{R.string.feature_ai_captions_name, R.string.feature_ai_captions_benefit});
        TEXT.put(Feature.BG_REPLACE, new int[]{R.string.feature_bg_replace_name, R.string.feature_bg_replace_benefit});
        TEXT.put(Feature.BG_CUSTOM, new int[]{R.string.feature_bg_custom_name, R.string.feature_bg_custom_benefit});
    }

    private FeatureLabels() {
    }

    static String name(Context c, String feature) {
        int[] res = TEXT.get(feature);
        return res != null ? c.getString(res[0]) : pretty(feature);
    }

    /** {@code tierName}: used by the generic line for a feature this app version has no text for. */
    static String benefit(Context c, String feature, String tierName) {
        int[] res = TEXT.get(feature);
        return res != null ? c.getString(res[1]) : c.getString(R.string.paywall_benefit_generic, tierName);
    }

    /** "bg_replace" to "Bg replace": better than the raw id for a feature added after this release. */
    static String pretty(String feature) {
        if (feature == null || feature.isEmpty()) return "";
        String spaced = feature.replace('_', ' ');
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }
}
