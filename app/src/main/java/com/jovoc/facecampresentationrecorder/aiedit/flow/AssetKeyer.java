package com.jovoc.facecampresentationrecorder.aiedit.flow;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.jovoc.facecampresentationrecorder.aiedit.engine.overlay.AssetFiles;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns the white box of an icon clip (an mp4 has no alpha) into transparent PNG frames, once per asset. The frames
 * are written to a ".part" folder and moved into place only when FFmpeg succeeded and left a frame, so a half-keyed
 * folder is never used. Lives in the flow package: the engine only reads the finished frames. Blocking.
 */
final class AssetKeyer {

    private static final String TAG = "AiEditAssets";

    // The key, tuned from real clips in task A4: the colour to remove and how close counts, the frame rate, the
    // widest frame kept and the longest clip kept.
    private static final String KEY_COLOR = "0xFFFFFF";
    private static final String KEY_SIMILARITY = "0.1";
    private static final String KEY_BLEND = "0.1";
    private static final int MAX_WIDTH = 512;
    private static final int MAX_SECONDS = 6;

    private AssetKeyer() {}

    /**
     * Keys {@code mp4} into {@code framesDir} (an existing folder there is replaced).
     *
     * @return true when the folder holds the keyed frames; false when nothing was made (the part is removed)
     */
    static boolean key(File mp4, File framesDir, AtomicBoolean cancel) {
        if (cancel != null && cancel.get()) return false;
        File part = new File(framesDir.getParentFile(), framesDir.getName() + ".part");
        // A ".part" left by an earlier crash would hold stale frames
        AssetFiles.deleteTree(part);
        if (!part.mkdirs()) return false;
        // One filter graph: key the colour, set the frame rate, cap the width (the comma of min() is escaped)
        String filter = "colorkey=" + KEY_COLOR + ":" + KEY_SIMILARITY + ":" + KEY_BLEND
                + ",fps=" + AssetFiles.FRAME_FPS
                + ",scale=min(" + MAX_WIDTH + "\\,iw):-2";
        String[] args = new String[] {
                "-y",
                "-i", mp4.getAbsolutePath(),
                "-t", String.valueOf(MAX_SECONDS),
                "-vf", filter,
                "-pix_fmt", "rgba",
                new File(part, "%04d.png").getAbsolutePath()
        };
        boolean ok = false;
        try {
            FFmpegSession session = FFmpegKit.executeWithArguments(args);
            File[] png = part.listFiles((d, n) -> n.endsWith(".png"));
            ok = ReturnCode.isSuccess(session.getReturnCode()) && png != null && png.length > 0;
            if (!ok) Log.w(TAG, "An asset could not be keyed. Return code: " + session.getReturnCode());
            if (ok) {
                AssetFiles.deleteTree(framesDir);
                ok = part.renameTo(framesDir);
            }
        } catch (RuntimeException e) {
            // Never the path or the id here: the log stays free of anything about the edit
            Log.w(TAG, "An asset could not be keyed: " + e.getClass().getSimpleName());
        } finally {
            if (!ok) AssetFiles.deleteTree(part);
        }
        return ok;
    }
}
