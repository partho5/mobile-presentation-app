package com.jovoc.facecampresentationrecorder.publish;

import android.content.Context;
import android.content.SharedPreferences;

import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleCache;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleEmbedStore;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tells the server when a transcript (AI caption) is used or thrown away, so its credits are held until then:
 * {@code use} when the subtitles are burned in (once per caption), {@code release} when the recording is deleted.
 * Silent: a call that cannot be made now stays in a small pending list and is retried on the next app start or the
 * next call. Never carries transcript words, only opaque ids.
 */
public final class CaptionUsage {

    private static final String PREFS = "caption_usage";
    private static final String KEY_PENDING = "pending";
    private static final String PREFIX_USED = "used:";
    private static final String USE = "use";
    private static final String RELEASE = "release";
    private static final char SEP = '|';

    private static final Object LOCK = new Object();
    private static boolean flushing;
    /**
     * Resolves the brand and stores the call off the caller's thread (often the main thread), so no screen waits
     * on the network. A call is only ever stored with the brand in use, never an empty one.
     */
    private static final ExecutorService BRAND_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "caption-usage-brand");
        t.setDaemon(true);
        return t;
    });

    private CaptionUsage() {}

    /** The subtitles of {@code video} were burned in: commit its caption once. No caption, nothing to do. */
    public static void useFor(Context context, File video) {
        try {
            Context app = context.getApplicationContext();
            // Read here, before the caller may change the subtitle record.
            String id = SubtitleEmbedStore.getCaptionId(app, video);
            if (id == null) return;
            BRAND_IO.execute(() -> record(app, USE, id));
        } catch (Exception ignored) {
            // credits are settled by the server anyway; never disturb the embed
        }
    }

    /**
     * {@code recording} is being deleted for good: give its unused caption back. Call before the subtitle record is
     * removed. Only a recording's master releases (a crop or edit shares the caption), and only when no crop of it
     * is left; a caption already used stays used.
     */
    public static void releaseFor(Context context, File recording) {
        try {
            Context app = context.getApplicationContext();
            if (recording == null || !isMasterAlone(recording)) return;
            // Read here, before the caller removes the subtitle record.
            String id = SubtitleEmbedStore.getCaptionId(app, recording);
            if (id == null) return;
            BRAND_IO.execute(() -> record(app, RELEASE, id));
        } catch (Exception ignored) {
            // the server frees an unused hold by itself after a day
        }
    }

    /** Sends every pending call once, off the main thread; what fails for a passing reason stays for later. */
    public static void flush(Context context) {
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (flushing || pending(prefs(app)).isEmpty()) return;
            flushing = true;
        }
        Thread t = new Thread(() -> {
            try {
                sendPending(app);
            } catch (Exception ignored) {
                // retried on the next start
            } finally {
                synchronized (LOCK) {
                    flushing = false;
                }
            }
        }, "caption-usage");
        t.setDaemon(true);
        t.start();
    }

    // ---- internals ----

    private static void sendPending(Context app) {
        SharedPreferences prefs = prefs(app);
        PublishApi api = new PublishApi(SessionManager.get(app).repository());
        Set<String> snapshot;
        synchronized (LOCK) {
            snapshot = pending(prefs);
        }
        for (String entry : snapshot) {
            String[] p = entry.split("\\|", 3);
            if (p.length < 3) {
                drop(prefs, entry);
                continue;
            }
            try {
                if (USE.equals(p[0])) api.useCaption(p[1], p[2]);
                else api.releaseCaption(p[1], p[2]);
                drop(prefs, entry);
            } catch (PublishException e) {
                if (retryable(e)) {
                    if (e.kind == PublishException.Kind.SIGNED_OUT || e.kind == PublishException.Kind.NETWORK) return;
                } else {
                    drop(prefs, entry); // the server's final answer (gone, not ours): nothing to retry
                }
            }
        }
    }

    private static boolean retryable(PublishException e) {
        switch (e.kind) {
            case NETWORK:
            case SIGNED_OUT:
            case RATE_LIMITED:
            case IN_PROGRESS:
            case SERVER:
                return true;
            default:
                return false;
        }
    }

    /** True for a master recording with no crop or edit of it left beside it. */
    private static boolean isMasterAlone(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (!SubtitleCache.stripCropSuffix(base).equals(base)) return false;
        File[] siblings = file.getAbsoluteFile().getParentFile() == null ? null
                : file.getAbsoluteFile().getParentFile().listFiles();
        if (siblings == null) return true;
        for (File s : siblings) {
            if (s.getName().startsWith(base + "_") && s.isFile()) return false;
        }
        return true;
    }

    /**
     * Stores one pending call in the brand in use, then sends it. Nothing is stored when no brand can be had now:
     * the call is not sent without one, and the next use or release of this recording asks again.
     */
    private static void record(Context app, String op, String id) {
        try {
            String brand = SessionManager.get(app).brandForNewJob();
            if (brand == null) return;
            SharedPreferences prefs = prefs(app);
            synchronized (LOCK) {
                if (prefs.getBoolean(PREFIX_USED + id, false)) return;
                if (USE.equals(op)) prefs.edit().putBoolean(PREFIX_USED + id, true).apply();
                add(prefs, op, id, brand);
            }
            flush(app);
        } catch (Exception ignored) {
            // credits are settled by the server anyway; never disturb the caller
        }
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> pending(SharedPreferences prefs) {
        return new HashSet<>(prefs.getStringSet(KEY_PENDING, new HashSet<>()));
    }

    private static void add(SharedPreferences prefs, String op, String id, String workspaceId) {
        Set<String> set = pending(prefs);
        set.add(op + SEP + id + SEP + workspaceId);
        prefs.edit().putStringSet(KEY_PENDING, set).apply();
    }

    private static void drop(SharedPreferences prefs, String entry) {
        synchronized (LOCK) {
            Set<String> set = pending(prefs);
            if (set.remove(entry)) prefs.edit().putStringSet(KEY_PENDING, set).apply();
        }
    }
}
