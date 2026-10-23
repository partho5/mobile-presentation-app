package com.jovoc.facecampresentationrecorder.entitlements;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The features that can sit behind a paid plan, and the plan order. The server decides which plan
 * each feature needs (and sends it in the catalogue); {@link #DEFAULT_LOWEST} is only what the app
 * assumes before the first token. Free features keep working offline and without an account.
 */
public final class Feature {

    public static final String CROP_9_16 = "crop_9_16";
    public static final String FLOATING_CAM = "floating_cam";
    public static final String DRAWING_BOARD = "drawing_board";
    public static final String SLIDE_INK = "slide_ink";
    public static final String AUTO_PUBLISH = "auto_publish";
    public static final String CROP_4_5 = "crop_4_5";
    public static final String CROP_1_1 = "crop_1_1";
    public static final String AI_CAPTIONS = "ai_captions";
    public static final String BG_REPLACE = "bg_replace";
    public static final String BG_CUSTOM = "bg_custom";

    public static final String PLAN_FREE = "free";
    public static final String PLAN_STARTER = "starter";
    public static final String PLAN_PRO = "pro";
    public static final String PLAN_AGENCY = "agency";

    /** Lowest to highest. A plan the server adds later is simply unknown here (rank -1). */
    public static final List<String> PLAN_ORDER = Collections.unmodifiableList(
            Arrays.asList(PLAN_FREE, PLAN_STARTER, PLAN_PRO, PLAN_AGENCY));

    /** Feature to the lowest plan that includes it; mirror of the backend's PRICING.features. */
    public static final Map<String, String> DEFAULT_LOWEST;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(CROP_9_16, PLAN_FREE);
        m.put(FLOATING_CAM, PLAN_FREE);
        m.put(DRAWING_BOARD, PLAN_FREE);
        m.put(SLIDE_INK, PLAN_FREE);
        m.put(AUTO_PUBLISH, PLAN_FREE);
        m.put(CROP_4_5, PLAN_STARTER);
        m.put(CROP_1_1, PLAN_STARTER);
        m.put(AI_CAPTIONS, PLAN_STARTER);
        m.put(BG_REPLACE, PLAN_PRO);
        m.put(BG_CUSTOM, PLAN_PRO);
        DEFAULT_LOWEST = Collections.unmodifiableMap(m);
    }

    private Feature() {
    }

    /** Position in {@link #PLAN_ORDER} (case does not matter), or -1 for a plan the app does not know. */
    public static int rank(String plan) {
        return plan == null ? -1 : PLAN_ORDER.indexOf(plan.toLowerCase(Locale.ROOT));
    }

    /** True when {@code plan} is at least {@code lowest}; false if either is unknown. */
    public static boolean planCovers(String plan, String lowest) {
        int have = rank(plan);
        int need = rank(lowest);
        return have >= 0 && need >= 0 && have >= need;
    }

    /** What a phone without a valid token may use: the features whose default lowest plan is Free. */
    public static boolean isFreeByDefault(String feature) {
        return PLAN_FREE.equals(DEFAULT_LOWEST.get(feature));
    }

    /** "pro" -> "Pro": the plan's name when the catalogue is not at hand. */
    public static String defaultName(String plan) {
        if (plan == null || plan.isEmpty()) return "Free";
        return Character.toUpperCase(plan.charAt(0)) + plan.substring(1);
    }
}
