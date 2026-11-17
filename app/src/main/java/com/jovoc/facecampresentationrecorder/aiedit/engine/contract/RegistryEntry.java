package com.jovoc.facecampresentationrecorder.aiedit.engine.contract;

import com.jovoc.facecampresentationrecorder.aiedit.engine.EngineJson;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * One effect of the registry (spec section 14): what it may target, how long it may last, its default
 * parameters and their limits. Mirrors the server's {@code RegistryEntry}.
 */
public final class RegistryEntry {
    public final String id;
    public final String layer;
    public final String target;
    public final List<String> allowedComponents;
    public final String minLevel;
    public final boolean contentSafe;
    public final String cost;
    public final int minDurationMs;
    public final int maxDurationMs;
    public final Map<String, Object> defaultParams;
    public final Map<String, double[]> paramLimits;
    public final int cooldownMs;
    public final int maxPerVideo;
    public final List<String> sound;
    public final boolean brandColorable;
    public final List<String> requires;

    private RegistryEntry(JSONObject o) throws JSONException {
        id = o.getString("id");
        layer = o.getString("layer");
        target = o.getString("target");
        allowedComponents = EngineJson.strings(o.getJSONArray("allowed_components"));
        minLevel = o.getString("min_level");
        contentSafe = o.getBoolean("content_safe");
        cost = o.getString("cost");
        minDurationMs = o.getInt("min_duration_ms");
        maxDurationMs = o.getInt("max_duration_ms");
        defaultParams = EngineJson.primitives(o.getJSONObject("default_params"));
        paramLimits = EngineJson.ranges(o.getJSONObject("param_limits"));
        cooldownMs = o.getInt("cooldown_ms");
        maxPerVideo = o.getInt("max_per_video");
        sound = EngineJson.strings(o.getJSONArray("sound"));
        brandColorable = o.getBoolean("brand_colorable");
        requires = EngineJson.strings(o.getJSONArray("requires"));
    }

    static RegistryEntry fromJson(JSONObject o) throws JSONException {
        return new RegistryEntry(o);
    }

    /** Whether a job at {@code level} may use this effect. */
    public boolean allowedAt(String level) {
        int have = EngineContract.levelIndex(level);
        int need = EngineContract.levelIndex(minLevel);
        return have >= 0 && need >= 0 && have >= need;
    }

    /** A default parameter as a number, or {@code fallback} when it is missing or not a number. */
    public double defaultNumber(String key, double fallback) {
        Object v = defaultParams.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    /** Whether {@code value} of parameter {@code key} is inside the registry's limit (no limit means any value). */
    public boolean withinLimit(String key, double value) {
        double[] r = paramLimits.get(key);
        return r == null || (value >= r[0] && value <= r[1]);
    }
}
