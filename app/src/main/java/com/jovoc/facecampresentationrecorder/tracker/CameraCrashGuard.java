package com.jovoc.facecampresentationrecorder.tracker;

import android.os.Looper;
import android.util.Log;

/**
 * Some Android 15 devices (seen on Samsung) throw a SecurityException
 * ("Attempt to use camera from a different process than original client") on the framework's
 * camera2 worker thread while a capture session that is already closing tears down its streams.
 * Nothing in the app can catch it there, and it kills the app although the camera is being
 * released anyway; CameraX reopens the camera when it is needed again.
 *
 * This handler swallows only that exception, only off the main thread, and only when it was thrown
 * from inside android.hardware.camera2. Every other crash goes to the previous handler unchanged.
 */
public final class CameraCrashGuard {

    private static final String TAG = "CameraCrashGuard";
    private static final String MESSAGE = "different process than original client";

    private CameraCrashGuard() { }

    /** Wraps the current default handler. Install after {@link TrackerCrashHandler} so it runs first. */
    public static void install() {
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
                if (isCameraTeardownRace(thread, e)) {
                    Log.w(TAG, "Ignored camera2 teardown SecurityException on " + thread.getName(), e);
                    try {
                        ActivityTracker.log("error", "camera teardown race ignored");
                    } catch (Throwable ignored) {
                        // tracking must not turn this back into a crash
                    }
                    return;
                }
                if (previous != null) previous.uncaughtException(thread, e);
            });
        } catch (Throwable ignored) {
            // without the guard the old handlers still run
        }
    }

    static boolean isCameraTeardownRace(Thread thread, Throwable e) {
        if (!(e instanceof SecurityException)) return false;
        if (thread == Looper.getMainLooper().getThread()) return false;
        String msg = e.getMessage();
        if (msg == null || !msg.contains(MESSAGE)) return false;
        for (StackTraceElement el : e.getStackTrace()) {
            if (el.getClassName().startsWith("android.hardware.camera2.")) return true;
        }
        return false;
    }
}
