package com.jovoc.facecampresentationrecorder.aiedit.capture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * What the app saw while one recording ran (spec section 10): the activity map and the facecam track,
 * on the recording's clock in whole milliseconds. Immutable and free of Android classes, so the merge
 * and remap maths is unit-testable. Fractions are of the master frame. The JSON it reads and writes is
 * the file in {@link EventLogStore}; its {@code activity_map} and {@code facecam_track} are the request
 * fields of the same names.
 */
public final class EventLogData {

    public static final String BRUSH = "brush";
    public static final String BOARD_SHOW = "board_show";
    public static final String BOARD_HIDE = "board_hide";
    public static final String SLIDE_CHANGE = "slide_change";
    public static final String SOURCE = "event_log";

    /** Strokes closer than this (gap in ms) are one span. */
    public static final long MERGE_GAP_MS = 400;

    private static final Comparator<Activity> BY_TIME = new Comparator<Activity>() {
        @Override
        public int compare(Activity a, Activity b) {
            return a.startMs != b.startMs ? Long.compare(a.startMs, b.startMs) : Long.compare(a.endMs, b.endMs);
        }
    };

    /** One activity entry; a marker has {@code startMs == endMs}. */
    public static final class Activity {
        public final String id;
        public final String type;
        public final long startMs;
        public final long endMs;
        public final double x;
        public final double y;
        public final double w;
        public final double h;
        public final double strength;

        public Activity(String id, String type, long startMs, long endMs,
                        double x, double y, double w, double h, double strength) {
            this.id = id;
            this.type = type;
            this.startMs = startMs;
            this.endMs = endMs;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.strength = strength;
        }

        /** True for a marker (slide change, board shown or hidden). */
        public boolean isMarker() {
            return !BRUSH.equals(type);
        }

        Activity with(String newId, long newStart, long newEnd) {
            return new Activity(newId, type, newStart, newEnd, x, y, w, h, strength);
        }

        JSONObject toJson() throws JSONException {
            JSONObject region = new JSONObject();
            region.put("x", round(x));
            region.put("y", round(y));
            region.put("w", round(w));
            region.put("h", round(h));
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("type", type);
            o.put("start_ms", startMs);
            o.put("end_ms", endMs);
            o.put("region", region);
            o.put("strength", round(strength));
            o.put("source", SOURCE);
            return o;
        }

        static Activity fromJson(JSONObject o) throws JSONException {
            JSONObject r = o.getJSONObject("region");
            return new Activity(o.getString("id"), o.getString("type"), o.getLong("start_ms"),
                    o.getLong("end_ms"), r.getDouble("x"), r.getDouble("y"), r.getDouble("w"),
                    r.getDouble("h"), o.getDouble("strength"));
        }
    }

    /** The facecam circle from {@code tMs} on: centre {@code cx}, {@code cy} and radius {@code r}, as fractions of the master (r of its width). */
    public static final class FacecamKey {
        public final long tMs;
        public final double cx;
        public final double cy;
        public final double r;

        public FacecamKey(long tMs, double cx, double cy, double r) {
            this.tMs = tMs;
            this.cx = cx;
            this.cy = cy;
            this.r = r;
        }

        FacecamKey at(long newT) {
            return new FacecamKey(newT, cx, cy, r);
        }

        /** True when the circle is where {@code o} has it, to well under a pixel. */
        boolean samePlace(FacecamKey o) {
            return Math.abs(cx - o.cx) < 0.001 && Math.abs(cy - o.cy) < 0.001
                    && Math.abs(r - o.r) < 0.001;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("t_ms", tMs);
            o.put("cx", round(cx));
            o.put("cy", round(cy));
            o.put("r", round(r));
            return o;
        }

        static FacecamKey fromJson(JSONObject o) throws JSONException {
            return new FacecamKey(o.getLong("t_ms"), o.getDouble("cx"), o.getDouble("cy"), o.getDouble("r"));
        }
    }

    public final String sessionId;
    public final int width;
    public final int height;
    public final long durationMs;
    public final List<Activity> activity;
    public final List<FacecamKey> facecam;

    public EventLogData(String sessionId, int width, int height, long durationMs,
                        List<Activity> activity, List<FacecamKey> facecam) {
        this.sessionId = sessionId;
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
        this.activity = Collections.unmodifiableList(new ArrayList<>(activity));
        this.facecam = Collections.unmodifiableList(new ArrayList<>(facecam));
    }

    /** The store file's content: {@code { sessionId, video, activity_map, facecam_track }}. */
    public JSONObject toJson() throws JSONException {
        JSONObject video = new JSONObject();
        video.put("width", width);
        video.put("height", height);
        video.put("duration_ms", durationMs);
        JSONObject o = new JSONObject();
        o.put("sessionId", sessionId);
        o.put("video", video);
        o.put("activity_map", activityMapJson());
        o.put("facecam_track", facecamTrackJson());
        return o;
    }

    /** The request's {@code activity_map}. */
    public JSONArray activityMapJson() throws JSONException {
        JSONArray a = new JSONArray();
        for (Activity e : activity) a.put(e.toJson());
        return a;
    }

    /** The request's {@code facecam_track}. */
    public JSONArray facecamTrackJson() throws JSONException {
        JSONArray a = new JSONArray();
        for (FacecamKey k : facecam) a.put(k.toJson());
        return a;
    }

    /** Reads what {@link #toJson} wrote; throws {@link JSONException} on any other shape. */
    public static EventLogData fromJson(JSONObject o) throws JSONException {
        JSONObject video = o.getJSONObject("video");
        List<Activity> activity = new ArrayList<>();
        JSONArray am = o.getJSONArray("activity_map");
        for (int i = 0; i < am.length(); i++) activity.add(Activity.fromJson(am.getJSONObject(i)));
        List<FacecamKey> facecam = new ArrayList<>();
        JSONArray ft = o.getJSONArray("facecam_track");
        for (int i = 0; i < ft.length(); i++) facecam.add(FacecamKey.fromJson(ft.getJSONObject(i)));
        return new EventLogData(o.getString("sessionId"), video.getInt("width"), video.getInt("height"),
                video.getLong("duration_ms"), activity, facecam);
    }

    /**
     * Brush entries closer than {@link #MERGE_GAP_MS} (or overlapping) become one: the union of the times
     * and of the regions, the larger strength. Markers pass through. The result is sorted by start and
     * numbered {@code a1, a2, ...}.
     */
    static List<Activity> mergeAndNumber(List<Activity> in) {
        List<Activity> sorted = new ArrayList<>(in);
        Collections.sort(sorted, BY_TIME);
        List<Activity> out = new ArrayList<>();
        Activity open = null;
        for (Activity a : sorted) {
            if (a.isMarker()) {
                out.add(a);
                continue;
            }
            if (open != null && a.startMs - open.endMs < MERGE_GAP_MS) {
                open = union(open, a);
            } else {
                if (open != null) out.add(open);
                open = a;
            }
        }
        if (open != null) out.add(open);
        Collections.sort(out, BY_TIME);
        List<Activity> numbered = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) numbered.add(out.get(i).with("a" + (i + 1), out.get(i).startMs, out.get(i).endMs));
        return numbered;
    }

    private static Activity union(Activity a, Activity b) {
        double x0 = Math.min(a.x, b.x);
        double y0 = Math.min(a.y, b.y);
        double x1 = Math.max(a.x + a.w, b.x + b.w);
        double y1 = Math.max(a.y + a.h, b.y + b.h);
        return new Activity(a.id, a.type, Math.min(a.startMs, b.startMs), Math.max(a.endMs, b.endMs),
                x0, y0, x1 - x0, y1 - y0, Math.max(a.strength, b.strength));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
