package com.jovoc.facecampresentationrecorder.publish;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.app.NotificationCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.SavedRecordingsActivity;
import com.jovoc.facecampresentationrecorder.db.PublishJob;
import com.jovoc.facecampresentationrecorder.ui.WebLinks;

/**
 * The notification an auto-publish job ends with, since nobody is looking at the screen:
 * "Published to N channels" / "Scheduled for ..." / "Auto-publish failed: reason". Tapping it
 * opens Saved Recordings.
 */
public final class AutoPublishNotifier {

    private static final String CHANNEL_ID = "auto_publish_result";
    private static final int ID_BASE = 5200;

    private AutoPublishNotifier() {}

    /** The one line shown for a finished (DONE or FAILED) auto-publish job. */
    public static String message(Context c, PublishJob job) {
        if (PublishJob.DONE.equals(job.status)) {
            if (PublishJob.MODE_AUTO_SLOT.equals(job.mode)) {
                return c.getString(R.string.auto_notif_scheduled);
            }
            int n = countIds(job.postIds);
            return c.getResources().getQuantityString(R.plurals.auto_notif_published, n, n);
        }
        if ("SIGNED_OUT".equals(job.errorKind)) return c.getString(R.string.auto_notif_signed_out);
        if (PurgedMedia.cannotRetry(job)) {
            return c.getString(R.string.auto_notif_failed,
                    c.getString(R.string.pub_err_media_purged_gone));
        }
        return c.getString(R.string.auto_notif_failed,
                PublishLabels.reason(c, job.errorKind, job.errorDetail));
    }

    /** Shows the result of a job that has just ended; ignores jobs that are not auto-publish. */
    public static void show(Context context, PublishJob job) {
        if (job == null || !job.isAuto()) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.auto_notif_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(context, SavedRecordingsActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int id = ID_BASE + (int) (job.id % 1000);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.auto_notif_title))
                .setContentText(message(context, job))
                .setContentIntent(PendingIntent.getActivity(context, id, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true);
        if ("AUTO_NOT_CONFIGURED".equals(job.errorKind)) {
            String url = WebLinks.isWebUrl(job.errorDetail) ? job.errorDetail : WebLinks.setsUrl();
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            b.addAction(0, context.getString(R.string.auto_notif_set_up_action),
                    PendingIntent.getActivity(context, id + 1000, web,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        Notification n = b.build();
        nm.notify(id, n);
    }

    static int countIds(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isEmpty()) return 0;
        int n = 0;
        for (String s : commaSeparated.split(",")) {
            if (!s.trim().isEmpty()) n++;
        }
        return n;
    }
}
