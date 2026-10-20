package com.jovoc.facecampresentationrecorder.bgreplace;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.util.RecordingStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Standalone test for after-recording background replacement: records a face clip of up
 * to 20 s with a plain camera preview, then replaces the background with the image chosen
 * in Settings and saves the result to the recordings folder.
 */
public class BgTestActivity extends AppCompatActivity {

    private static final String TAG = "BgTestActivity";
    private static final long MAX_DURATION_MS = 20_000;

    private PreviewView previewView;
    private VideoView resultView;
    private Button actionButton;
    private TextView statusText;

    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private File rawFile;
    private boolean keepRawFile;
    private long recordStartMs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (recording == null) return;
            long elapsed = SystemClock.elapsedRealtime() - recordStartMs;
            statusText.setText("Recording… " + Math.max(0, (MAX_DURATION_MS - elapsed) / 1000) + " s left");
            if (elapsed >= MAX_DURATION_MS) {
                stopRecording();
            } else {
                handler.postDelayed(this, 250);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Background replace test");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        resultView = new VideoView(this);
        resultView.setVisibility(View.GONE);
        FrameLayout.LayoutParams resultParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        resultParams.gravity = Gravity.CENTER;
        root.addView(resultView, resultParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        bottom.setPadding(pad, pad, pad, pad);

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(16);
        statusText.setGravity(Gravity.CENTER);
        statusText.setShadowLayer(4, 0, 0, Color.BLACK);
        bottom.addView(statusText);

        actionButton = new Button(this);
        actionButton.setText("Start recording (20 s)");
        actionButton.setOnClickListener(v -> {
            if (recording == null) startRecording();
            else stopRecording();
        });
        bottom.addView(actionButton);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        root.addView(bottom, bottomParams);
        setContentView(root);

        // Debug hook: process an existing clip without recording,
        // e.g. adb shell am start -n <pkg>/.bgreplace.BgTestActivity --es input /sdcard/Download/x.mp4
        String debugInput = getIntent().getStringExtra("input");
        if (debugInput != null) {
            rawFile = new File(debugInput);
            keepRawFile = true;
            actionButton.setEnabled(false);
            startProcessing();
            return;
        }
        bindCamera();
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, videoCapture);
            } catch (Exception e) {
                Log.e(TAG, "Camera bind failed", e);
                statusText.setText("Camera error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("MissingPermission")
    private void startRecording() {
        if (videoCapture == null) return;
        rawFile = new File(getCacheDir(), "bg_test_raw.mp4");
        PendingRecording pending = videoCapture.getOutput()
                .prepareRecording(this, new FileOutputOptions.Builder(rawFile).build());
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }
        recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                if (fin.hasError()) {
                    Log.e(TAG, "Recording error " + fin.getError(), fin.getCause());
                    statusText.setText("Recording failed (" + fin.getError() + ")");
                    actionButton.setEnabled(true);
                } else {
                    startProcessing();
                }
            }
        });
        recordStartMs = SystemClock.elapsedRealtime();
        actionButton.setText("Stop");
        handler.post(tick);
    }

    private void stopRecording() {
        if (recording == null) return;
        recording.stop();
        recording = null;
        handler.removeCallbacks(tick);
        actionButton.setEnabled(false);
        statusText.setText("Finishing recording…");
    }

    private void startProcessing() {
        // Release the camera: processing uses the GPU and video codecs heavily
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider.getInstance(this).get().unbindAll();
            } catch (Exception ignored) {}
        }, ContextCompat.getMainExecutor(this));

        File dir = RecordingStorage.getAppDir(this);
        if (!dir.exists()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File output = new File(dir, stamp + "-bg.mp4");
        Bitmap background = FaceBgReplaceTask.loadSelectedBackground(this,
                Entitlements.has(Feature.BG_CUSTOM));
        long startMs = SystemClock.elapsedRealtime();

        new Thread(() -> {
            try {
                OfflineBgProcessor.ProgressListener progress = percent ->
                        handler.post(() -> statusText.setText("Replacing background… " + percent + "%"));
                // Debug: --es circle left,top,size,ring treats the input as a screen recording
                String circle = getIntent().getStringExtra("circle");
                if (circle != null) {
                    String[] v = circle.split(",");
                    List<BubbleTrack.Entry> track = new ArrayList<>();
                    track.add(new BubbleTrack.Entry(0, true, Integer.parseInt(v[0]),
                            Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3])));
                    new OfflineBgProcessor(this).processBubble(rawFile, output, background, track, progress);
                } else {
                    new OfflineBgProcessor(this).processFullFrame(rawFile, output, background, progress);
                }
                long tookMs = SystemClock.elapsedRealtime() - startMs;
                Log.d(TAG, "Processed in " + tookMs + " ms -> " + output);
                MediaScannerConnection.scanFile(this, new String[]{output.getAbsolutePath()},
                        new String[]{"video/mp4"}, null);
                handler.post(() -> showResult(output, tookMs));
            } catch (Exception e) {
                Log.e(TAG, "Processing failed", e);
                output.delete();
                handler.post(() -> {
                    statusText.setText("Processing failed: " + e.getMessage());
                    actionButton.setEnabled(true);
                });
            } finally {
                if (!keepRawFile) rawFile.delete();
            }
        }, "OfflineBgProcessor").start();
    }

    private void showResult(File output, long tookMs) {
        if (isFinishing() || isDestroyed()) return;
        previewView.setVisibility(View.GONE);
        resultView.setVisibility(View.VISIBLE);
        resultView.setVideoPath(output.getAbsolutePath());
        resultView.setOnPreparedListener(mp -> mp.setLooping(true));
        resultView.start();
        statusText.setText(String.format(Locale.US, "Done in %.1f s — saved as %s",
                tookMs / 1000f, output.getName()));
        actionButton.setText("Record again");
        actionButton.setEnabled(true);
        actionButton.setOnClickListener(v -> recreate());
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recording != null) {
            recording.stop();
            recording = null;
        }
        super.onDestroy();
    }
}
