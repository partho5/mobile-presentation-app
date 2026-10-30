package com.jovoc.facecampresentationrecorder.subtitle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Manages caching of subtitle tracks in app-private storage.
 *
 * <p>Privacy note (see ACTIVITY_LOG_PRIVACY.md): Subtitle words and transcript texts are private
 * user data. This class must NEVER log spoken transcript, word text, or subtitle content.
 */
public final class SubtitleCache {

    private static final String DIR_SUBTITLES = "subtitles";
    private static final String PREFIX = "sub_";
    private static final String EXT = ".json";

    private SubtitleCache() {}

    /**
     * Resolves the cache file path for a given job id: {@code <cacheDir>/subtitles/sub_<jobId>.json}.
     */
    public static File fileFor(File cacheDir, long jobId) {
        if (cacheDir == null) return null;
        File subDir = new File(cacheDir, DIR_SUBTITLES);
        return new File(subDir, PREFIX + jobId + EXT);
    }

    /**
     * Saves raw subtitle JSON into app-private cache atomically.
     */
    public static void save(File cacheDir, long jobId, String jsonString) {
        if (cacheDir == null || jsonString == null || jsonString.trim().isEmpty()) {
            return;
        }
        File subDir = new File(cacheDir, DIR_SUBTITLES);
        saveToFile(new File(subDir, PREFIX + jobId + EXT), jsonString);
    }

    /**
     * Loads and parses the cached subtitle track for the given job id. Returns null if missing or invalid.
     */
    public static SubtitleTrack load(File cacheDir, long jobId) {
        if (cacheDir == null) return null;
        File file = fileFor(cacheDir, jobId);
        return loadFromFile(file);
    }

    /**
     * Loads a subtitle track associated with a video file.
     * Checks in order:
     * 1. {@code <cacheDir>/subtitles/<videoFile.getName()>.json}
     * 2. {@code <cacheDir>/subtitles/<videoBaseName>.json}
     * 3. {@code <videoParent>/<videoBaseName>.sub.json}
     * 4. {@code <videoParent>/<videoFile.getName()>.sub.json}
     * Returns null if missing or invalid.
     */
    public static SubtitleTrack loadForVideo(File cacheDir, File videoFile) {
        if (videoFile == null) return null;

        String name = videoFile.getName();
        int dot = name.lastIndexOf('.');
        String baseName = (dot > 0) ? name.substring(0, dot) : name;

        // 1. Check cache directory
        if (cacheDir != null) {
            File subDir = new File(cacheDir, DIR_SUBTITLES);
            SubtitleTrack t1 = loadFromFile(new File(subDir, name + EXT));
            if (t1 != null) return t1;

            SubtitleTrack t2 = loadFromFile(new File(subDir, baseName + EXT));
            if (t2 != null) return t2;
        }

        // 2. Check video directory
        File parent = videoFile.getParentFile();
        if (parent != null) {
            SubtitleTrack p1 = loadFromFile(new File(parent, baseName + ".sub.json"));
            if (p1 != null) return p1;

            SubtitleTrack p2 = loadFromFile(new File(parent, name + ".sub.json"));
            if (p2 != null) return p2;
        }

        return null;
    }

    /**
     * Saves raw subtitle JSON associated with a video file into app-private cache and/or alongside video.
     */
    public static void saveForVideo(File cacheDir, File videoFile, String json) {
        if (videoFile == null || json == null || json.trim().isEmpty()) {
            return;
        }

        String name = videoFile.getName();
        int dot = name.lastIndexOf('.');
        String baseName = (dot > 0) ? name.substring(0, dot) : name;

        // Save to cache directory: <cacheDir>/subtitles/<videoFile.getName()>.json
        if (cacheDir != null) {
            File subDir = new File(cacheDir, DIR_SUBTITLES);
            saveToFile(new File(subDir, name + EXT), json);
        }

        // Save alongside video if directory is writable: <videoParent>/<videoBaseName>.sub.json
        File parent = videoFile.getParentFile();
        if (parent != null && parent.canWrite()) {
            saveToFile(new File(parent, baseName + ".sub.json"), json);
        }
    }

    private static SubtitleTrack loadFromFile(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return null;
        }
        try {
            int length = (int) file.length();
            byte[] bytes = new byte[length];
            try (FileInputStream fis = new FileInputStream(file)) {
                int offset = 0;
                while (offset < length) {
                    int read = fis.read(bytes, offset, length - offset);
                    if (read < 0) break;
                    offset += read;
                }
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            return SubtitleTrack.parse(json);
        } catch (Exception ignored) {
            // Privacy rule: NEVER log transcript or subtitle words
            return null;
        }
    }

    private static void saveToFile(File target, String jsonString) {
        if (target == null || jsonString == null || jsonString.trim().isEmpty()) {
            return;
        }
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            File tmp = new File(parent, target.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(jsonString.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }

            if (target.exists()) {
                target.delete();
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(jsonString.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                tmp.delete();
            }
        } catch (IOException ignored) {
            // Privacy rule: NEVER log transcript or subtitle words
        }
    }

    /**
     * Deletes any cached subtitle file for the given job id. Never throws.
     */
    public static void delete(File cacheDir, long jobId) {
        if (cacheDir == null) return;
        try {
            File file = fileFor(cacheDir, jobId);
            if (file != null && file.exists()) {
                file.delete();
            }
            if (file != null) {
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                if (tmp.exists()) {
                    tmp.delete();
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Checks if a cached subtitle file exists for the given job id.
     */
    public static boolean exists(File cacheDir, long jobId) {
        if (cacheDir == null) return false;
        File file = fileFor(cacheDir, jobId);
        return file != null && file.exists() && file.length() > 0;
    }
}
