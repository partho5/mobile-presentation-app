package com.jovoc.facecampresentationrecorder;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLogScheduler;
import com.jovoc.facecampresentationrecorder.activitylog.CrashLogger;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.EntitlementsRefresher;
import com.jovoc.facecampresentationrecorder.publish.CaptionUsage;
import com.jovoc.facecampresentationrecorder.ui.BillingLink;
import com.jovoc.facecampresentationrecorder.ui.PlanNotice;
import com.jovoc.facecampresentationrecorder.ui.StartupActivity;

/**
 * Starts the paid-feature checks and the activity log (what users do in the app) with its
 * background upload schedule.
 */
public class FaceCamApp extends Application {

    private static boolean coldStart = true;

    /** True the first time it is asked in this process, i.e. for the launch that started the process. */
    public static synchronized boolean consumeColdStart() {
        boolean cold = coldStart;
        coldStart = false;
        return cold;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Entitlements.init(this);
            EntitlementsRefresher.init(this);
            BillingLink.init(this);
        } catch (Exception e) {
            // Plans must never stop the app from starting; every gate then reads as Free.
        }
        try {
            CaptionUsage.flush(this); // retries a transcript use / release that could not be sent
        } catch (Exception e) {
            // Settling transcripts must never stop the app from starting.
        }
        try {
            ActivityLog.init(this);
            ActivityLog.setListener(new ActivityLogScheduler(this));
            ActivityLogScheduler.schedulePeriodic(this);
            CrashLogger.install();
            registerActivityLifecycleCallbacks(new ScreenTracker());
        } catch (Exception e) {
            // Logging must never stop the app from starting.
        }
    }

    /**
     * Logs which screen the user is on and when the app comes to the foreground or goes away. A
     * rotation stops and restarts the screen; that is not a visit, so it is skipped.
     */
    private static final class ScreenTracker implements ActivityLifecycleCallbacks {

        private int started;
        private boolean recreating;
        /** The app just came to the foreground: the plan-ended notice is checked on the first real screen. */
        private boolean noticeDue;

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            if (recreating) {
                recreating = false;
                started++;
                return;
            }
            if (started++ == 0) {
                ActivityLog.appForeground();
                EntitlementsRefresher.refreshIfDue();
                BillingLink.refreshIfPending(activity); // billing trip pending (maybe after a restart): fetch the plan again
                noticeDue = true;
            }
            // The launcher entry only forwards to the first real screen.
            if (!(activity instanceof StartupActivity)) {
                ActivityLog.log("screen_view", "screen", activity.getClass().getSimpleName());
            }
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            started--;
            if (activity.isChangingConfigurations()) {
                recreating = true;
                return;
            }
            if (started == 0) ActivityLog.appBackground();
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            if (noticeDue && PlanNotice.canShowOn(activity)) {
                noticeDue = false;
                PlanNotice.showIfDue(activity);
            }
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
}
