package com.jovoc.facecampresentationrecorder.util;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public class VideoCropHelper {

    private static final String TAG = "VideoCropHelper";

    /**
     * Broadcast whenever a crop job is queued, finishes, or fails, so any open
     * screen listing recordings can refresh itself instead of showing stale state.
     */
    public static final String ACTION_CROP_STATE_CHANGED =
            "com.jovoc.facecampresentationrecorder.CROP_STATE_CHANGED";

    /**
     * Suffix for the in-progress crop. It must still end in ".mp4": on shared
     * storage MediaProvider derives the MIME type from the extension and refuses to
     * create anything but video/* under DCIM, so a ".part" name fails with EPERM.
     * Recording lists skip it by its leading dot instead.
     */
    private static final String PART_SUFFIX = ".part.mp4";

    /**
     * Crops still being written. The Saved Recordings screen reads this to show a
     * "preparing" placeholder rather than the half-written file, which reports a
     * zero duration and refuses to play.
     */
    private static final CopyOnWriteArrayList<PendingCrop> PENDING = new CopyOnWriteArrayList<>();

    /** One queued or running crop, described well enough to render a placeholder card. */
    public static class PendingCrop {
        public final String sourceName;
        public final String outputPath;
        public final String outputName;
        public final String ratioLabel;
        public final long sourceLastModified;

        PendingCrop(File source, String outputPath, String ratioLabel) {
            this.sourceName = source.getName();
            this.outputPath = outputPath;
            this.outputName = new File(outputPath).getName();
            this.ratioLabel = ratioLabel;
            this.sourceLastModified = source.lastModified();
        }
    }

    public interface CropCallback {
        void onProgress(String ratioLabel, boolean success);
        void onAllComplete(int successCount, int failCount);
    }

    /** Snapshot of the crops currently in flight, newest queue order preserved. */
    public static List<PendingCrop> getPendingCrops() {
        return new ArrayList<>(PENDING);
    }

    public static boolean hasPendingCrops() {
        return !PENDING.isEmpty();
    }

    private static void notifyStateChanged(Context context) {
        Intent intent = new Intent(ACTION_CROP_STATE_CHANGED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    /**
     * Asynchronously crops a video to multiple aspect ratios using FFmpegKit.
     *
     * Each ratio is written to a hidden ".part" file and only renamed into place
     * once FFmpeg reports success, so a partially written video is never visible
     * to the gallery or to the in-app recordings list.
     *
     * @param context       Application context
     * @param inputPath     Absolute path to the original recorded video
     * @param ratios        List of int[2] arrays, each {ratioWidth, ratioHeight}.
     *                      Example: {9,16} for 9:16, {4,5} for 4:5, {1,1} for 1:1
     * @param callback      Callback for progress and completion (called on main thread)
     */
    public static void cropAsync(Context context, String inputPath,
                                  List<int[]> ratios, CropCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        File inputFile = new File(inputPath);

        // Register every job up front so the placeholder cards appear immediately,
        // not only once FFmpeg reaches that ratio.
        List<PendingCrop> jobs = new ArrayList<>();
        // Group entries and pending jobs go in together, so a list scan pruning
        // unknown paths never sees one without the other.
        synchronized (VideoGroupStore.lockForRegistration()) {
            String groupId = VideoGroupStore.ensureGroup(context, inputPath);
            for (int[] ratio : ratios) {
                String outputPath = buildOutputPath(inputPath, ratio[0] + "x" + ratio[1]);
                VideoGroupStore.assign(context, outputPath, groupId);
                PendingCrop job = new PendingCrop(inputFile, outputPath, ratio[0] + ":" + ratio[1]);
                jobs.add(job);
                PENDING.add(job);
            }
        }
        notifyStateChanged(context);

        Executors.newSingleThreadExecutor().execute(() -> {
            int successCount = 0;
            int failCount = 0;

            try {
                // Step 1: Get video dimensions from the original file
                int videoWidth = 0;
                int videoHeight = 0;
                try {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(inputPath);
                    String w = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    retriever.release();
                    if (w != null) videoWidth = Integer.parseInt(w);
                    if (h != null) videoHeight = Integer.parseInt(h);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to read video dimensions", e);
                }

                if (videoWidth <= 0 || videoHeight <= 0) {
                    final int fc = ratios.size();
                    mainHandler.post(() -> {
                        if (callback != null) callback.onAllComplete(0, fc);
                    });
                    return;
                }

                // Step 2: Process each ratio sequentially
                for (int i = 0; i < ratios.size(); i++) {
                    int[] ratio = ratios.get(i);
                    PendingCrop job = jobs.get(i);
                    int ratioW = ratio[0];
                    int ratioH = ratio[1];

                    // Calculate crop dimensions
                    // Crop width = full video width (no horizontal crop)
                    // Crop height = videoWidth * ratioH / ratioW
                    int cropW = videoWidth;
                    int cropH = videoWidth * ratioH / ratioW;

                    // Ensure even numbers (4:2:0 video can only be cropped in 2-pixel steps)
                    if (cropW % 2 != 0) cropW--;
                    if (cropH % 2 != 0) cropH--;

                    String ratioLabel = ratioW + "x" + ratioH;

                    // If crop height >= video height, no crop needed for this ratio
                    if (cropH >= videoHeight) {
                        Log.d(TAG, "Skipping " + ratioLabel +
                              ": crop height " + cropH +
                              " >= video height " + videoHeight);
                        successCount++;
                        finishJob(context, job);
                        final String label = ratioLabel;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onProgress(label, true);
                        });
                        continue;
                    }

                    String outputPath = job.outputPath;
                    File partFile = partFileFor(outputPath);
                    // A ".part" left by an earlier crash would make FFmpeg prompt; drop it.
                    //noinspection ResultOfMethodCallIgnored
                    partFile.delete();

                    // Lossless crop: nothing is decoded or re-encoded. The crop only ever
                    // drops rows from the bottom, which H.264 can express as a crop
                    // rectangle in its stream header, so the kept pixels stay bit-identical.
                    // A re-encode here (FFmpeg's default: MPEG-4 at ~200 kbps, as this build
                    // has no x264) is what used to wreck the picture, the face cam most of all.
                    //
                    // The header counts hidden rows from the coded height, which H.264 pads
                    // up to a multiple of 16, and the value replaces any padding crop already there.
                    int codedHeight = (videoHeight + 15) / 16 * 16;
                    int cropBottom = codedHeight - cropH;

                    // -y = overwrite if exists
                    // -i = input
                    // -c copy = copy video and audio streams without re-encoding
                    // -bsf:v h264_metadata=crop_bottom=N = hide the bottom N rows
                    // -f mp4 = name the container explicitly rather than trust the temp name
                    String[] args = new String[] {
                            "-y",
                            "-i", inputPath,
                            "-c", "copy",
                            "-bsf:v", "h264_metadata=crop_bottom=" + cropBottom,
                            "-f", "mp4",
                            partFile.getAbsolutePath()
                    };

                    Log.d(TAG, "FFmpeg args for " + ratioLabel + ": " + String.join(" ", args));

                    // Execute FFmpeg synchronously (we're already on background thread)
                    FFmpegSession session = FFmpegKit.executeWithArguments(args);

                    boolean ok = ReturnCode.isSuccess(session.getReturnCode())
                            && partFile.exists() && partFile.length() > 0;

                    // FFmpeg copied the container headers too, and they still declare the
                    // uncropped size; players and the gallery read the size from there.
                    if (ok && !Mp4VideoSize.set(partFile, cropW, cropH)) {
                        Log.e(TAG, "Could not update the declared size of " + partFile);
                        ok = false;
                    }

                    File outFile = new File(outputPath);
                    if (ok) {
                        //noinspection ResultOfMethodCallIgnored
                        outFile.delete();
                        ok = partFile.renameTo(outFile);
                        if (!ok) {
                            Log.e(TAG, "Could not move " + partFile + " into place");
                        }
                    } else {
                        Log.e(TAG, "Crop " + ratioLabel + " FAILED. Return code: " +
                              session.getReturnCode() +
                              ", output: " + session.getOutput());
                    }

                    if (!ok) {
                        //noinspection ResultOfMethodCallIgnored
                        partFile.delete();
                        failCount++;
                        finishJob(context, job);
                        final String label = ratioLabel;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onProgress(label, false);
                        });
                        continue;
                    }

                    Log.d(TAG, "Crop " + ratioLabel + " succeeded: " + outputPath);
                    successCount++;

                    // Register with MediaStore so it appears in gallery. Only now
                    // that the file is complete, so no player ever sees a stub.
                    try {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Video.Media.TITLE, outFile.getName());
                        values.put(MediaStore.Video.Media.DISPLAY_NAME, outFile.getName());
                        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                        values.put(MediaStore.Video.Media.DATA, outFile.getAbsolutePath());
                        context.getContentResolver().insert(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    } catch (Exception e) {
                        Log.w(TAG, "MediaStore insert failed for " + ratioLabel, e);
                    }

                    // Scan with MediaScanner
                    MediaScannerConnection.scanFile(context,
                            new String[]{outputPath},
                            new String[]{"video/mp4"}, null);

                    finishJob(context, job);
                    final String label = ratioLabel;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onProgress(label, true);
                    });
                }
            } finally {
                // Nothing may stay pending once the worker exits, or the list would
                // show a placeholder that never resolves.
                boolean anyLeft = false;
                for (PendingCrop job : jobs) {
                    if (PENDING.remove(job)) anyLeft = true;
                }
                if (anyLeft) notifyStateChanged(context);
            }

            // Step 3: Notify completion
            final int s = successCount;
            final int f = failCount;
            mainHandler.post(() -> {
                if (callback != null) callback.onAllComplete(s, f);
            });
        });
    }

    private static void finishJob(Context context, PendingCrop job) {
        if (PENDING.remove(job)) {
            notifyStateChanged(context);
        }
    }

    /**
     * Hidden sibling of the destination: "/dir/clip_9x16.mp4" -> "/dir/.clip_9x16.part.mp4".
     * Dot-prefixed so the media scanner and the recordings list skip it; same
     * directory, so the final rename is atomic.
     */
    private static File partFileFor(String outputPath) {
        File out = new File(outputPath);
        return new File(out.getParentFile(), partFileNameFor(outputPath));
    }

    /** Name only, so callers can tell a live ".part" from an abandoned one. */
    public static String partFileNameFor(String outputPath) {
        String base = new File(outputPath).getName();
        if (base.toLowerCase(Locale.US).endsWith(".mp4")) {
            base = base.substring(0, base.length() - 4);
        }
        return "." + base + PART_SUFFIX;
    }

    /** "/dir/clip.mp4" + "9x16" -> "/dir/clip_9x16.mp4", replacing only the extension. */
    private static String buildOutputPath(String inputPath, String ratioLabel) {
        int dot = inputPath.lastIndexOf('.');
        String base = dot > inputPath.lastIndexOf('/') ? inputPath.substring(0, dot) : inputPath;
        return base + "_" + ratioLabel + ".mp4";
    }

    /** True for the temp file an in-progress crop writes to. */
    public static boolean isPartialFile(String fileName) {
        return fileName != null && fileName.startsWith(".") && fileName.endsWith(PART_SUFFIX);
    }
}
