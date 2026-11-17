package com.jovoc.facecampresentationrecorder.aiedit.capture;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The event log of a recording as a JSON file in app-private storage, named after the master:
 * {@code <files dir>/aiedit/logs/<master base name>.json}. An edit or a crop of the master ({@code _edit},
 * {@code _edit_2}, {@code _9x16}, ...) finds the master's log by the same base name. Never logged, never
 * sent anywhere by this class. Every method but {@link #dir} takes the directory, so it runs on a plain JVM.
 */
public final class EventLogStore {

    /** {@code <base>[_edit[_n]][_9x16|_4x5|_1x1]} (see EditGroupMath and VideoCropHelper). */
    private static final Pattern GROUP_NAME =
            Pattern.compile("^(.*?)(_edit(_\\d+)?)?(_(9x16|4x5|1x1))?$");

    private EventLogStore() {}

    /** The folder the logs live in. */
    public static File dir(Context context) {
        return new File(context.getFilesDir(), "aiedit/logs");
    }

    /** The master's base name for any file of its group: no folder, no extension, no edit or crop suffix. */
    public static String masterBaseName(String fileName) {
        String name = new File(fileName).getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Matcher m = GROUP_NAME.matcher(base);
        return m.matches() && !m.group(1).isEmpty() ? m.group(1) : base;
    }

    /** The log file of the group {@code video} belongs to. */
    public static File fileFor(File dir, File video) {
        return new File(dir, masterBaseName(video.getName()) + ".json");
    }

    /** Writes the log of {@code master} (temp file, then rename). False when it could not be written. */
    public static boolean save(File dir, File master, EventLogData data) {
        File target = fileFor(dir, master);
        File tmp = new File(dir, target.getName() + ".tmp");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data.toJson().toString().getBytes(StandardCharsets.UTF_8));
            }
            if (target.exists() && !target.delete()) return false;
            return tmp.renameTo(target);
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
    }

    /** The log of the group {@code video} belongs to, or null when there is none or it cannot be read. */
    public static EventLogData load(File dir, File video) {
        File f = fileFor(dir, video);
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return EventLogData.fromJson(new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8)));
        } catch (IOException | org.json.JSONException e) {
            return null;
        }
    }

    /** Removes the log of the group {@code video} belongs to. */
    public static void delete(File dir, File video) {
        fileFor(dir, video).delete();
    }
}
