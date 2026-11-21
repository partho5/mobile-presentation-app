package com.jovoc.facecampresentationrecorder.tracker;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** One activity or error record, and its JSON in the backend contract. Pure Java. */
public final class TrackedRecord {

    static final String KIND_ACTIVITY = "activity";
    static final String KIND_ERROR = "error";

    String id;
    long seq;
    String session;
    String kind = KIND_ACTIVITY;
    String name;
    String description;
    long startedAt;
    Long endedAt;
    int tz;
    int appBuild;
    String screen;
    String source;
    Meta meta;
    ErrorInfo error;

    /** Lower case, spaces and dashes become "_", other characters are removed, at most 40. Null when empty. */
    static String cleanName(String raw) {
        return clean(raw, 40);
    }

    /** Same rule as {@link #cleanName}, at most 32 characters. */
    static String cleanKey(String raw) {
        return clean(raw, 32);
    }

    /** Trims and cuts to {@code max}. Null when null or blank. */
    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String clean(String raw, int max) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("[^a-z0-9_]", "");
        if (s.length() > max) s = s.substring(0, max);
        return s.isEmpty() ? null : s;
    }

    /** The contract's activity object, or null when the record has no usable name or cannot be encoded. */
    String toJson() {
        try {
            String n = cleanName(name);
            if (n == null) return null;
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("seq", seq);
            o.put("session", session);
            o.put("kind", kind == null ? KIND_ACTIVITY : kind);
            o.put("name", n);
            putIfNotNull(o, "description", cut(description, 120));
            o.put("startedAt", startedAt);
            if (endedAt != null) o.put("endedAt", endedAt.longValue());
            o.put("tz", tz);
            o.put("appBuild", appBuild);
            putIfNotNull(o, "screen", cut(screen, 60));
            putIfNotNull(o, "source", cut(source, 40));
            if (meta != null) {
                JSONObject m = meta.toJson();
                if (m != null) o.put("meta", m);
            }
            if (error != null) o.put("error", error.toJson());
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void putIfNotNull(JSONObject o, String key, String value) throws JSONException {
        if (value != null) o.put(key, value);
    }
}
