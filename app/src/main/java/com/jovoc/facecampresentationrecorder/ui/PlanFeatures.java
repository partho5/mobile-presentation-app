package com.jovoc.facecampresentationrecorder.ui;

import com.jovoc.facecampresentationrecorder.entitlements.Feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What the plans sheet lists: the tiers in order, and which features each tier adds. */
final class PlanFeatures {

    private PlanFeatures() {
    }

    /**
     * The plan keys to show, lowest first: Free, then the catalogue's plans in the server's order
     * (the app's own order before the first fetch). Free is added when the catalogue lacks it.
     */
    static List<String> planKeys(List<String> catalogueKeys) {
        if (catalogueKeys == null || catalogueKeys.isEmpty()) return Feature.PLAN_ORDER;
        List<String> out = new ArrayList<>();
        boolean hasFree = false;
        for (String key : catalogueKeys) {
            if (Feature.PLAN_FREE.equalsIgnoreCase(key)) hasFree = true;
        }
        if (!hasFree) out.add(Feature.PLAN_FREE);
        out.addAll(catalogueKeys);
        return out;
    }

    /** The features whose lowest plan is exactly {@code planKey}, in the map's order. */
    static List<String> featuresOf(String planKey, Map<String, String> lowestByFeature) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : lowestByFeature.entrySet()) {
            if (planKey.equalsIgnoreCase(e.getValue())) out.add(e.getKey());
        }
        return out;
    }
}
