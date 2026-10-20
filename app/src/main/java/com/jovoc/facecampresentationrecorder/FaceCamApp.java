package com.jovoc.facecampresentationrecorder;

import android.app.Application;

import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLogScheduler;

/** Starts the activity log (what users do in the app) and its background upload schedule. */
public class FaceCamApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ActivityLog.init(this);
            ActivityLog.setListener(new ActivityLogScheduler(this));
            ActivityLogScheduler.schedulePeriodic(this);
        } catch (Exception e) {
            // Logging must never stop the app from starting.
        }
    }
}
