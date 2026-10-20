package com.jovoc.facecampresentationrecorder.activitylog;

import android.content.Context;
import android.util.Log;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.db.ActivityEvent;
import com.jovoc.facecampresentationrecorder.db.ActivityEventDao;
import com.jovoc.facecampresentationrecorder.db.AppDatabase;

import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Records what the user does in the app, for the backend's per-user timeline.
 *
 * <pre>ActivityLog.log("record_stop", "duration_ms", 92000, "source", "button");</pre>
 *
 * Details are optional key / value pairs (see {@link ActivityMeta} for what is kept). Logging never
 * throws and never blocks the caller: the event is queued to one background thread that writes it
 * to the local buffer, and any failure only costs that event. Before {@link #init} every call is a
 * no-op. Uploading is the job of {@link ActivityLogWorker}, not of this class.
 */
public final class ActivityLog {

    /** Told after an event was stored, with how many events are waiting now. */
    public interface Listener {
        void onLogged(int pending);
    }

    private static final String TAG = "ActivityLog";

    /** Most events kept on the device while they cannot be sent. */
    static final int BUFFER_CAP = 2000;
    /** Rows dropped at once when the buffer is full, so the marker is not logged on every event. */
    static final int TRIM_CHUNK = 100;
    /** Events waiting for the writer thread; further ones are dropped. */
    private static final int QUEUE_CAP = 500;

    private static volatile ActivityLog instance;
    private static volatile Listener listener;

    private final Context context;
    private final SessionTracker sessions =
            new SessionTracker(System::currentTimeMillis, ActivityLog::newSessionId);
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAP), r -> {
        Thread t = new Thread(r, "activity-log");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());

    private ActivityLog(Context context) {
        this.context = context;
    }

    /** Starts logging; call once from Application.onCreate. Safe to call again. */
    public static void init(Context context) {
        try {
            if (instance == null) {
                synchronized (ActivityLog.class) {
                    if (instance == null) instance = new ActivityLog(context.getApplicationContext());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "init failed", e);
        }
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    /**
     * Logs one action. {@code action} is {@code ^[a-z0-9_]{1,48}$}; {@code meta} alternates key and
     * value. Anything invalid is dropped silently.
     */
    public static void log(String action, Object... meta) {
        try {
            ActivityLog self = instance;
            if (self == null || !ActivityMeta.isValidAction(action)) return;
            self.enqueue(action, ActivityMeta.toJson(meta));
        } catch (Exception e) {
            // Logging must never affect the app.
        }
    }

    /** A screen of the app came to the foreground after the app was away (or for the first time). */
    public static void appForeground() {
        try {
            ActivityLog self = instance;
            if (self == null) return;
            self.sessions.onForeground();
            log("app_foreground");
        } catch (Exception e) {
            // Logging must never affect the app.
        }
    }

    /** The last screen of the app went away. */
    public static void appBackground() {
        try {
            ActivityLog self = instance;
            if (self == null) return;
            log("app_background");
            self.sessions.onBackground();
        } catch (Exception e) {
            // Logging must never affect the app.
        }
    }

    private void enqueue(String action, String metaJson) {
        final long now = System.currentTimeMillis();
        final int tz = TimeZone.getDefault().getOffset(now) / 60000;
        final String session = sessions.current();
        if (BuildConfig.DEBUG) Log.d(TAG, action + (metaJson == null ? "" : " " + metaJson));
        writer.execute(() -> store(now, tz, session, action, metaJson));
    }

    /** Runs on the writer thread. */
    private void store(long ts, int tz, String session, String action, String metaJson) {
        try {
            ActivityEventDao dao = AppDatabase.getInstance(context).activityEventDao();
            if (dao.count() >= BUFFER_CAP) {
                dao.deleteOldest(TRIM_CHUNK);
                dao.insert(event(ts, tz, session, "log_overflow", "{\"dropped\":" + TRIM_CHUNK + "}"));
            }
            dao.insert(event(ts, tz, session, action, metaJson));
            Listener l = listener;
            if (l != null) l.onLogged(dao.count());
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.w(TAG, "could not store " + action, e);
        }
    }

    private static ActivityEvent event(long ts, int tz, String session, String action, String metaJson) {
        ActivityEvent e = new ActivityEvent();
        e.ts = ts;
        e.tz = tz;
        e.session = session;
        e.action = action;
        e.metaJson = metaJson;
        return e;
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
