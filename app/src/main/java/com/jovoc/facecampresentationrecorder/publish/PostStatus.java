package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One post's state as GET /public/v1/posts/status reports it. */
public final class PostStatus {

    public enum State {
        QUEUE, PUBLISHED, ERROR, DRAFT, DELETED, UNKNOWN;

        /** True when the post will not change any more on its own. */
        public boolean isFinal() {
            return this == PUBLISHED || this == ERROR || this == DELETED;
        }

        static State parse(String s) {
            if (s == null) return UNKNOWN;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    public final String id;
    public final State state;
    /** Link to the live post; null when the server sent none. */
    public final String releaseUrl;
    /** The channel's error text (ERROR only); null otherwise. */
    public final String error;

    public PostStatus(String id, State state, String releaseUrl, String error) {
        this.id = id;
        this.state = state;
        this.releaseUrl = releaseUrl;
        this.error = error;
    }

    /** Parses the answer array; entries without an id are skipped, unknown states are UNKNOWN. */
    public static List<PostStatus> parseList(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<PostStatus> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            out.add(new PostStatus(id, State.parse(o.optString("state", null)),
                    nonEmpty(o, "releaseURL"), nonEmpty(o, "error")));
        }
        return out;
    }

    private static String nonEmpty(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String v = o.optString(key, "").trim();
        return v.isEmpty() ? null : v;
    }
}
