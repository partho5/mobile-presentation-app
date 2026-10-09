package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Remembers which files are versions of the same recording.
 *
 * A source video and every aspect-ratio crop made from it share one group id, kept
 * against the file path. Rename moves the entry, so the link survives any filename.
 * A file with no entry gets a group of its own on first sight, and each group's list
 * colour is remembered here so it never changes between visits.
 */
public final class VideoGroupStore {

    private static final String PREFS = "video_groups";
    private static final String COLOR_PREFS = "video_group_colors";

    private VideoGroupStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    /** Forgets a deleted file, so a new file at the same path never inherits its group. */
    public static synchronized void remove(Context context, String path) {
        prefs(context).edit().remove(path).apply();
    }

    /** Palette slot fixed for this group the first time it was shown, or -1 if none yet. */
    public static synchronized int getColorIndex(Context context, String groupId) {
        return colorPrefs(context).getInt(groupId, -1);
    }

    public static synchronized void setColorIndex(Context context, String groupId, int index) {
        colorPrefs(context).edit().putInt(groupId, index).apply();
    }

    private static SharedPreferences colorPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(COLOR_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Drops entries for files that are gone and are not about to appear, then the
     * colours of groups left with no files.
     *
     * The keep set is read inside the lock, and {@link #lockForRegistration} callers
     * hold the same lock, so a crop being registered cannot lose its entries here.
     */
    public static synchronized void prune(Context context, Supplier<Set<String>> keepPaths) {
        Set<String> keep = keepPaths.get();
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor editor = p.edit();
        Set<String> liveGroups = new HashSet<>();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String path = e.getKey();
            if (keep.contains(path) || new File(path).exists()) {
                liveGroups.add(String.valueOf(e.getValue()));
            } else {
                editor.remove(path);
            }
        }
        editor.apply();

        SharedPreferences colors = colorPrefs(context);
        SharedPreferences.Editor colorEditor = colors.edit();
        for (String groupId : colors.getAll().keySet()) {
            if (!liveGroups.contains(groupId)) colorEditor.remove(groupId);
        }
        colorEditor.apply();
    }

    /** Monitor to hold while registering new group entries and the pending jobs that protect them. */
    public static Object lockForRegistration() {
        return VideoGroupStore.class;
    }
}
