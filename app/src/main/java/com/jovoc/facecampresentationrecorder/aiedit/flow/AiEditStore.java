package com.jovoc.facecampresentationrecorder.aiedit.flow;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Keeps one {@link AiEditJob} per recording and level in app-private preferences, so a job that was running when
 * the process died is picked up again and a repeated tap on "AI Edit" finds the job instead of making a second
 * one. The session id of a recording is made once and shared by its levels. Holds no transcript or plan text.
 */
public final class AiEditStore {

    private static final String PREFS = "ai_edit_jobs";
    private static final String PREFIX_JOB = "job:";
    private static final String PREFIX_SESSION = "session:";
    private static final String PREFIX_RECEIPT = "receipt:";
    /** The receipts kept for a retry: when more are waiting, the oldest are dropped. */
    static final int MAX_PENDING_RECEIPTS = 5;
    /** A receipt older than this is dropped unsent: the server no longer wants it. */
    static final long RECEIPT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    /** A render receipt that has not reached the server yet: codes, ids and numbers only. */
    public static final class PendingReceipt {
        public final String attemptId;
        public final String editId;
        public final String workspaceId;
        /** The receipt's JSON body. */
        public final String body;
        public final long createdMs;

        public PendingReceipt(String attemptId, String editId, String workspaceId, String body, long createdMs) {
            this.attemptId = attemptId;
            this.editId = editId;
            this.workspaceId = workspaceId;
            this.body = body;
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

    public AiEditStore(Store store) {
        this.store = store;
    }

    public static AiEditStore get(Context context) {
        final SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new AiEditStore(new Store() {
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

    private static String jobKey(String sourcePath, String level) {
        return PREFIX_JOB + sourcePath + "|" + level;
    }

    /** The job of {@code sourcePath} at {@code level}, or null when there is none (or it cannot be read). */
    public synchronized AiEditJob find(String sourcePath, String level) {
        String json = store.get(jobKey(sourcePath, level));
        if (json == null) return null;
        try {
            return AiEditJob.fromJson(new JSONObject(json));
        } catch (JSONException e) {
            store.remove(jobKey(sourcePath, level));
            return null;
        }
    }

    public synchronized void save(AiEditJob job, long nowMs) {
        job.updatedMs = nowMs;
        if (job.createdMs == 0) job.createdMs = nowMs;
        try {
            store.put(jobKey(job.sourcePath, job.level), job.toJson().toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every job that is not finished, oldest first: what a restarted service continues. */
    public synchronized List<AiEditJob> unfinished() {
        List<AiEditJob> out = new ArrayList<>();
        for (String key : store.keys()) {
            if (!key.startsWith(PREFIX_JOB)) continue;
            String json = store.get(key);
            if (json == null) continue;
            try {
                AiEditJob job = AiEditJob.fromJson(new JSONObject(json));
                if (job.isUnfinished() && !job.hasFailed()) out.add(job);
            } catch (JSONException e) {
                store.remove(key);
            }
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.createdMs, b.createdMs));
        return out;
    }

    /**
     * The session id of the recording at {@code sourcePath}: the one given (from its event log) when there is
     * one, else a new uuid. Made once, so the levels of one recording share it and the server tells a repeated
     * start from a new recording.
     */
    public synchronized String sessionFor(String sourcePath, String fromLog) {
        String key = PREFIX_SESSION + sourcePath;
        String known = store.get(key);
        if (known != null && !known.isEmpty()) return known;
        String id = fromLog != null && !fromLog.isEmpty() ? fromLog : UUID.randomUUID().toString();
        store.put(key, id);
        return id;
    }

    // ---- render receipts waiting to be sent ----

    /** Keeps a receipt until the server has it; past {@link #MAX_PENDING_RECEIPTS} the oldest are dropped. */
    public synchronized void savePendingReceipt(PendingReceipt r) {
        try {
            JSONObject o = new JSONObject();
            o.put("attempt", r.attemptId);
            o.put("edit", r.editId);
            o.put("workspace", r.workspaceId == null ? JSONObject.NULL : r.workspaceId);
            o.put("body", r.body);
            o.put("created", r.createdMs);
            store.put(PREFIX_RECEIPT + r.attemptId, o.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        List<PendingReceipt> all = pendingReceipts();
        for (int i = 0; i < all.size() - MAX_PENDING_RECEIPTS; i++) removePendingReceipt(all.get(i).attemptId);
    }

    /** The receipts not yet sent, oldest first. */
    public synchronized List<PendingReceipt> pendingReceipts() {
        List<PendingReceipt> out = new ArrayList<>();
        for (String key : store.keys()) {
            if (!key.startsWith(PREFIX_RECEIPT)) continue;
            String json = store.get(key);
            if (json == null) continue;
            try {
                JSONObject o = new JSONObject(json);
                out.add(new PendingReceipt(o.getString("attempt"), o.getString("edit"),
                        o.isNull("workspace") ? null : o.optString("workspace", null), o.getString("body"),
                        o.optLong("created", 0)));
            } catch (JSONException e) {
                store.remove(key);
            }
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.createdMs, b.createdMs));
        return out;
    }

    public synchronized void removePendingReceipt(String attemptId) {
        store.remove(PREFIX_RECEIPT + attemptId);
    }
}
