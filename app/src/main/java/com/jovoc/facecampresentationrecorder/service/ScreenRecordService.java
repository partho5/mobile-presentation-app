package com.jovoc.facecampresentationrecorder.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.jovoc.facecampresentationrecorder.R;

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
    public static final String ACTION_RECORDING_FINISHED = "com.jovoc.facecampresentationrecorder.ACTION_RECORDING_FINISHED";
    public static final String EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE";
    public static final String EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA";
    public static final String EXTRA_VIDEO_PATH = "EXTRA_VIDEO_PATH";

    private final IBinder binder = new LocalBinder();

    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;

    private boolean isRecording = false;
    private String currentVideoPath = null;

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
                stopRecordingInternal();
            }
        }
        return START_NOT_STICKY;
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
        return new NotificationCompat.Builder(this, CHANNEL_ID)
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
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
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
            File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            File appDir = new File(dcimDir, appName);
            if (!appDir.exists()) {
                boolean created = appDir.mkdirs();
                if (!created) {
                    appDir = dcimDir;
                }
            }

            String timeStamp = new SimpleDateFormat("hh-mm-a-yyyy-MM-dd", Locale.US).format(new Date()).toUpperCase(Locale.US);
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
                    isRecording = true;
                    Log.d(TAG, "Recording started successfully: " + currentVideoPath);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Fatal exception during startRecordingInternal", t);
            Toast.makeText(this, "Failed to start recording: " + t.getMessage(), Toast.LENGTH_LONG).show();
            stopRecordingInternal();
        }
    }

    private void stopRecordingInternal() {
        if (!isRecording && mediaRecorder == null) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Exception ignored) {}
            stopSelf();
            return;
        }

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) {
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

        isRecording = false;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {}

        if (currentVideoPath != null) {
            File file = new File(currentVideoPath);
            if (file.exists() && file.length() > 0) {
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

                Intent finishIntent = new Intent(ACTION_RECORDING_FINISHED);
                finishIntent.setPackage(getPackageName());
                finishIntent.putExtra(EXTRA_VIDEO_PATH, currentVideoPath);
                sendBroadcast(finishIntent);
            } else {
                Toast.makeText(this, "Recording file empty or unreadable", Toast.LENGTH_LONG).show();
            }
        }

        stopSelf();
    }

    public boolean isRecording() {
        return isRecording;
    }

    @Override
    public void onDestroy() {
        stopRecordingInternal();
        super.onDestroy();
    }
}
