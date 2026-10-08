package com.jovoc.facecampresentationrecorder.account;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One workspace the user belongs to (an entry of GET /user/organizations). */
public final class Workspace {

    public final String id;
    public final String name;
    /** SUPERADMIN, ADMIN or USER; empty when the server did not say. */
    public final String role;

    public Workspace(String id, String name, String role) {
        this.id = id;
        this.name = name;
        this.role = role;
    }

    /**
     * Parses the list. Entries without an id are skipped, and so are workspaces the user is
     * disabled in (the server already filters those; this is a second guard).
     */
    public static List<Workspace> parseList(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<Workspace> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            JSONArray users = o.optJSONArray("users");
            JSONObject membership = users == null ? null : users.optJSONObject(0);
            if (membership != null && membership.optBoolean("disabled", false)) continue;
            String name = o.optString("name", "");
            out.add(new Workspace(id, name.isEmpty() ? id : name,
                    membership == null ? "" : membership.optString("role", "")));
        }
        return out;
    }
}
