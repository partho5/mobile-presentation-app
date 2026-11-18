package com.jovoc.facecampresentationrecorder.util;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.provider.MediaStore;

import com.jovoc.facecampresentationrecorder.publish.CaptionUsage;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleEmbedStore;

import java.io.File;

/** The app's one way to delete a recording file: disk, group store, MediaStore, media scan. */
public final class RecordingDeleter {

    private RecordingDeleter() {}

    /** True when the file is gone afterwards (it may already have been). */
    public static boolean deleteFile(Context context, File file) {
        if (file == null) return false;
        boolean removed = !file.exists() || file.delete();
        if (!removed) return false;
        VideoGroupStore.remove(context, file.getAbsolutePath());
        CaptionUsage.releaseFor(context, file);
        SubtitleEmbedStore.remove(context, file.getAbsolutePath());

        try {
            ContentResolver resolver = context.getContentResolver();
            resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.DATA + "=?",
                    new String[]{file.getAbsolutePath()});
        } catch (Exception ignored) {}

        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
        return true;
    }
}
