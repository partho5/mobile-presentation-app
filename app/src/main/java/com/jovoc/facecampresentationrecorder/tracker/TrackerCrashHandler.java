package com.jovoc.facecampresentationrecorder.tracker;

/**
 * Records an uncaught exception before the previous handler runs. Installed after the old
 * CrashLogger, so this handler runs first and then calls the old one.
 */
public final class TrackerCrashHandler {

    private TrackerCrashHandler() { }

    /** Wraps the current default handler. Call once, after the other handlers are installed. */
    public static void install() {
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable e) {
                    try {
                        ActivityTracker.fatal(e, thread);
                    } catch (Throwable ignored) {
                        // the crash handler must never get in the way of the real crash
                    }
                    if (previous != null) previous.uncaughtException(thread, e);
                }
            });
        } catch (Throwable ignored) {
            // without the tracker the old handlers still run
        }
    }
}
