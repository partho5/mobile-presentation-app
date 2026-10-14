package com.jovoc.facecampresentationrecorder.publish;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The Cancel action of the undo-window notification: the same cancel the card uses. */
public class PublishCancelReceiver extends BroadcastReceiver {

    public static final String ACTION_CANCEL =
            "com.jovoc.facecampresentationrecorder.PUBLISH_CANCEL";
    public static final String EXTRA_JOB_ID = "job_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent == null ? -1L : intent.getLongExtra(EXTRA_JOB_ID, -1L);
        if (id < 0) return;
        PendingResult pending = goAsync();
        PublishManager.get(context).cancel(id, pending::finish);
    }
}
