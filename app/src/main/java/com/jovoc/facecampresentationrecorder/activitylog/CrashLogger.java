package com.jovoc.facecampresentationrecorder.activitylog;

/**
 * Logs an uncaught exception as {@code app_crash} before the app dies, then lets the previous
 * handler (the system's) do what it always did. Only the exception class and the first stack frame
 * inside this app are recorded, never the message (it can hold user text or file names).
 */
public final class CrashLogger implements Thread.UncaughtExceptionHandler {

    private static final String APP_PACKAGE = "com.jovoc.facecampresentationrecorder";
    /** The write may take this long before the crash goes on; the process is about to end. */
    private static final long MAX_WAIT_MS = 500L;
    private static final int MAX_VALUE = 60;

    private final Thread.UncaughtExceptionHandler previous;

    private CrashLogger(Thread.UncaughtExceptionHandler previous) {
        this.previous = previous;
    }

    /** Puts this logger in front of the current default handler. */
    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler(new CrashLogger(Thread.getDefaultUncaughtExceptionHandler()));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            ActivityLog.logAndWait("app_crash", MAX_WAIT_MS, "type", typeOf(error), "where", whereOf(error));
        } catch (Throwable ignored) {
            // Nothing may get in the way of the crash being reported as it always was.
        }
        if (previous != null) previous.uncaughtException(thread, error);
    }

    /** The simple class name of the root cause, e.g. {@code NullPointerException}. */
    static String typeOf(Throwable error) {
        return cut(rootOf(error).getClass().getSimpleName());
    }

    /** {@code MainActivity.startRecordingFlow:1311}: the first frame in this app's own code, else the top frame. */
    static String whereOf(Throwable error) {
        StackTraceElement[] frames = rootOf(error).getStackTrace();
        if (frames == null || frames.length == 0) return "unknown";
        StackTraceElement pick = frames[0];
        for (StackTraceElement f : frames) {
            if (f.getClassName().startsWith(APP_PACKAGE)) {
                pick = f;
                break;
            }
        }
        String cls = pick.getClassName();
        cls = cls.substring(cls.lastIndexOf('.') + 1);
        return cut(cls + "." + pick.getMethodName() + ":" + pick.getLineNumber());
    }

    private static Throwable rootOf(Throwable error) {
        Throwable t = error;
        for (int i = 0; i < 10 && t.getCause() != null && t.getCause() != t; i++) t = t.getCause();
        return t;
    }

    private static String cut(String s) {
        return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE);
    }
}
