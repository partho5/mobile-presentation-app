package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/** Reads an item's parameters the way the effects share: the item's value, else the registry default, within the limit. */
final class Params {

    private Params() {}

    /** A numeric parameter: item value, else the registry default, else {@code fallback}; held to the registry limit. */
    static double number(PlanItem item, RegistryEntry entry, String key, double fallback) {
        double base = entry != null ? entry.defaultNumber(key, fallback) : fallback;
        double v = item.paramNumber(key, base);
        double[] limit = entry != null ? entry.paramLimits.get(key) : null;
        return limit != null ? Math.max(limit[0], Math.min(limit[1], v)) : v;
    }

    /** The ease name: item value, else the registry default, else {@code fallback}. */
    static String ease(PlanItem item, RegistryEntry entry, String fallback) {
        Object d = entry != null ? entry.defaultParams.get("ease") : null;
        return item.paramString("ease", d instanceof String ? (String) d : fallback);
    }

    static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
