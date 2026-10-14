package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A connected social channel (one entry of GET /public/v1/integrations). */
public final class Channel {

    public final String id;
    public final String name;
    /** Provider identifier, e.g. "youtube", "tiktok", "instagram". */
    public final String identifier;
    public final String picture;
    public final boolean disabled;

    public Channel(String id, String name, String identifier, String picture, boolean disabled) {
        this.id = id;
        this.name = name;
        this.identifier = identifier;
        this.picture = picture;
        this.disabled = disabled;
    }

    /** Parses the integrations list; entries without an id or identifier are skipped. */
    public static List<Channel> parseList(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<Channel> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String identifier = o.optString("identifier", "");
            if (id.isEmpty() || identifier.isEmpty()) continue;
            String picture = o.isNull("picture") ? null : o.optString("picture", null);
            out.add(new Channel(id, o.optString("name", identifier), identifier, picture,
                    o.optBoolean("disabled", false)));
        }
        return out;
    }

    /** The compact form kept on a publish job: [{id, identifier, name}]. */
    public static String toJson(List<Channel> channels) {
        JSONArray arr = new JSONArray();
        try {
            for (Channel c : channels) {
                arr.put(new JSONObject()
                        .put("id", c.id)
                        .put("identifier", c.identifier)
                        .put("name", c.name));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return arr.toString();
    }

    public static List<Channel> fromJson(String json) {
        if (json == null) return new ArrayList<>();
        try {
            List<Channel> list = parseList(json);
            return list;
        } catch (JSONException e) {
            return new ArrayList<>();
        }
    }
}
