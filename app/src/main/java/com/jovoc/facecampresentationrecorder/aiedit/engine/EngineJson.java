package com.jovoc.facecampresentationrecorder.aiedit.engine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small org.json readers shared by the contract and plan parsers. Every method throws
 * {@link JSONException} when the shape is wrong, so a caller wraps one try/catch around a whole read.
 */
public final class EngineJson {

    private EngineJson() {}

    /** The strings of an array. */
    public static List<String> strings(JSONArray a) throws JSONException {
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return Collections.unmodifiableList(out);
    }

    /** The numbers of an array. */
    public static double[] doubles(JSONArray a) throws JSONException {
        double[] out = new double[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.getDouble(i);
        return out;
    }

    /** The strings of {@code key}, or an empty list when the key is missing. */
    public static List<String> optStrings(JSONObject o, String key) throws JSONException {
        return o.has(key) && !o.isNull(key) ? strings(o.getJSONArray(key)) : Collections.<String>emptyList();
    }

    /** The string of {@code key}, or null when it is missing or JSON null. */
    public static String optString(JSONObject o, String key) {
        return o.has(key) && !o.isNull(key) ? o.optString(key, null) : null;
    }

    /** An object of numbers, booleans and strings as a map (whole numbers as Long, others as Double). */
    public static Map<String, Object> primitives(JSONObject o) throws JSONException {
        Map<String, Object> out = new LinkedHashMap<>();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = o.get(k);
            if (v instanceof Boolean || v instanceof String) {
                out.put(k, v);
            } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                out.put(k, ((Number) v).longValue());
            } else if (v instanceof Number) {
                // Double on Android; BigDecimal in the JVM org.json that the unit tests use.
                out.put(k, ((Number) v).doubleValue());
            } else {
                throw new JSONException("not a primitive: " + k);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** An object whose values are number pairs, such as a registry's {@code param_limits}. */
    public static Map<String, double[]> ranges(JSONObject o) throws JSONException {
        Map<String, double[]> out = new LinkedHashMap<>();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            double[] r = doubles(o.getJSONArray(k));
            if (r.length != 2) throw new JSONException("range needs two numbers: " + k);
            out.put(k, r);
        }
        return Collections.unmodifiableMap(out);
    }
}
