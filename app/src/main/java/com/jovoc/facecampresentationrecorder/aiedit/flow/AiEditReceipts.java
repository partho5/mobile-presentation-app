package com.jovoc.facecampresentationrecorder.aiedit.flow;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

import java.util.List;

/**
 * Sends the render receipts that wait in {@link AiEditStore} (see {@link RenderReceipt}). Every failure is silent:
 * a receipt that could not be sent stays for the next try (the next app start), a receipt the server will never
 * take is dropped, and none of it ever reaches the screen or holds a result back.
 */
public final class AiEditReceipts {

    private static final Object LOCK = new Object();

    private AiEditReceipts() {
    }

    /** Sends every waiting receipt on a background thread. */
    public static void flushAsync(Context context) {
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> flush(app), "ai-edit-receipts");
        t.setDaemon(true);
        t.start();
    }

    private static void flush(Context app) {
        try {
            AiEditStore store = AiEditStore.get(app);
            if (store.pendingReceipts().isEmpty() || !SessionManager.get(app).hasSession()) return;
            AuthRepository repo = SessionManager.get(app).repository();
            flush(store, new AiEditApi(repo), System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // A receipt is never worth a crash.
        }
    }

    /** Sends the waiting receipts through {@code api}, oldest first; stops at the first failure worth a retry. */
    static void flush(AiEditStore store, AiEditApi api, long nowMs) {
        synchronized (LOCK) {
            List<AiEditStore.PendingReceipt> pending = store.pendingReceipts();
            for (AiEditStore.PendingReceipt r : pending) {
                if (nowMs - r.createdMs > AiEditStore.RECEIPT_MAX_AGE_MS) {
                    store.removePendingReceipt(r.attemptId);
                    continue;
                }
                try {
                    api.reportRender(r.editId, r.body, r.workspaceId);
                    store.removePendingReceipt(r.attemptId);
                } catch (AiEditException e) {
                    if (isFinal(e)) {
                        store.removePendingReceipt(r.attemptId);
                    } else {
                        return;
                    }
                }
            }
        }
    }

    /** True when sending again can never work: the server refused it or no longer knows the edit. */
    private static boolean isFinal(AiEditException e) {
        return (AiEditException.REJECTED.equals(e.code) && !e.retryable)
                || AiEditException.EDIT_NOT_FOUND.equals(e.code);
    }
}
