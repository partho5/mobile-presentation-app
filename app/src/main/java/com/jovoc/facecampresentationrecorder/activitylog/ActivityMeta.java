package com.jovoc.facecampresentationrecorder.activitylog;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Turns the details of a logged action into the flat JSON the backend accepts, and drops anything
 * it would refuse: at most 10 keys, plain keys, boolean / number / short-string values, 512 bytes
 * in all. Keys that sound like credentials or addresses are never kept.
 */
final class ActivityMeta {

    static final int MAX_KEYS = 10;
    static final int MAX_STRING = 64;
    static final int MAX_BYTES = 512;

    private static final Pattern ACTION = Pattern.compile("^[a-z0-9_]{1,48}$");
    private static final Pattern KEY = Pattern.compile("^[a-z0-9_]{1,24}$");
    private static final Pattern SENSITIVE =
            Pattern.compile("email|token|password|authorization|secret", Pattern.CASE_INSENSITIVE);

    private ActivityMeta() {
    }

    static boolean isValidAction(String action) {
        return action != null && ACTION.matcher(action).matches();
    }

    /**
     * JSON object text for the key / value pairs ({@code "duration_ms", 92000, "source", "button"}),
     * or null when nothing valid is left. A trailing key without a value is ignored.
     */
    static String toJson(Object[] pairs) {
        if (pairs == null || pairs.length < 2) return null;
        JSONObject out = new JSONObject();
        int kept = 0;
        try {
            for (int i = 0; i + 1 < pairs.length && kept < MAX_KEYS; i += 2) {
                if (!(pairs[i] instanceof String)) continue;
                String key = (String) pairs[i];
                if (!KEY.matcher(key).matches() || SENSITIVE.matcher(key).find()) continue;
                Object value = pairs[i + 1];
                if (isAllowed(value)) {
                    out.put(key, value);
                    kept++;
                }
            }
        } catch (JSONException e) {
            return null;
        }
        if (kept == 0) return null;
        String json = out.toString();
        return json.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES ? json : null;
    }

    private static boolean isAllowed(Object value) {
        if (value instanceof Boolean) return true;
        if (value instanceof String) return ((String) value).length() <= MAX_STRING;
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return !Double.isNaN(d) && !Double.isInfinite(d);
        }
        return false;
    }
}
