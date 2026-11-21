package com.jovoc.facecampresentationrecorder.tracker;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.jovoc.facecampresentationrecorder.ui.StartupActivity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Records what the app does without any screen code: app open (with its source), foreground and
 * background, one span per screen, and the exit reasons of earlier process deaths. Every callback runs
 * on the main thread, so no locking is needed. Nothing here ever throws.
 */
public final class AutoTracker implements Application.ActivityLifecycleCallbacks {

    private final Context app;
    private final TrackerPrefs prefs;

    private int started;
    private boolean recreating;
    private boolean processFirst = true;
    private Tracked background;
    private Tracked rotatingSpan;
    private final Map<Activity, Tracked> screens = new WeakHashMap<>();

    /** Call after {@link ActivityTracker#init}. Records the background period that a killed process left open. */
    public AutoTracker(Context context) {
        Context c = context.getApplicationContext();
        app = c != null ? c : context;
        prefs = new TrackerPrefs(app);
        try {
            long since = prefs.backgroundSince();
            if (since > 0) {
                ActivityTracker.logAt("app_background", "minimized", since, Meta.of("process_ended", true));
                prefs.setBackgroundSince(0);
            }
        } catch (Throwable ignored) {
            // tracking must never stop the app from starting
        }
        TrackerStore.submit(() -> ExitReasons.recordNew(app, prefs));
    }

    @Override
    public void onActivityStarted(@NonNull Activity a) {
        try {
            if (recreating) {
                // A rotation is the same visit: the screen span moves to the recreated Activity.
                recreating = false;
                started++;
                if (rotatingSpan != null) screens.put(a, rotatingSpan);
                rotatingSpan = null;
                return;
            }
            ActivityTracker.Entry entry = ActivityTracker.takeEntry();
            if (started++ == 0) {
                Intent i = a.getIntent();
                String source = entry.source != null ? entry.source : AppOpenSource.of(false,
                        i == null ? null : i.getAction(),
                        i != null && i.hasCategory(Intent.CATEGORY_LAUNCHER),
                        i == null ? 0 : i.getFlags());
                if (processFirst) {
                    Meta m = Meta.of("first_run", !prefs.firstOpenDone(), "notif", entry.notif);
                    ActivityTracker.logWithSource("app_open", "cold start", source, m);
                    prefs.setFirstOpenDone();
                    processFirst = false;
                } else {
                    if (background != null) background.end();
                    background = null;
                    ActivityTracker.logWithSource("app_foreground", "came back", source,
                            Meta.of("notif", entry.notif));
                }
                prefs.setBackgroundSince(0);
            }
            // The launcher entry and the notification trampoline are not screens.
            if (!(a instanceof StartupActivity) && !(a instanceof NotificationEntryActivity)) {
                String simple = a.getClass().getSimpleName();
                screens.put(a, ActivityTracker.start("screen", simple));
                ActivityTracker.setScreen(simple);
            }
        } catch (Throwable ignored) {
            // silent
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity a) {
        try {
            started--;
            if (a.isChangingConfigurations()) {
                recreating = true;
                rotatingSpan = screens.remove(a);
                return;
            }
            Tracked s = screens.remove(a);
            if (s != null) s.end();
            if (started == 0) {
                background = ActivityTracker.start("app_background", "minimized");
                prefs.setBackgroundSince(System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
            // silent
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }
}
