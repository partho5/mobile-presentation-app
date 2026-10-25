package com.jovoc.facecampresentationrecorder.edit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an {@link EditProject} into text and back, so an unsaved edit can survive the app being
 * killed. Reading is strict: anything that does not look right gives null, never a half project.
 */
public final class EditProjectJson {

    private EditProjectJson() {}

    public static String toJson(EditProject p) {
        try {
            JSONObject o = new JSONObject();
            o.put("master", p.getMasterPath());
            JSONArray keep = new JSONArray();
            for (EditProject.Range r : p.getKeep()) {
                keep.put(new JSONArray().put(r.startMs).put(r.endMs));
            }
            o.put("keep", keep);
            EditProject.AudioSettings a = p.getOriginal();
            o.put("volume", (double) a.volume);
            o.put("muted", a.muted);
            o.put("removed", a.removed);
            JSONArray added = new JSONArray();
            for (EditProject.AddedAudio t : p.getAdded()) {
                added.put(new JSONObject()
                        .put("uri", t.uri)
                        .put("in", t.sourceInMs)
                        .put("out", t.sourceOutMs)
                        .put("start", t.startOnTimelineMs)
                        .put("volume", (double) t.volume));
            }
            o.put("added", added);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The project in {@code json}, or null when it is missing, damaged or not valid. */
    public static EditProject fromJson(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            String master = o.getString("master");
            List<EditProject.Range> keep = new ArrayList<>();
            JSONArray ka = o.getJSONArray("keep");
            long lastEnd = 0;
            for (int i = 0; i < ka.length(); i++) {
                JSONArray pair = ka.getJSONArray(i);
                long s = pair.getLong(0);
                long e = pair.getLong(1);
                if (s < lastEnd || e < s) return null; // must be sorted and not overlap
                keep.add(new EditProject.Range(s, e));
                lastEnd = e;
            }
            EditProject.AudioSettings settings = new EditProject.AudioSettings(
                    (float) o.getDouble("volume"), o.getBoolean("muted"), o.getBoolean("removed"));
            List<EditProject.AddedAudio> added = new ArrayList<>();
            JSONArray aa = o.getJSONArray("added");
            for (int i = 0; i < aa.length(); i++) {
                JSONObject t = aa.getJSONObject(i);
                added.add(new EditProject.AddedAudio(t.getString("uri"), t.getLong("in"),
                        t.getLong("out"), t.getLong("start"), (float) t.getDouble("volume")));
            }
            return new EditProject(master, keep, settings, added);
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }
}
