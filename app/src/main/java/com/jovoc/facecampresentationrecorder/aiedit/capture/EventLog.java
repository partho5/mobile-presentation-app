package com.jovoc.facecampresentationrecorder.aiedit.capture;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;

import java.io.File;

/**
 * The one door the recorder's screens call to log what happens while a recording runs (spec section 10),
 * on the recording's own clock. Outside a recording every call returns at once, so it costs nothing when
 * no AI edit is ever asked for. Calls are one-liners and carry no logic; this class does the screen maths.
 *
 * What is logged never goes to {@code ActivityLog} or a crash report: it is metadata about the user's
 * content and stays in {@link EventLogStore}'s private file.
 */
public final class EventLog {

    private static final Object LOCK = new Object();
    private static volatile EventLogBuilder builder;

    private EventLog() {}

    /** The screen recorder produced its first frame; {@code startElapsedMs} is its {@code elapsedRealtime} clock zero. */
    public static void begin(long startElapsedMs, int screenW, int screenH) {
        synchronized (LOCK) {
            builder = new EventLogBuilder(startElapsedMs, screenW, screenH);
        }
    }

    /**
     * The recording stopped: saves the log next to {@code master}'s name in private storage. A recording
     * that left no file (it failed) drops its log. Always leaves the logger idle.
     */
    public static void end(Context context, String masterPath) {
        end(context, masterPath == null ? null : new File(masterPath));
    }

    /** See {@link #end(Context, String)}. */
    public static void end(Context context, File master) {
        EventLogBuilder b;
        synchronized (LOCK) {
            b = builder;
            builder = null;
        }
        if (b == null || master == null || !master.isFile() || master.length() == 0) return;
        EventLogStore.save(EventLogStore.dir(context), master, b.finish(SystemClock.elapsedRealtime()));
    }

    /** A finger went down on {@code view} (board or slide ink) at view-local {@code x}, {@code y}. */
    public static void strokeStart(View view, float x, float y) {
        if (builder == null) return;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        synchronized (LOCK) {
            if (builder != null) builder.strokeStart(SystemClock.elapsedRealtime(), loc[0] + x, loc[1] + y);
        }
    }

    /** The finger moved to view-local {@code x}, {@code y}. */
    public static void strokeMove(View view, float x, float y) {
        if (builder == null) return;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        synchronized (LOCK) {
            if (builder != null) builder.strokeMove(loc[0] + x, loc[1] + y);
        }
    }

    /** The finger came up or the stroke was cancelled. */
    public static void strokeEnd() {
        if (builder == null) return;
        synchronized (LOCK) {
            if (builder != null) builder.strokeEnd(SystemClock.elapsedRealtime());
        }
    }

    /** A short activity over the whole of {@code view}: undo, redo, clear, board text. */
    public static void pulse(View view) {
        if (builder == null) return;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        synchronized (LOCK) {
            if (builder != null) {
                builder.pulse(SystemClock.elapsedRealtime(), loc[0], loc[1],
                        loc[0] + view.getWidth(), loc[1] + view.getHeight());
            }
        }
    }

    /** The drawing board slid in ({@code true}) or out; repeats are ignored. */
    public static void boardShown(boolean shown) {
        if (builder == null) return;
        synchronized (LOCK) {
            if (builder != null) builder.boardShown(SystemClock.elapsedRealtime(), shown);
        }
    }

    /** The shown slide changed. */
    public static void slideChanged() {
        if (builder == null) return;
        synchronized (LOCK) {
            if (builder != null) builder.slideChanged(SystemClock.elapsedRealtime());
        }
    }

    /** The facecam circle as fractions of the master frame: centre {@code cx}, {@code cy}, radius {@code r} of the width. */
    public static void facecam(double cx, double cy, double r) {
        if (builder == null) return;
        synchronized (LOCK) {
            if (builder != null) builder.facecam(SystemClock.elapsedRealtime(), cx, cy, r);
        }
    }

    /** The facecam circle as its box on screen in px (what the bubble tracker already measures); hidden adds nothing. */
    public static void facecamBox(boolean visible, int left, int top, int size) {
        if (builder == null) return;
        synchronized (LOCK) {
            if (builder != null) builder.facecamBox(SystemClock.elapsedRealtime(), visible, left, top, size);
        }
    }
}
