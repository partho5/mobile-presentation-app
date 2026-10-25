package com.jovoc.facecampresentationrecorder.edit;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the editor's unsaved edit in a small file per master, so a killed app can offer it back.
 * One file per master path (name from the path's hash), written to a temp name then renamed, so a
 * crash mid-write never leaves a torn draft. A draft only applies to the master it was made for:
 * if that file changed since (size or date), the draft is dropped. No Android classes.
 */
public final class EditDraftStore {

    /** Drafts older than this are forgotten. */
    public static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    /** What was saved: the edit and where the playhead was. */
    public static final class Draft {
        public final EditProject project;
        public final long playheadMs;

        Draft(EditProject project, long playheadMs) {
            this.project = project;
            this.playheadMs = playheadMs;
        }
    }

    private EditDraftStore() {}

    static File fileFor(File dir, String masterPath) {
        return new File(dir, "draft_" + Integer.toHexString(masterPath.hashCode()) + ".json");
    }

    /** Writes the draft; failures are swallowed (a missing draft only costs the user a redo). */
    public static void save(File dir, EditProject project, long playheadMs, long nowMs) {
        File master = new File(project.getMasterPath());
        File target = fileFor(dir, project.getMasterPath());
        File tmp = new File(dir, target.getName() + ".tmp");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            JSONObject o = new JSONObject()
                    .put("project", EditProjectJson.toJson(project))
                    .put("playhead", playheadMs)
                    .put("masterSize", master.length())
                    .put("masterModified", master.lastModified())
                    .put("savedAt", nowMs);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                target.delete();
                if (!tmp.renameTo(target)) tmp.delete();
            }
        } catch (IOException | JSONException | RuntimeException e) {
            tmp.delete();
        }
    }

    /**
     * The draft for {@code masterPath}, or null when there is none, it is damaged or too old, or
     * the master is no longer the file it was made for (a stale draft is deleted).
     */
    public static Draft load(File dir, String masterPath, long nowMs) {
        File f = fileFor(dir, masterPath);
        if (!f.isFile()) return null;
        try {
            byte[] bytes = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            EditProject p = EditProjectJson.fromJson(o.getString("project"));
            File master = new File(masterPath);
            boolean fresh = nowMs - o.getLong("savedAt") <= MAX_AGE_MS;
            boolean sameMaster = master.isFile()
                    && master.length() == o.getLong("masterSize")
                    && master.lastModified() == o.getLong("masterModified");
            if (p == null || !p.getMasterPath().equals(masterPath) || !fresh || !sameMaster) {
                f.delete();
                return null;
            }
            return new Draft(p, Math.max(0, o.getLong("playhead")));
        } catch (IOException | JSONException | RuntimeException e) {
            f.delete();
            return null;
        }
    }

    public static void clear(File dir, String masterPath) {
        fileFor(dir, masterPath).delete();
    }

    /** Deletes drafts past {@link #MAX_AGE_MS} (by file date) and leftover temp files. */
    public static void prune(File dir, long nowMs) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().endsWith(".tmp") || nowMs - f.lastModified() > MAX_AGE_MS) f.delete();
        }
    }
}
