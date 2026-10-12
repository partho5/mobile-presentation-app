package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.os.Environment;

import com.jovoc.facecampresentationrecorder.R;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Where recordings live on shared storage.
 *
 * Recordings are saved under DCIM/&lt;app name&gt;.
 */
public final class RecordingStorage {

    private RecordingStorage() {}

    /** Folder new recordings are written to: DCIM/&lt;app name&gt;. */
    public static File getAppDir(Context context) {
        File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        return new File(dcimDir, context.getString(R.string.app_name));
    }

    /** Every folder that may hold recordings. */
    public static List<File> getAllDirs(Context context) {
        return Collections.singletonList(getAppDir(context));
    }
}
