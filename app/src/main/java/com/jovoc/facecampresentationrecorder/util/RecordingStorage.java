package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.os.Environment;

import com.jovoc.facecampresentationrecorder.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where recordings live on shared storage.
 *
 * The folder is named after the app, so renaming the app moves where new videos go.
 * Videos recorded under the old name stay in their old folder and are still listed,
 * because moving another app-era file on scoped storage is not reliable.
 */
public final class RecordingStorage {

    /** Folder used before the app was renamed to "Short Video Recorder". */
    private static final String LEGACY_FOLDER_NAME = "FaceCam Presentation Recorder";

    private RecordingStorage() {}

    /** Folder new recordings are written to: DCIM/&lt;app name&gt;. */
    public static File getAppDir(Context context) {
        File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        return new File(dcimDir, context.getString(R.string.app_name));
    }

    /** Every folder that may hold recordings, current one first. */
    public static List<File> getAllDirs(Context context) {
        List<File> dirs = new ArrayList<>();
        File current = getAppDir(context);
        dirs.add(current);
        File legacy = new File(current.getParentFile(), LEGACY_FOLDER_NAME);
        if (!legacy.equals(current)) {
            dirs.add(legacy);
        }
        return dirs;
    }
}
