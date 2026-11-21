package com.jovoc.facecampresentationrecorder.tracker;

import android.content.Context;

import androidx.work.WorkManager;

import com.jovoc.facecampresentationrecorder.BuildConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The static facade every screen and service calls. Every public method is silent: it never throws
 * and never waits. Records are built and written on the tracker thread (see {@link TrackerStore}).
 * Before {@link #init} every method does nothing and {@link #start} returns {@link Tracked#NOOP}.
 */
public final class ActivityTracker {

    private static final Object LOCK = new Object();
    /** Serialises sequence and session stamping, which both use the shared prefs. */
    private static final Object STAMP_LOCK = new Object();
    private static final String DEFAULT_LAST = "";

    private static volatile boolean ready;
    private static volatile Context app;
    private static volatile String currentScreen;
    private static volatile String lastActivity = DEFAULT_LAST;
    private static volatile String entrySource;
    private static volatile String entryNotif;
    private static final Set<Tracked> OPEN = Collections.newSetFromMap(new ConcurrentHashMap<Tracked, Boolean>());

    private ActivityTracker() { }

    /** Once per process; safe to call again. */
    public static void init(Context context) {
        try {
            synchronized (LOCK) {
                if (ready || context == null) return;
                Context c = context.getApplicationContext();
                app = c != null ? c : context;
                ready = true;
            }
            TrackerStore.submit(() -> removeLeftovers(app));
        } catch (Throwable ignored) {
            // tracking stays off
        }
    }

    /** Work names and the prefs file of the activity log this tracker replaced. */
    private static final String[] OLD_WORK = {"activity-log-periodic", "activity-log-count", "activity-log-drain"};
    private static final String OLD_PREFS = "activity_log_prefs";

    /**
     * Removes what the replaced activity log left behind. Otherwise WorkManager keeps retrying the deleted
     * worker class. Runs on the tracker thread, once per process; each step is best effort.
     */
    private static void removeLeftovers(Context c) {
        for (String name : OLD_WORK) {
            try {
                WorkManager.getInstance(c).cancelUniqueWork(name);
            } catch (Throwable ignored) {
                // best effort
            }
        }
        try {
            c.deleteSharedPreferences(OLD_PREFS);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    public static void log(String name) {
        try {
            log(name, null, null);
        } catch (Throwable ignored) {
            // silent
        }
    }

    public static void log(String name, String description) {
        try {
            log(name, description, null);
        } catch (Throwable ignored) {
            // silent
        }
    }

    public static void log(String name, String description, Meta meta) {
        try {
            if (!ready) return;
            String n = TrackedRecord.cleanName(name);
            if (n == null) return;
            long now = System.currentTimeMillis();
            String desc = TrackedRecord.cut(description, 120);
            TrackedRecord r = draft(TrackedRecord.KIND_ACTIVITY, n, desc, now, null, copy(meta), currentScreen, null);
            remember(n, desc);
            emit(r, now, null);
        } catch (Throwable ignored) {
            // silent
        }
    }

    /** Opens a span. Ends it with {@link Tracked#end()}. Never null. */
    public static Tracked start(String name, String description) {
        try {
            return start(name, description, null);
        } catch (Throwable ignored) {
            return Tracked.NOOP;
        }
    }

    public static Tracked start(String name, String description, Meta meta) {
        try {
            if (!ready) return Tracked.NOOP;
            String n = TrackedRecord.cleanName(name);
            if (n == null) return Tracked.NOOP;
            Tracked t = new Tracked(n, TrackedRecord.cut(description, 120), System.currentTimeMillis(),
                    copy(meta), currentScreen);
            OPEN.add(t);
            return t;
        } catch (Throwable ignored) {
            return Tracked.NOOP;
        }
    }

    public static void error(String name, String description, Throwable t) {
        try {
            error(name, description, t, null);
        } catch (Throwable ignored) {
            // silent
        }
    }

    public static void error(String name, String description, Throwable t, Meta meta) {
        try {
            if (!ready) return;
            String n = TrackedRecord.cleanName(name);
            if (n == null) return;
            long now = System.currentTimeMillis();
            String desc = TrackedRecord.cut(description, 120);
            // The error's lastActivity is the record before it, not the error itself.
            String previous = lastActivity;
            TrackedRecord r = draft(TrackedRecord.KIND_ERROR, n, desc, now, null, copy(meta), currentScreen, null);
            r.error = ErrorInfo.from(t, false, Thread.currentThread().getName(), previous);
            remember(n, desc);
            emit(r, now, null);
        } catch (Throwable ignored) {
            // silent
        }
    }

    /**
     * Crash handler (A3): stores the crash, and ends every open span with {@code ended_by=crash}.
     * Waits at most {@link TrackerConfig#CRASH_WAIT_MS}.
     */
    static void fatal(Throwable t, Thread thread) {
        try {
            if (!ready) return;
            Context c = app;
            long now = System.currentTimeMillis();
            String threadName = thread == null ? null : thread.getName();
            String last = lastActivity;

            List<TrackedRecord> out = new ArrayList<>();
            for (Tracked s : OPEN) {
                if (!s.claim()) continue;
                OPEN.remove(s);
                Meta m = copy(s.meta);
                if (m == null) m = new Meta();
                m.put("ended_by", "crash");
                out.add(draft(TrackedRecord.KIND_ACTIVITY, s.name, s.description, s.startedAt, now, m,
                        s.screen, null));
            }
            TrackedRecord crash = draft(TrackedRecord.KIND_ERROR, "crash", "uncaught", now, null, null,
                    currentScreen, null);
            crash.error = ErrorInfo.from(t, true, threadName, last);
            out.add(crash);

            for (TrackedRecord r : out) stamp(c, r, now);
            TrackerStore.writeAndWait(c, out, TrackerConfig.CRASH_WAIT_MS);
        } catch (Throwable ignored) {
            // the crash handler must never get in the way of the real crash
        }
    }

    /** The current screen, stamped on every record after this call. */
    static void setScreen(String screen) {
        try {
            if (!ready) return;
            currentScreen = TrackedRecord.cut(screen, 60);
        } catch (Throwable ignored) {
            // silent
        }
    }

    /** The source of the next app_open or app_foreground (A3 reads it). */
    static void setEntry(String source, String notif) {
        try {
            if (!ready) return;
            entrySource = TrackedRecord.cut(source, 40);
            entryNotif = TrackedRecord.cut(notif, 60);
        } catch (Throwable ignored) {
            // silent
        }
    }

    /** The entry set by {@link #setEntry}, handed out once. Both fields are null when nothing was set. */
    static final class Entry {
        final String source;
        final String notif;

        Entry(String source, String notif) {
            this.source = source;
            this.notif = notif;
        }
    }

    /** Returns the pending entry and clears it, so a stale entry never reaches a later open. Never null. */
    static Entry takeEntry() {
        try {
            Entry e = new Entry(entrySource, entryNotif);
            entrySource = null;
            entryNotif = null;
            return e;
        } catch (Throwable ignored) {
            return new Entry(null, null);
        }
    }

    /** An instant activity record with a source (app_open, app_foreground). */
    static void logWithSource(String name, String description, String source, Meta meta) {
        try {
            if (!ready) return;
            String n = TrackedRecord.cleanName(name);
            if (n == null) return;
            long now = System.currentTimeMillis();
            String desc = TrackedRecord.cut(description, 120);
            TrackedRecord r = draft(TrackedRecord.KIND_ACTIVITY, n, desc, now, null, copy(meta), currentScreen,
                    TrackedRecord.cut(source, 40));
            remember(n, desc);
            emit(r, now, null);
        } catch (Throwable ignored) {
            // silent
        }
    }

    /**
     * An instant activity record stamped with an earlier time (a background period that ended with the
     * process). The session is taken from now, so an old time never moves the session clock back.
     */
    static void logAt(String name, String description, long startedAt, Meta meta) {
        try {
            storeAt(TrackedRecord.KIND_ACTIVITY, name, description, startedAt, meta, null);
        } catch (Throwable ignored) {
            // silent
        }
    }

    /** Stores a record with an earlier start time, optionally with an error block (A3 exit reasons). */
    static void storeAt(String kind, String name, String description, long startedAt, Meta meta, ErrorInfo error) {
        try {
            if (!ready) return;
            String n = TrackedRecord.cleanName(name);
            if (n == null) return;
            long now = System.currentTimeMillis();
            TrackedRecord r = draft(kind, n, TrackedRecord.cut(description, 120), startedAt, null, copy(meta),
                    null, null);
            r.error = error;
            emit(r, now, null);
        } catch (Throwable ignored) {
            // silent
        }
    }

    public static void onStored(TrackerStore.Listener l) {
        try {
            TrackerStore.listener = l;
        } catch (Throwable ignored) {
            // silent
        }
    }

    /** Called by a span's {@link Tracked#end}. Only the first call stores anything. */
    static void endSpan(Tracked t, Meta extra) {
        try {
            if (t == null || !t.claim()) return;
            OPEN.remove(t);
            if (!ready) return;
            long now = System.currentTimeMillis();
            Meta m = copy(t.meta);
            if (extra != null) {
                if (m == null) m = new Meta();
                m.merge(extra);
            }
            TrackedRecord r = draft(TrackedRecord.KIND_ACTIVITY, t.name, t.description, t.startedAt, now, m,
                    t.screen, null);
            remember(t.name, t.description);
            emit(r, now, null);
        } catch (Throwable ignored) {
            // silent
        }
    }

    /** The overflow marker written when the buffer was trimmed. Runs on the tracker thread. */
    static TrackedRecord overflowRecord(Context c, int dropped) {
        long now = System.currentTimeMillis();
        TrackedRecord r = draft(TrackedRecord.KIND_ACTIVITY, "tracker_overflow", null, now, null,
                Meta.of("dropped", dropped), null, null);
        stamp(c, r, now);
        return r;
    }

    /** A record with everything except sequence and session, which {@link #stamp} sets later. */
    private static TrackedRecord draft(String kind, String name, String description, long startedAt, Long endedAt,
                                       Meta meta, String screen, String source) {
        TrackedRecord r = new TrackedRecord();
        r.id = UUID.randomUUID().toString();
        r.kind = kind;
        r.name = name;
        r.description = description;
        r.startedAt = startedAt;
        r.endedAt = endedAt;
        r.tz = TimeZone.getDefault().getOffset(startedAt) / 60000;
        r.appBuild = BuildConfig.VERSION_CODE;
        r.screen = screen;
        r.source = source;
        r.meta = meta;
        return r;
    }

    /** Sets the sequence number and the session. Runs on the tracker thread, or on the crash thread for {@link #fatal}. */
    private static void stamp(Context c, TrackedRecord r, long at) {
        synchronized (STAMP_LOCK) {
            TrackerPrefs prefs = new TrackerPrefs(c);
            r.seq = prefs.reserveSeq();
            r.session = new SessionClock(prefs, SessionClock::newId).current(at);
        }
    }

    /** Hands a finished record to the tracker thread, which stamps and stores it. */
    private static void emit(final TrackedRecord r, final long at, final Runnable after) {
        final Context c = app;
        if (c == null) return;
        TrackerStore.submit(() -> {
            stamp(c, r, at);
            TrackerStore.storeOn(c, r, after);
        });
    }

    /** Remembers the last record for crash reports. Screen spans are not "what the user did", so they are skipped. */
    private static void remember(String name, String description) {
        if ("screen".equals(name)) return;
        lastActivity = name + (description == null ? "" : " — " + description);
    }

    private static Meta copy(Meta meta) {
        return meta == null ? null : new Meta().merge(meta);
    }
}
