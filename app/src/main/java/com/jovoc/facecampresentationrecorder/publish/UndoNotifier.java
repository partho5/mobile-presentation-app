package com.jovoc.facecampresentationrecorder.publish;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.db.PublishJob;

/**
 * The "publishing soon" notification shown while an auto-publish waits out its undo window,
 * with a Cancel action ({@link PublishCancelReceiver}). It uses the id and channel of the
 * worker's own progress notification, so when the upload starts that one simply takes over.
 */
public final class UndoNotifier {

    /** Same channel the worker's foreground notification uses. */
    static final String CHANNEL_ID = "publish_upload";

    private UndoNotifier() {}

    /** Shared with {@code PublishWorker}'s foreground notification id. */
    public static int notificationId(long jobId) {
        return 4200 + (int) (jobId % 1000);
    }

    public static void show(Context context, PublishJob job, long delayMs, long now) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.publish_channel_name), NotificationManager.IMPORTANCE_LOW));
        }
        int id = notificationId(job.id);
        Intent cancel = new Intent(context, PublishCancelReceiver.class)
                .setAction(PublishCancelReceiver.ACTION_CANCEL)
                .putExtra(PublishCancelReceiver.EXTRA_JOB_ID, job.id);
        Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.undo_notif_title))
                .setContentText(undoText(context, job))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // Live countdown that ticks without the app updating the notification.
                .setWhen(now + delayMs)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                // Never left behind if the work is replaced or fails to start.
                .setTimeoutAfter(delayMs + 5000L)
                .addAction(0, context.getString(R.string.publish_notif_cancel),
                        PendingIntent.getBroadcast(context, id, cancel,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
        nm.notify(id, n);
    }

    /** "clip will be published unless you cancel", naming the job's brand when the person has several. */
    private static String undoText(Context context, PublishJob job) {
        String brand = PublishLabels.brandNameFor(context, job);
        return brand == null
                ? context.getString(R.string.undo_notif_text, job.fileName)
                : context.getString(R.string.undo_notif_text_brand, job.fileName, brand);
    }

    public static void cancel(Context context, long jobId) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(notificationId(jobId));
    }
}
