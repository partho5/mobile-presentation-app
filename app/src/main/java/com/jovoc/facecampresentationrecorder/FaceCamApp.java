package com.jovoc.facecampresentationrecorder;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jovoc.facecampresentationrecorder.aiedit.flow.AiEditReceipts;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.EntitlementsRefresher;
import com.jovoc.facecampresentationrecorder.publish.CaptionUsage;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;
import com.jovoc.facecampresentationrecorder.tracker.AutoTracker;
import com.jovoc.facecampresentationrecorder.tracker.TrackerCrashHandler;
import com.jovoc.facecampresentationrecorder.tracker.TrackerScheduler;
import com.jovoc.facecampresentationrecorder.ui.BillingLink;
import com.jovoc.facecampresentationrecorder.ui.PlanNotice;

/**
 * Starts the paid-feature checks and the activity tracker (what users do in the app, see
 * {@link ActivityTracker} and {@link AutoTracker}) with its background upload schedule.
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
            // An install with no brand picked yet pins the server's default, and its unpinned jobs follow it.
            SessionManager.get(this).pinBrandInBackground(false);
        } catch (Exception e) {
            // Pinning must never stop the app from starting; the next authed call pins instead.
        }
        try {
            CaptionUsage.flush(this); // retries a transcript use / release that could not be sent
        } catch (Exception e) {
            // Settling transcripts must never stop the app from starting.
        }
        try {
            AiEditReceipts.flushAsync(this); // retries an AI edit render receipt that could not be sent
        } catch (Exception e) {
            // A receipt must never stop the app from starting.
        }
        try {
            // The activity tracker: silent, batched upload. Must never stop the app from starting.
            ActivityTracker.init(this);
            registerActivityLifecycleCallbacks(new AutoTracker(this));
            ActivityTracker.onStored(new TrackerScheduler(this));
            TrackerScheduler.schedulePeriodic(this);
        } catch (Throwable ignored) {
            // Tracking must never stop the app from starting.
        }
        try {
            registerActivityLifecycleCallbacks(new ScreenTracker());
        } catch (Exception e) {
            // The plan checks must never stop the app from starting.
        }
        try {
            // Wraps the default crash handler, so the tracker records the crash first and then the previous one runs.
            TrackerCrashHandler.install();
        } catch (Throwable ignored) {
            // Tracking must never stop the app from starting.
        }
    }

    /**
     * Foreground and background checks (plan refresh, billing, plan notice). Tracking of screens and
     * app open/background lives in {@link AutoTracker}. A rotation is not a foreground change, so it is skipped.
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
                EntitlementsRefresher.refreshIfDue();
                BillingLink.refreshIfPending(activity); // billing trip pending (maybe after a restart): fetch the plan again
                noticeDue = true;
            }
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            started--;
            if (activity.isChangingConfigurations()) {
                recreating = true;
            }
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
