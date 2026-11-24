package com.jovoc.facecampresentationrecorder.contentplan;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

import java.util.List;

/**
 * Sends the "recorded" reports that wait in {@link ContentPlanStore}. Every failure is silent: a report that could not
 * be sent stays for the next try (the next app start), a report the server will never take is dropped, and none of
 * it ever reaches the screen.
 */
public final class ContentPlanReports {

    /** A report older than this is dropped unsent. */
    static final long MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000;

    private static final Object LOCK = new Object();

    private ContentPlanReports() {
    }

    /** Sends every waiting report on a background thread. */
    public static void flushAsync(Context context) {
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> flush(app), "content-plan-reports");
        t.setDaemon(true);
        t.start();
    }

    private static void flush(Context app) {
        try {
            ContentPlanStore store = ContentPlanStore.get(app);
            if (store.pendingRecorded().isEmpty() || !SessionManager.get(app).hasSession()) return;
            AuthRepository repo = SessionManager.get(app).repository();
            flush(store, new ContentPlanApi(repo), System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // A report is never worth a crash.
        }
    }

    /**
     * Sends the waiting reports through {@code api}, oldest first. A success or a day the server does not know
     * (or a refusal a retry cannot fix) removes the report; any other failure keeps it and stops, so the next start tries again.
     */
    static void flush(ContentPlanStore store, ContentPlanApi api, long nowMs) {
        synchronized (LOCK) {
            List<ContentPlanStore.PendingRecorded> pending = store.pendingRecorded();
            for (ContentPlanStore.PendingRecorded p : pending) {
                if (nowMs - p.createdMs > MAX_AGE_MS) {
                    store.removePending(p.dayId);
                    continue;
                }
                try {
                    api.recorded(p.dayId, p.brandId);
                    store.removePending(p.dayId);
                } catch (ContentPlanException e) {
                    if (ContentPlanException.NOT_FOUND.equals(e.code) || ContentPlanException.REJECTED.equals(e.code)) {
                        store.removePending(p.dayId);
                    } else {
                        return;
                    }
                }
            }
        }
    }
}
