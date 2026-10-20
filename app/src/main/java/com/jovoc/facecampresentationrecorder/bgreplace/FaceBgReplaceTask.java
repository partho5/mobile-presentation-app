package com.jovoc.facecampresentationrecorder.bgreplace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.os.SystemClock;
import android.util.Log;

import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.entitlements.FeatureSnapshot;
import com.jovoc.facecampresentationrecorder.util.UserBackgrounds;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Returns true if the replaced video now sits at {@code video}. Setting {@code cancel} aborts
     * the run: all resources and the temp file are released and the original stays as it was.
     * {@code features} is the plan the recording started with: without bg_replace nothing runs and
     * the original stays; without bg_custom an uploaded background gives way to the default.
     */
    public static boolean replace(Context context, File video, List<BubbleTrack.Entry> track,
                                  FeatureSnapshot features, AtomicBoolean cancel,
                                  OfflineBgProcessor.ProgressListener listener) {
        if (!features.has(Feature.BG_REPLACE)) {
            Log.w(TAG, "bg_replace is not in the plan; keeping original");
            return false;
        }
        Context app = context.getApplicationContext();
        // DCIM only accepts media file types, so temp files there must end in .mp4
        File processed = new File(video.getParentFile(), baseName(video) + ".bgtmp.mp4");
        long startMs = SystemClock.elapsedRealtime();
        boolean ok = false;
        Bitmap background = null;
        try {
            background = loadSelectedBackground(app, features.has(Feature.BG_CUSTOM));
            new OfflineBgProcessor(app).withCancelFlag(cancel)
                    .processBubble(video, processed, background, track, listener);
            // A cancel that arrives as the encode finishes still wins: never swap after it
            if (cancel.get()) throw new CancellationException("Cancelled before swap");
            ok = swapIn(processed, video);
            Log.d(TAG, "Done in " + (SystemClock.elapsedRealtime() - startMs) + " ms, swapped=" + ok);
        } catch (CancellationException e) {
            Log.d(TAG, "Background replacement cancelled; keeping original");
        } catch (Throwable t) {
            Log.e(TAG, "Background replacement failed; keeping original", t);
        } finally {
            if (processed.exists()) processed.delete();
            if (background != null) background.recycle();
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

    /** Used when nothing is saved or the saved background no longer exists (e.g. a deleted upload). */
    public static final String DEFAULT_BG_ASSET = "bg_samples/modern-office.webp";

    /**
     * The background chosen in Settings, falling back to the default, then to a plain colour.
     * An uploaded photo counts only while {@code customAllowed} (the bg_custom feature); the saved
     * choice is left alone so it applies again when the plan does.
     */
    public static Bitmap loadSelectedBackground(Context context, boolean customAllowed) {
        String saved = UserBackgrounds.effectivePath(
                context.getSharedPreferences("facecam_prefs", Context.MODE_PRIVATE)
                        .getString("bg_asset_path", DEFAULT_BG_ASSET),
                customAllowed, DEFAULT_BG_ASSET);
        Bitmap bitmap = decodeBackground(context, saved);
        if (bitmap == null && !DEFAULT_BG_ASSET.equals(saved)) {
            bitmap = decodeBackground(context, DEFAULT_BG_ASSET);
        }
        if (bitmap != null) return bitmap;
        Bitmap fallback = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        fallback.eraseColor(Color.rgb(40, 120, 90));
        return fallback;
    }

    /** Decodes an uploaded file (absolute path) or a bundled asset; null if it can't be read. */
    private static Bitmap decodeBackground(Context context, String path) {
        if (UserBackgrounds.isUserPath(path)) {
            return BitmapFactory.decodeFile(path);
        }
        try (InputStream in = context.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            Log.w(TAG, "Background asset missing: " + path, e);
            return null;
        }
    }
}
