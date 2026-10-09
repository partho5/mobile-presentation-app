package com.jovoc.facecampresentationrecorder.bgreplace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.util.List;

/**
 * After a screen recording stops: replaces the background inside the face cam circle and
 * swaps the result in place of the original file. On any failure the original recording is
 * kept untouched. Blocking: runs on the worker thread of {@code BgReplaceService}.
 */
public final class FaceBgReplaceTask {

    private static final String TAG = "FaceBgReplaceTask";

    private FaceBgReplaceTask() {}

    /** The re-encode is slightly bigger than the source; plus headroom for the temp file. */
    public static boolean hasEnoughSpace(File video) {
        File dir = video.getParentFile();
        return dir != null && dir.getUsableSpace() >= video.length() * 3 / 2;
    }

    /** Returns true if the replaced video now sits at {@code video}. */
    public static boolean replace(Context context, File video, List<BubbleTrack.Entry> track,
                                  OfflineBgProcessor.ProgressListener listener) {
        Context app = context.getApplicationContext();
        // DCIM only accepts media file types, so temp files there must end in .mp4
        File processed = new File(video.getParentFile(), baseName(video) + ".bgtmp.mp4");
        long startMs = SystemClock.elapsedRealtime();
        boolean ok = false;
        try {
            new OfflineBgProcessor(app).processBubble(video, processed,
                    loadSelectedBackground(app), track, listener);
            ok = swapIn(processed, video);
            Log.d(TAG, "Done in " + (SystemClock.elapsedRealtime() - startMs) + " ms, swapped=" + ok);
        } catch (Throwable t) {
            Log.e(TAG, "Background replacement failed; keeping original", t);
        } finally {
            if (processed.exists()) processed.delete();
        }
        if (ok) {
            MediaScannerConnection.scanFile(app, new String[]{video.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
        }
        return ok;
    }

    /** Replaces {@code target} with {@code replacement} without ever losing the original. */
    private static boolean swapIn(File replacement, File target) {
        if (!replacement.exists() || replacement.length() == 0) return false;
        File backup = new File(target.getParentFile(), baseName(target) + ".orig.mp4");
        if (!target.renameTo(backup)) return false;
        if (!replacement.renameTo(target)) {
            backup.renameTo(target);
            return false;
        }
        backup.delete();
        return true;
    }

    private static String baseName(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** The background chosen in Settings, or a plain colour if the asset is missing. */
    public static Bitmap loadSelectedBackground(Context context) {
        String asset = context.getSharedPreferences("facecam_prefs", Context.MODE_PRIVATE)
                .getString("bg_asset_path", "bg_samples/100ydesign-white-room-2267978.jpg");
        try (InputStream in = context.getAssets().open(asset)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap != null) return bitmap;
        } catch (Exception e) {
            Log.w(TAG, "Background asset missing: " + asset, e);
        }
        Bitmap fallback = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        fallback.eraseColor(Color.rgb(40, 120, 90));
        return fallback;
    }
}
