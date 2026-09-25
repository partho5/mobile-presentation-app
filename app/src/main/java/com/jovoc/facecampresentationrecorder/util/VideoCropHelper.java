package com.jovoc.facecampresentationrecorder.util;

import android.content.ContentValues;
import android.content.Context;
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
import java.util.List;
import java.util.concurrent.Executors;

public class VideoCropHelper {

    private static final String TAG = "VideoCropHelper";

    public interface CropCallback {
        void onProgress(String ratioLabel, boolean success);
        void onAllComplete(int successCount, int failCount);
    }

    /**
     * Asynchronously crops a video to multiple aspect ratios using FFmpegKit.
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

        Executors.newSingleThreadExecutor().execute(() -> {
            int successCount = 0;
            int failCount = 0;

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
                final int fc = ratios.size();
                mainHandler.post(() -> {
                    if (callback != null) callback.onAllComplete(0, fc);
                });
                return;
            }

            if (videoWidth <= 0 || videoHeight <= 0) {
                final int fc = ratios.size();
                mainHandler.post(() -> {
                    if (callback != null) callback.onAllComplete(0, fc);
                });
                return;
            }

            // Step 2: Process each ratio sequentially
            for (int[] ratio : ratios) {
                int ratioW = ratio[0];
                int ratioH = ratio[1];

                // Calculate crop dimensions
                // Crop width = full video width (no horizontal crop)
                // Crop height = videoWidth * ratioH / ratioW
                int cropW = videoWidth;
                int cropH = videoWidth * ratioH / ratioW;

                // Ensure even numbers (required by H264 encoder)
                if (cropW % 2 != 0) cropW--;
                if (cropH % 2 != 0) cropH--;

                String ratioLabel = ratioW + "x" + ratioH;

                // If crop height >= video height, no crop needed for this ratio
                if (cropH >= videoHeight) {
                    Log.d(TAG, "Skipping " + ratioLabel +
                          ": crop height " + cropH +
                          " >= video height " + videoHeight);
                    successCount++;
                    final String label = ratioLabel;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onProgress(label, true);
                    });
                    continue;
                }

                // Build output file path
                // "recording.mp4" -> "recording_9x16.mp4"
                String outputPath = inputPath.replace(".mp4",
                        "_" + ratioLabel + ".mp4");

                // Build FFmpeg command arguments array
                // -y = overwrite if exists
                // -i = input
                // -vf crop=W:H:X:Y = crop filter (X=0, Y=0 = from top-left)
                // -c:a copy = copy audio stream without re-encoding
                String[] args = new String[] {
                        "-y",
                        "-i", inputPath,
                        "-vf", String.format("crop=%d:%d:0:0", cropW, cropH),
                        "-c:a", "copy",
                        outputPath
                };

                Log.d(TAG, "FFmpeg args for " + ratioLabel + ": " + String.join(" ", args));

                // Execute FFmpeg synchronously (we're already on background thread)
                FFmpegSession session = FFmpegKit.executeWithArguments(args);

                if (ReturnCode.isSuccess(session.getReturnCode())) {
                    Log.d(TAG, "Crop " + ratioLabel + " succeeded: " + outputPath);
                    successCount++;

                    // Register with MediaStore so it appears in gallery
                    try {
                        File outFile = new File(outputPath);
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

                    final String label = ratioLabel;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onProgress(label, true);
                    });
                } else {
                    Log.e(TAG, "Crop " + ratioLabel + " FAILED. Return code: " +
                          session.getReturnCode() +
                          ", output: " + session.getOutput());
                    failCount++;

                    final String label = ratioLabel;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onProgress(label, false);
                    });
                }
            }

            // Step 3: Notify completion
            final int s = successCount;
            final int f = failCount;
            mainHandler.post(() -> {
                if (callback != null) callback.onAllComplete(s, f);
            });
        });
    }
}
