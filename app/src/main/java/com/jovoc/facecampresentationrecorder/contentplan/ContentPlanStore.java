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
 * Keeps, in app-private preferences, the last week plan of each brand (so the card works offline), the "recorded"
 * reports the server has not got yet, and the days this phone has seen recorded. Holds the server's week text as it
 * came; never logged.
 */
public final class ContentPlanStore {

    private static final String PREFS = "content_plan";
    private static final String PREFIX_WEEK = "week:";
    private static final String PREFIX_FETCHED = "fetched:";
    private static final String PREFIX_STAMP = "stamp:";
    private static final String PREFIX_PENDING = "pending:";
    private static final String PREFIX_RECORDED = "recorded:";
    /** A local "recorded" mark is kept this long, then pruned on the next save. */
    static final long RECORDED_KEEP_MS = 30L * 24 * 60 * 60 * 1000;

    /** A "recorded" report that has not reached the server yet: ids and a time only. */
    public static final class PendingRecorded {
        public final String dayId;
        public final String brandId;
        public final long createdMs;

        public PendingRecorded(String dayId, String brandId, long createdMs) {
            this.dayId = dayId;
            this.brandId = brandId;
            this.createdMs = createdMs;
        }
    }

    public interface Store {
        String get(String key);
        void put(String key, String value);
        void remove(String key);
        Collection<String> keys();
    }

    private final Store store;

    public ContentPlanStore(Store store) {
        this.store = store;
    }

    public static ContentPlanStore get(Context context) {
        final SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new ContentPlanStore(new Store() {
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

    private static String brandKey(String brandId) {
        return brandId == null ? "" : brandId;
    }

    // ---- the cached week, per brand ----

    public synchronized void saveWeek(String brandId, String json, long nowMs) {
        store.put(PREFIX_WEEK + brandKey(brandId), json);
        store.put(PREFIX_FETCHED + brandKey(brandId), Long.toString(nowMs));
    }

    /** The raw body of the last fetch for {@code brandId}, or null. */
    public synchronized String weekJson(String brandId) {
        return store.get(PREFIX_WEEK + brandKey(brandId));
    }

    /** When the week of {@code brandId} was last fetched, or 0. */
    public synchronized long fetchedAt(String brandId) {
        String v = store.get(PREFIX_FETCHED + brandKey(brandId));
        if (v == null) return 0;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Keeps the week stamp the cached week of {@code brandId} was fetched at. */
    public synchronized void saveWeekStamp(String brandId, String stamp) {
        store.put(PREFIX_STAMP + brandKey(brandId), stamp);
    }

    /** The stamp the cached week of {@code brandId} was fetched at, or null. */
    public synchronized String weekStamp(String brandId) {
        return store.get(PREFIX_STAMP + brandKey(brandId));
    }

    // ---- "recorded" reports waiting to be sent ----

    /** Keeps a report until the server has it. One per day: a repeat replaces the first. */
    public synchronized void savePendingRecorded(String dayId, String brandId, long nowMs) {
        try {
            JSONObject o = new JSONObject();
            o.put("day", dayId);
            o.put("brand", brandId == null ? JSONObject.NULL : brandId);
            o.put("created", nowMs);
            store.put(PREFIX_PENDING + dayId, o.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The reports not yet sent, oldest first. */
    public synchronized List<PendingRecorded> pendingRecorded() {
        List<PendingRecorded> out = new ArrayList<>();
        for (String key : store.keys()) {
            if (!key.startsWith(PREFIX_PENDING)) continue;
            String json = store.get(key);
            if (json == null) continue;
            try {
                JSONObject o = new JSONObject(json);
                out.add(new PendingRecorded(o.getString("day"),
                        o.isNull("brand") ? null : o.optString("brand", null), o.optLong("created", 0)));
            } catch (JSONException e) {
                store.remove(key);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.createdMs, b.createdMs));
        return out;
    }

    public synchronized void removePending(String dayId) {
        store.remove(PREFIX_PENDING + dayId);
    }

    // ---- days this phone has seen recorded ----

    /** Marks the day recorded on this phone now. */
    public void markRecordedLocally(String dayId) {
        markRecordedLocally(dayId, System.currentTimeMillis());
    }

    /** Marks the day recorded at {@code nowMs} and prunes the marks older than 30 days. */
    public synchronized void markRecordedLocally(String dayId, long nowMs) {
        for (String key : store.keys()) {
            if (!key.startsWith(PREFIX_RECORDED)) continue;
            String v = store.get(key);
            long at;
            try {
                at = v == null ? 0 : Long.parseLong(v);
            } catch (NumberFormatException e) {
                at = 0;
            }
            if (nowMs - at > RECORDED_KEEP_MS) store.remove(key);
        }
        store.put(PREFIX_RECORDED + dayId, Long.toString(nowMs));
    }

    public synchronized boolean recordedLocally(String dayId) {
        return store.get(PREFIX_RECORDED + dayId) != null;
    }
}
