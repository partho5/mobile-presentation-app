package com.jovoc.facecampresentationrecorder.publish;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.work.NetworkType;

import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import java.util.Set;

/**
 * "Upload on Wi-Fi only": which network the upload work needs, whether a queued job is blocked by
 * it (the card then says "Waiting for Wi-Fi"), and which work item of a chain is the upload.
 * The decisions are plain functions; the small Android readers at the bottom feed them.
 */
public final class PublishNetwork {

    private PublishNetwork() {}

    /** The WorkManager constraint for the upload: unmetered with the setting on, else any connection. */
    public static NetworkType requiredNetwork(boolean wifiOnly) {
        return wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED;
    }

    /**
     * True when a job is queued only because the network is metered (or absent) and the setting
     * wants Wi-Fi. Never inside the undo window (that card says "Publishing in N s"), never for a
     * job that is already running or has ended.
     */
    public static boolean waitingForWifi(PublishJob job, boolean wifiOnly, boolean onUnmetered, long now) {
        return job != null && wifiOnly && !onUnmetered
                && PublishJob.QUEUED.equals(job.status)
                && AutoPublishUndo.secondsLeft(job, now) == 0;
    }

    /**
     * True when a work item of a job's chain is the upload (not the undo gate): it carries the
     * upload tag, or - work enqueued before the tag existed - the worker's class name tag.
     */
    public static boolean isUploadWork(Set<String> tags, String uploadTag, String workerClassName) {
        return tags != null && (tags.contains(uploadTag) || tags.contains(workerClassName));
    }

    // ---- Android readers ----

    public static boolean isWifiOnly(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(RecordSettings.PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(RecordSettings.KEY_PUBLISH_WIFI_ONLY, false);
    }

    /** True when the default network is unmetered (what WorkManager's UNMETERED requires). */
    public static boolean isOnUnmetered(Context context) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Card helper: reads the setting and the network only when the job could be waiting at all. */
    public static boolean waitingForWifi(Context context, PublishJob job, long now) {
        if (job == null || !PublishJob.QUEUED.equals(job.status) || !isWifiOnly(context)) return false;
        return waitingForWifi(job, true, isOnUnmetered(context), now);
    }
}
