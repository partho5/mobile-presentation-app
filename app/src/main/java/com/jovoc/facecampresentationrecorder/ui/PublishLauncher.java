package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

import java.io.File;

/**
 * Starts the existing publish flow ({@link PublishActivity}) for one video from the preview
 * panel and the editor. Signed out: no upload and no sign-in detour, only a message.
 */
public final class PublishLauncher {

    private PublishLauncher() {}

    /** True when the publish screen was started. */
    public static boolean publish(Context context, File file) {
        if (file == null || !file.exists()) {
            Toast.makeText(context, "File no longer exists", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!SessionManager.get(context).hasSession()) {
            Toast.makeText(context, R.string.editor_publish_signed_out, Toast.LENGTH_LONG).show();
            return false;
        }
        Intent intent = new Intent(context, PublishActivity.class)
                .putExtra(PublishActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }
}
