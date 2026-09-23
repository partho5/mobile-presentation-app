package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.jovoc.facecampresentationrecorder.R;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SlideLogger {

    private static final String TAG = "SlideLogger";
    private static File logFile = null;

    public static void init(Context context) {
        try {
            String appName = context.getString(R.string.app_name);
            File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            File appDir = new File(dcimDir, appName);
            if (!appDir.exists()) {
                boolean created = appDir.mkdirs();
                if (!created) {
                    appDir = context.getFilesDir();
                }
            }
            logFile = new File(appDir, "slide_debug_log.txt");
            log("INIT", "SlideLogger initialized. Log path: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SlideLogger", e);
        }
    }

    public static void log(String category, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String formattedMessage = String.format("[%s] [%s] %s", timestamp, category, message);

        Log.d(TAG + "_" + category, message);

        if (logFile != null) {
            try (FileWriter fw = new FileWriter(logFile, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println(formattedMessage);
            } catch (Exception e) {
                Log.e(TAG, "Failed to write to slide_debug_log.txt", e);
            }
        }
    }

    public static File getLogFile() {
        return logFile;
    }
}
