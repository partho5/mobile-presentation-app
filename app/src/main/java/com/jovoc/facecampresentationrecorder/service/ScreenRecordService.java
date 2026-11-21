package com.jovoc.facecampresentationrecorder.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioDeviceInfo;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.camera.core.CameraSelector;
import androidx.core.app.NotificationCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.aiedit.capture.EventLog;
import com.jovoc.facecampresentationrecorder.bgreplace.BubbleTrack;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.entitlements.FeatureSnapshot;
import com.jovoc.facecampresentationrecorder.publish.AudioExtractor;
import com.jovoc.facecampresentationrecorder.publish.PostRecordTranscriber;
import com.jovoc.facecampresentationrecorder.publish.PublishManager;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;
import com.jovoc.facecampresentationrecorder.tracker.Meta;
import com.jovoc.facecampresentationrecorder.tracker.Tracked;
import com.jovoc.facecampresentationrecorder.tracker.TrackerNotifications;
import com.jovoc.facecampresentationrecorder.ui.FloatingCamOverlay;
import com.jovoc.facecampresentationrecorder.util.AudioInputs;
import com.jovoc.facecampresentationrecorder.util.RecordingStorage;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenRecordService extends Service {

    private static final String TAG = "ScreenRecordService";
    private static final String CHANNEL_ID = "screen_record_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_SHOW_OVERLAY = "ACTION_SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "ACTION_HIDE_OVERLAY";
    public static final String EXTRA_OVERLAY_X = "EXTRA_OVERLAY_X";
    public static final String EXTRA_OVERLAY_Y = "EXTRA_OVERLAY_Y";
    public static final String EXTRA_OVERLAY_SIZE = "EXTRA_OVERLAY_SIZE";
    public static final String ACTION_RECORDING_FINISHED = "com.jovoc.facecampresentationrecorder.ACTION_RECORDING_FINISHED";
    public static final String EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE";
    public static final String EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA";
    public static final String EXTRA_VIDEO_PATH = "EXTRA_VIDEO_PATH";
    /** True when background replace is running; the UI waits for its result before showing the file. */
    public static final String EXTRA_PROCESSING = "EXTRA_PROCESSING";
    /** Who asked to stop, for the activity log: button, notification, overlay, system, error, destroyed. */
    public static final String EXTRA_STOP_SOURCE = "EXTRA_STOP_SOURCE";

    private final IBinder binder = new LocalBinder();

    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;

    private FloatingCamOverlay overlay;

    private boolean isRecording = false;
    /** The plan when this recording started; null when none runs. Also FeatureSnapshot.allows(). */
    private FeatureSnapshot features;
    /** elapsedRealtime when the recording began; 0 when none runs. For the activity log. */
    private long recordStartedAt = 0;
    private String stopSource = "unknown";
    private String currentVideoPath = null;
    /** The screen recording span: open from the moment the recorder runs until stop; NOOP otherwise. */
    private Tracked recordSpan = Tracked.NOOP;

    public class LocalBinder extends Binder {
        public ScreenRecordService getService() {
            return ScreenRecordService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                if (resultCode != 0 && resultData != null) {
                    startRecordingInternal(resultCode, resultData);
                }
            } else if (ACTION_STOP.equals(action)) {
                String source = intent.getStringExtra(EXTRA_STOP_SOURCE);
                if (source != null) stopSource = source;
                stopRecordingInternal();
            } else if (ACTION_SHOW_OVERLAY.equals(action)) {
                showOverlay(intent.getIntExtra(EXTRA_OVERLAY_X, 0),
                        intent.getIntExtra(EXTRA_OVERLAY_Y, 0),
                        intent.getIntExtra(EXTRA_OVERLAY_SIZE, 0));
            } else if (ACTION_HIDE_OVERLAY.equals(action)) {
                hideOverlay();
            }
        }
        return START_NOT_STICKY;
    }

    private void showOverlay(int x, int y, int size) {
        // Stray command (no recording running): don't leave an idle service behind
        if (!isRecording) {
            if (mediaRecorder == null) stopSelf();
            return;
        }
        if (size <= 0 || !Settings.canDrawOverlays(this)
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // The plan when this recording started decides, not the plan now.
        if (!FeatureSnapshot.allows(Feature.FLOATING_CAM)) return;
        if (overlay == null) {
            overlay = new FloatingCamOverlay(this, this::onOverlayStopClicked);
        }
        int lens = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getInt("key_cam_lens_facing", CameraSelector.LENS_FACING_FRONT);
        overlay.show(x, y, size, lens);
    }

    private void hideOverlay() {
        if (overlay != null) {
            overlay.hide();
            overlay = null;
        }
        if (!isRecording && mediaRecorder == null) stopSelf();
    }

    private void onOverlayStopClicked() {
        stopSource = "overlay";
        // Launch first: the visible overlay window is what exempts us from background-launch limits
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(launch);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not bring app to front", e);
        }
        stopRecordingInternal();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Recording",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notification shown while recording screen and camera");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = null;
        if (launchIntent != null) {
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            contentIntent = PendingIntent.getActivity(this, 0,
                    TrackerNotifications.wrap(this, launchIntent, "recording"),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        Intent stopIntent = new Intent(this, ScreenRecordService.class).setAction(ACTION_STOP)
                .putExtra(EXTRA_STOP_SOURCE, "notification");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Recording screen and camera...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void startRecordingInternal(int resultCode, Intent resultData) {
        if (isRecording) return;

        try {
            Notification notification = createNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // CAMERA / MICROPHONE types throw SecurityException unless the runtime permission is granted
                int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIFICATION_ID, notification, serviceType);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            if (windowManager != null) {
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
            }

            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            int densityDpi = metrics.densityDpi;

            // Ensure width and height are even numbers for encoder stability
            if (width % 2 != 0) width--;
            if (height % 2 != 0) height--;

            // Determine target directory in public DCIM / Camera
            String appName = getString(R.string.app_name);
            File appDir = RecordingStorage.getAppDir(this);
            File dcimDir = appDir.getParentFile();
            if (!appDir.exists()) {
                boolean created = appDir.mkdirs();
                if (!created) {
                    appDir = dcimDir;
                }
            }

            String timeStamp = new SimpleDateFormat("hh-mm-ss-a-yyyy-MM-dd", Locale.US).format(new Date()).toUpperCase(Locale.US);
            String firstWord = (appName != null && !appName.trim().isEmpty()) ? appName.trim().split("\\s+")[0] : appName;
            File outputFile = new File(appDir, timeStamp + "-" + firstWord + ".mp4");
            currentVideoPath = outputFile.getAbsolutePath();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(this);
            } else {
                mediaRecorder = new MediaRecorder();
            }

            boolean recordAudio = getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("key_record_audio", true);

            if (recordAudio) {
                try {
                    mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    // External mic (or the phone's, if chosen). A mic unplugged mid-recording makes
                    // the system fall back to the phone mic on its own, so nothing watches for that.
                    AudioDeviceInfo input = AudioInputs.resolveDevice(this);
                    if (input != null) mediaRecorder.setPreferredDevice(input);
                } catch (Exception e) {
                    Log.w(TAG, "AudioSource.MIC set failed, attempting video-only", e);
                }
            }

            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(currentVideoPath);

            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

            if (recordAudio) {
                try {
                    mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    mediaRecorder.setAudioSamplingRate(44100);
                    mediaRecorder.setAudioEncodingBitRate(128000);
                } catch (Exception e) {
                    Log.w(TAG, "Audio encoder configuration skipped", e);
                }
            }

            mediaRecorder.setVideoEncodingBitRate(8 * 1024 * 1024); // 8Mbps
            mediaRecorder.setVideoFrameRate(30);

            mediaRecorder.prepare();

            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager != null) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
                if (mediaProjection != null) {
                    // Mandatory callback registration on Android 14+ (API 34+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        mediaProjection.registerCallback(new MediaProjection.Callback() {
                            @Override
                            public void onStop() {
                                super.onStop();
                                if (isRecording) stopSource = "system";
                                stopRecordingInternal();
                            }
                        }, new Handler(Looper.getMainLooper()));
                    }

                    virtualDisplay = mediaProjection.createVirtualDisplay(
                            "ScreenRecordVirtualDisplay",
                            width,
                            height,
                            densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            mediaRecorder.getSurface(),
                            null,
                            null
                    );

                    mediaRecorder.start();
                    BubbleTrack.start();
                    // The plan is read once here and kept until this recording and its
                    // post-processing are done: nothing switches on or off mid-recording.
                    features = FeatureSnapshot.beginRecording();
                    isRecording = true;
                    recordStartedAt = SystemClock.elapsedRealtime();
                    recordSpan = ActivityTracker.start("record", "screen recording");
                    EventLog.begin(recordStartedAt, width, height);
                    Log.d(TAG, "Recording started successfully: " + currentVideoPath);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Fatal exception during startRecordingInternal", t);
            ActivityTracker.error("record", "start_failed", t);
            recordSpan.end(Meta.of("failed", true));
            Toast.makeText(this, "Failed to start recording: " + t.getMessage(), Toast.LENGTH_LONG).show();
            stopRecordingInternal();
        }
    }

    private void stopRecordingInternal() {
        if (overlay != null) {
            try {
                overlay.hide();
            } catch (Exception ignored) {}
            overlay = null;
        }
        if (!isRecording && mediaRecorder == null) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Exception ignored) {}
            stopSelf();
            return;
        }

        long durationMs = recordStartedAt > 0 ? SystemClock.elapsedRealtime() - recordStartedAt : 0;
        String source = stopSource;
        recordStartedAt = 0;
        stopSource = "unknown";

        boolean stopFailed = false;
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) {
            stopFailed = true;
            Log.e(TAG, "Error stopping MediaRecorder", e);
        }

        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception ignored) {}
            virtualDisplay = null;
        }

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {}
            mediaProjection = null;
        }

        // The outcome is known only now: a stop error, or no saved file, is a failed recording.
        // Ended here, after the guarded teardown and before any code that could throw.
        File recorded = currentVideoPath != null ? new File(currentVideoPath) : null;
        boolean failed = stopFailed || recorded == null || !recorded.exists() || recorded.length() == 0;
        Tracked span = recordSpan;
        recordSpan = Tracked.NOOP;
        span.end(failed ? Meta.of("stop_source", source, "failed", true) : Meta.of("stop_source", source));

        isRecording = false;
        EventLog.end(this, currentVideoPath);
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {}

        if (currentVideoPath != null) {
            File file = new File(currentVideoPath);
            if (file.exists() && file.length() > 0) {
                ActivityTracker.log("record_saved", "file saved",
                        Meta.of("duration_ms", durationMs, "size_mb", file.length() / (1024 * 1024)));
                // Register with MediaStore for instant Camera / Gallery visibility
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.TITLE, file.getName());
                    values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
                    values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    values.put(MediaStore.Video.Media.DATA, file.getAbsolutePath());
                    getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                } catch (Exception e) {
                    Log.w(TAG, "ContentResolver insert fallback", e);
                }

                MediaScannerConnection.scanFile(
                        getApplicationContext(),
                        new String[]{ currentVideoPath },
                        new String[]{ "video/mp4" },
                        (path, uri) -> Log.d(TAG, "MediaScanner Connection scanned " + path + " -> uri=" + uri)
                );

                try {
                    PublishManager.get(this).beginAuto(file);
                } catch (Exception e) {
                    Log.e(TAG, "Could not save auto-publish", e);
                }

                // Capture main-thread state before any background work.
                final File capturedFile = file;
                final java.util.List<BubbleTrack.Entry> bubbleTrack = BubbleTrack.stop();
                final FeatureSnapshot featSnap = features != null ? features : FeatureSnapshot.take();

                // Decide whether BgReplaceService will do real work. Doing this now means the
                // broadcast below carries the correct EXTRA_PROCESSING flag immediately, without
                // waiting for the background thread.
                final boolean willReplace = RecordSettings.bgReplaceEnabled(this, featSnap)
                        && BubbleTrack.hasVisible(bubbleTrack);
                final boolean willCrop = !RecordSettings.autoCropRatios(this, featSnap).isEmpty();
                boolean processing = willReplace;

                if (willReplace || willCrop) {
                    // BgReplaceService renames the original file when its FFmpeg finishes.
                    // AudioExtractor must read that file first. We do it on a background thread
                    // so the main thread is never blocked, and only start BgReplaceService after
                    // the WAV is safely written. PostRecordTranscriber then finds the pre-extracted
                    // WAV and skips its own extraction step entirely.
                    final Context appCtx = getApplicationContext();
                    new Thread(() -> {
                        final long jobId = Math.abs((long) capturedFile.getAbsolutePath().hashCode());
                        AudioExtractor.extract(appCtx.getCacheDir(), jobId, capturedFile);
                        try {
                            BgReplaceService.enqueue(appCtx, capturedFile, bubbleTrack, featSnap);
                        } catch (Exception e) {
                            Log.e(TAG, "Could not start post-recording processing", e);
                        }
                        try {
                            PublishManager.get(appCtx).onRecordingStopped(capturedFile);
                        } catch (Exception e) {
                            Log.e(TAG, "Could not queue auto-publish", e);
                        }
                        try {
                            PostRecordTranscriber.start(appCtx, capturedFile);
                        } catch (Exception e) {
                            Log.e(TAG, "Could not start post-record transcriber", e);
                        }
                    }, "post-record-preextract").start();
                } else {
                    try {
                        PublishManager.get(this).onRecordingStopped(capturedFile);
                    } catch (Exception e) {
                        Log.e(TAG, "Could not queue auto-publish", e);
                    }
                    try {
                        PostRecordTranscriber.start(this, capturedFile);
                    } catch (Exception e) {
                        Log.e(TAG, "Could not start post-record transcriber", e);
                    }
                }

                Intent finishIntent = new Intent(ACTION_RECORDING_FINISHED);
                finishIntent.setPackage(getPackageName());
                finishIntent.putExtra(EXTRA_VIDEO_PATH, currentVideoPath);
                finishIntent.putExtra(EXTRA_PROCESSING, processing);
                sendBroadcast(finishIntent);
            } else {
                ActivityTracker.error("record", "empty_file", null);
                Toast.makeText(this, "Recording file empty or unreadable", Toast.LENGTH_LONG).show();
            }
        }

        // The queued job holds its own copy of the snapshot
        features = null;
        FeatureSnapshot.endRecording();
        stopSelf();
    }

    public boolean isRecording() {
        return isRecording;
    }

    @Override
    public void onDestroy() {
        if (isRecording) stopSource = "destroyed";
        stopRecordingInternal();
        super.onDestroy();
    }
}
