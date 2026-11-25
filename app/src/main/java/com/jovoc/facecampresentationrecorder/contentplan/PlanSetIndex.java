package com.jovoc.facecampresentationrecorder.contentplan;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Remembers, per plan video, what the app saved as its topic set (title, date, server version and a signature of the
 * saved slides), so a later fetch can tell an untouched set from one the user edited. Ids and titles only; the plan
 * texts themselves live in the slides table. Backed by {@link ContentPlanStore.Store}.
 */
public final class PlanSetIndex {

    private static final String PREFS = "content_plan_sets";
    private static final String PREFIX_SET = "set:";

    /** What the app saved for one plan video. {@code signature} is null for a set adopted from an older app version. */
    public static final class Entry {
        public final String dayId;
        public final String brandId;
        public final String title;
        public final String date;
        public final String kind;
        public final int version;
        public final String signature;
        public final long createdMs;

        public Entry(String dayId, String brandId, String title, String date, String kind, int version,
                     String signature, long createdMs) {
            this.dayId = dayId;
            this.brandId = brandId;
            this.title = title;
            this.date = date;
            this.kind = kind;
            this.version = version;
            this.signature = signature;
            this.createdMs = createdMs;
        }
    }

    private final ContentPlanStore.Store store;

    public PlanSetIndex(ContentPlanStore.Store store) {
        this.store = store;
    }

    public static PlanSetIndex get(Context context) {
        final SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new PlanSetIndex(new ContentPlanStore.Store() {
            @Override
            public String get(String key) {
                return prefs.getString(key, null);
            }

            @Override
            public void put(String key, String value) {
                prefs.edit().putString(key, value).apply();
            }

            @Override
            public void remove(String key) {
                prefs.edit().remove(key).apply();
            }

            @Override
            public Collection<String> keys() {
                return new ArrayList<>(prefs.getAll().keySet());
            }
        });
    }

    /** The entry of {@code dayId}, or null when absent or unreadable. */
    public synchronized Entry get(String dayId) {
        if (dayId == null) return null;
        return parse(dayId, store.get(PREFIX_SET + dayId));
    }

    public synchronized void put(Entry e) {
        try {
            JSONObject o = new JSONObject();
            o.put("brand", e.brandId == null ? JSONObject.NULL : e.brandId);
            o.put("title", e.title == null ? "" : e.title);
            o.put("date", e.date == null ? "" : e.date);
            o.put("kind", e.kind == null ? "" : e.kind);
            o.put("version", e.version);
            o.put("sig", e.signature == null ? JSONObject.NULL : e.signature);
            o.put("created", e.createdMs);
            store.put(PREFIX_SET + e.dayId, o.toString());
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public synchronized void remove(String dayId) {
        store.remove(PREFIX_SET + dayId);
    }

    /** Every readable entry, date ascending, then creation time. A malformed value is skipped. */
    public synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        for (String key : store.keys()) {
            if (!key.startsWith(PREFIX_SET)) continue;
            Entry e = parse(key.substring(PREFIX_SET.length()), store.get(key));
            if (e != null) out.add(e);
        }
        Collections.sort(out, (a, b) -> {
            int c = a.date.compareTo(b.date);
            return c != 0 ? c : Long.compare(a.createdMs, b.createdMs);
        });
        return out;
    }

    private static Entry parse(String dayId, String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            return new Entry(dayId,
                    o.isNull("brand") ? null : o.optString("brand", null),
                    o.optString("title", ""),
                    o.optString("date", ""),
                    o.optString("kind", ""),
                    o.optInt("version", 0),
                    o.isNull("sig") ? null : o.optString("sig", null),
                    o.optLong("created", 0));
        } catch (JSONException e) {
            return null;
        }
    }
}
