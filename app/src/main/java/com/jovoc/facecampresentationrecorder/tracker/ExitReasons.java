package com.jovoc.facecampresentationrecorder.tracker;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Records how the app process ended since the last check (ANR, native crash, low memory, user
 * stop, ...). Runs on the tracker thread. Nothing here ever throws.
 */
public final class ExitReasons {

    private static final int TRACE_MAX_CHARS = 8000;
    private static final int HISTORY_MAX = 10;

    private ExitReasons() { }

    /** Stores every exit newer than the last check. The first run only sets the clock, so old history is not imported. */
    static void recordNew(Context c, TrackerPrefs prefs) {
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            if (am == null) return;
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, HISTORY_MAX);
            long last = prefs.lastExitCheck();
            if (last == 0) {
                prefs.setLastExitCheck(System.currentTimeMillis());
                return;
            }
            long newest = last;
            if (list == null) return;
            for (ApplicationExitInfo x : list) {
                if (x == null || x.getTimestamp() <= last) continue;
                newest = Math.max(newest, x.getTimestamp());
                store(x);
            }
            prefs.setLastExitCheck(newest);
        } catch (Throwable ignored) {
            // exit reasons are best effort
        }
    }

    private static void store(ApplicationExitInfo x) {
        String reason = ExitReasonNames.reasonName(x.getReason());
        Meta m = Meta.of("reason", reason, "status", x.getStatus(), "importance", x.getImportance(),
                "pss_kb", x.getPss(), "rss_kb", x.getRss(), "detail", x.getDescription());
        if (!ExitReasonNames.isError(reason)) {
            ActivityTracker.storeAt(TrackedRecord.KIND_ACTIVITY, "app_exit", reason, x.getTimestamp(), m, null);
            return;
        }
        boolean fatal = !"ANR".equals(reason);
        String stack = "ANR".equals(reason) ? readTrace(x) : null;
        ErrorInfo error = ErrorInfo.of(reason, x.getDescription(), stack, null, fatal, null);
        ActivityTracker.storeAt(TrackedRecord.KIND_ERROR, "app_exit", reason, x.getTimestamp(), m, error);
    }

    /** The first 8000 characters of the system's trace for this exit, or null when there is none. */
    private static String readTrace(ApplicationExitInfo x) {
        try (InputStream in = x.getTraceInputStream()) {
            if (in == null) return null;
            Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            char[] buf = new char[TRACE_MAX_CHARS];
            int off = 0;
            int n;
            while (off < buf.length && (n = r.read(buf, off, buf.length - off)) != -1) off += n;
            return new String(buf, 0, off);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
