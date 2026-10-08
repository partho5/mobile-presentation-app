package com.jovoc.facecampresentationrecorder.account;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A signed-in device (an entry of GET /auth/sessions). */
public final class DeviceSession {

    public final String id;
    /** Name the device gave at sign-in, else the parsed user agent; may be empty. */
    public final String name;
    public final String userAgent;
    public final String ip;
    /** Epoch millis, or 0 when the server sent nothing readable. */
    public final long lastSeenAt;
    public final long createdAt;
    /** True for the device making the request. */
    public final boolean current;

    public DeviceSession(String id, String name, String userAgent, String ip, long createdAt,
                         long lastSeenAt, boolean current) {
        this.id = id;
        this.name = name;
        this.userAgent = userAgent;
        this.ip = ip;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
        this.current = current;
    }

    /** Parses the list in the order sent (newest activity first); entries without an id are skipped. */
    public static List<DeviceSession> parseList(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<DeviceSession> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            out.add(new DeviceSession(id, text(o, "name"), text(o, "userAgent"), text(o, "ip"),
                    time(o, "createdAt"), time(o, "lastSeenAt"), o.optBoolean("current", false)));
        }
        return out;
    }

    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }

    private static long time(JSONObject o, String key) {
        String v = text(o, key);
        if (v.isEmpty()) return 0L;
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
