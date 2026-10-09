package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Remembers which files are versions of the same recording.
 *
 * A source video and every aspect-ratio crop made from it share one group id, kept
 * against the file path. Rename moves the entry, so the link survives any filename;
 * files made before this existed simply have no group and stand alone.
 */
public final class VideoGroupStore {

    private static final String PREFS = "video_groups";

    private VideoGroupStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Group id for this file, or null if it was never part of a crop set. */
    public static synchronized String getGroupId(Context context, String path) {
        return prefs(context).getString(path, null);
    }

    /** Reuses the file's group, or starts a new one so crops can join it. */
    public static synchronized String ensureGroup(Context context, String path) {
        SharedPreferences p = prefs(context);
        String id = p.getString(path, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString(path, id).apply();
        }
        return id;
    }

    public static synchronized void assign(Context context, String path, String groupId) {
        prefs(context).edit().putString(path, groupId).apply();
    }

    /** Carries the group over to the file's new path after a rename. */
    public static synchronized void onRenamed(Context context, File oldFile, File newFile) {
        SharedPreferences p = prefs(context);
        String id = p.getString(oldFile.getAbsolutePath(), null);
        if (id == null) return;
        p.edit().remove(oldFile.getAbsolutePath())
                .putString(newFile.getAbsolutePath(), id).apply();
    }

    /** Drops entries for files that are gone and are not about to appear. */
    public static synchronized void prune(Context context, Set<String> keepPaths) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String path = e.getKey();
            if (keepPaths.contains(path) || new File(path).exists()) continue;
            if (editor == null) editor = p.edit();
            editor.remove(path);
        }
        if (editor != null) editor.apply();
    }
}
