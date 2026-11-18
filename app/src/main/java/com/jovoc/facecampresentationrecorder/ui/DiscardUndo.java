package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleEmbedStore;
import com.jovoc.facecampresentationrecorder.util.VideoGroupStore;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Discarding a recording without a confirm dialog: the preview closes, "Recording discarded · Undo" shows for a few
 * seconds, and the file is deleted when they pass. Nothing is touched before then, so Undo has nothing to put back,
 * and a process that dies inside the window loses nothing (the file simply stays). Free: discarding never costs
 * credits. Main thread only.
 */
final class DiscardUndo {

    /** How long Undo is offered; the deletion happens when it ends. */
    static final long UNDO_MS = 5000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The deletions waiting out their undo window, by file path. */
    private static final Map<String, Runnable> PENDING = new HashMap<>();

    private DiscardUndo() {}

    /**
     * Offers the undo for {@code file} on {@code host}'s screen and deletes the file after {@link #UNDO_MS}.
     * {@code afterDelete} runs once the file is gone (the list drops it then); never when Undo was tapped.
     * A host that is not an activity has nowhere to show the snackbar, so the file is deleted at once.
     */
    static void discard(Context host, File file, Runnable afterDelete) {
        final Context app = host.getApplicationContext();
        final String path = file.getAbsolutePath();
        if (PENDING.containsKey(path)) return; // already waiting out its window
        View anchor = host instanceof Activity ? ((Activity) host).findViewById(android.R.id.content) : null;
        if (anchor == null) {
            finish(app, file, afterDelete);
            return;
        }

        final Snackbar bar = Snackbar.make(anchor, R.string.player_discarded, Snackbar.LENGTH_INDEFINITE);
        bar.setDuration((int) UNDO_MS);
        bar.setAction(R.string.player_undo, v -> {
            Runnable waiting = PENDING.remove(path);
            if (waiting != null) MAIN.removeCallbacks(waiting);
        });
        Runnable commit = () -> {
            PENDING.remove(path);
            bar.dismiss();
            finish(app, file, afterDelete);
        };
        PENDING.put(path, commit);
        MAIN.postDelayed(commit, UNDO_MS);
        bar.show();
    }

    private static void finish(Context app, File file, Runnable afterDelete) {
        if (delete(app, file)) {
            if (afterDelete != null) afterDelete.run();
        } else {
            Toast.makeText(app, "Failed to delete video", Toast.LENGTH_SHORT).show();
        }
    }

    /** Removes the file from disk, the group, the subtitle record and MediaStore. */
    private static boolean delete(Context app, File file) {
        if (file.exists() && !file.delete()) return false;
        VideoGroupStore.remove(app, file.getAbsolutePath());
        SubtitleEmbedStore.remove(app, file.getAbsolutePath());
        try {
            ContentResolver resolver = app.getContentResolver();
            resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.DATA + "=?", new String[]{file.getAbsolutePath()});
        } catch (Exception ignored) {
            // the media index is a convenience; the file is gone
        }
        MediaScannerConnection.scanFile(app, new String[]{file.getAbsolutePath()}, null, null);
        return true;
    }
}
