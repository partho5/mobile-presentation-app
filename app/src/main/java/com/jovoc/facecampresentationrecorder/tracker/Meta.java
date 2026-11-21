package com.jovoc.facecampresentationrecorder.tracker;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small flat map of details attached to a record. Pure Java: no android.* classes.
 * Keys are cleaned, values are Boolean/Integer/Long/Double/Float or a String of at most 200
 * characters, and there are at most 20 keys.
 */
public final class Meta {

    static final int MAX_KEYS = 20;
    static final int MAX_VALUE = 200;

    final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

    /** Meta.of("count", 3, "on", true). Odd tail, null keys and null values are skipped. */
    public static Meta of(Object... keyValues) {
        Meta m = new Meta();
        if (keyValues != null) {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                if (keyValues[i] != null) m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return m;
    }

    public Meta put(String key, Object value) {
        try {
            String k = TrackedRecord.cleanKey(key);
            if (k == null || value == null) return this;
            if (values.size() >= MAX_KEYS && !values.containsKey(k)) return this;
            Object kept;
            if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
                kept = value;
            } else if (value instanceof Double || value instanceof Float) {
                double d = ((Number) value).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) return this;
                kept = value;
            } else {
                kept = TrackedRecord.cut(String.valueOf(value), MAX_VALUE);
                if (kept == null) return this;
            }
            values.put(k, kept);
        } catch (Throwable ignored) {
            // A detail that cannot be stored is dropped; the record itself is kept.
        }
        return this;
    }

    /** Copies every entry of {@code other} into this map. Null-safe. */
    public Meta merge(Meta other) {
        if (other != null) {
            for (Map.Entry<String, Object> e : other.values.entrySet()) put(e.getKey(), e.getValue());
        }
        return this;
    }

    boolean isEmpty() {
        return values.isEmpty();
    }

    /** The JSON object of this map, or null when empty. */
    JSONObject toJson() throws JSONException {
        if (values.isEmpty()) return null;
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Object> e : values.entrySet()) o.put(e.getKey(), e.getValue());
        return o;
    }
}
