package com.jovoc.facecampresentationrecorder.subtitle;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Persists two lightweight facts per recording file:
 * <ol>
 *   <li>Whether subtitles have already been burned into the video's pixels (verified by file
 *       length + mtime fingerprint so overwritten files are never misclassified).</li>
 *   <li>The server {@code aiCaptionId} created by {@code PostRecordTranscriber}, so subsequent
 *       actions (embedding or publishing) reuse it instead of calling {@code POST /ai-captions}
 *       again and charging the user credits twice.</li>
 * </ol>
 *
 * <p>Privacy note: stores only file fingerprints and opaque server IDs — never transcript words.
 */
public final class SubtitleEmbedStore {

    private static final String PREFS = "subtitle_embed_state";
    private static final String PREFIX_EMBED = "emb:";
    private static final String PREFIX_CAPTION_PATH = "cap:";
    private static final String PREFIX_CAPTION_BASE = "capbase:";

    public interface Store {
        String get(String key);
        void put(String key, String value);
        void remove(String key);
    }

    private final Store store;

    public SubtitleEmbedStore(Store store) {
        this.store = store;
    }

    public static SubtitleEmbedStore get(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new SubtitleEmbedStore(new Store() {
            @Override
            public String get(String key) {
                return prefs.getString(key, null);
            }

            @Override
            public void put(String key, String value) {
                prefs.edit().putString(key, value).apply();
            }

            @Override
            public void remove(String key) {
                prefs.edit().remove(key).apply();
            }
        });
    }

    // ---- Static convenience API ----

    public static boolean isEmbedded(Context context, File file) {
        if (context == null) return false;
        return get(context).isEmbedded(file);
    }

    public static void markEmbedded(Context context, File file) {
        if (context == null) return;
        get(context).markEmbedded(file);
    }

    public static void saveCaptionId(Context context, File video, String captionId) {
        if (context == null) return;
        get(context).saveCaptionId(video, captionId);
    }

    public static String getCaptionId(Context context, File video) {
        if (context == null) return null;
        return get(context).getCaptionId(video);
    }

    public static void onRenamed(Context context, File oldFile, File newFile) {
        if (context == null) return;
        get(context).onRenamed(oldFile, newFile);
    }

    public static void remove(Context context, String path) {
        if (context == null) return;
        get(context).remove(path);
    }

    // ---- Instance methods (unit-testable without Android Context) ----

    public synchronized boolean isEmbedded(File file) {
        if (file == null || !file.isFile()) return false;
        String stored = store.get(PREFIX_EMBED + file.getAbsolutePath());
        return stored != null && stored.equals(fingerprint(file));
    }

    public synchronized void markEmbedded(File file) {
        if (file == null || !file.isFile()) return;
        store.put(PREFIX_EMBED + file.getAbsolutePath(), fingerprint(file));
    }

    public synchronized void saveCaptionId(File video, String captionId) {
        if (video == null || captionId == null) return;
        String trimmed = captionId.trim();
        if (trimmed.isEmpty() || "none".equals(trimmed)) return;
        store.put(PREFIX_CAPTION_PATH + video.getAbsolutePath(), trimmed);
        String baseKey = masterBaseKey(video);
        if (baseKey != null) {
            store.put(PREFIX_CAPTION_BASE + baseKey, trimmed);
        }
    }

    public synchronized String getCaptionId(File video) {
        if (video == null) return null;
        String byPath = store.get(PREFIX_CAPTION_PATH + video.getAbsolutePath());
        if (byPath != null && !byPath.isEmpty()) return byPath;
        String baseKey = masterBaseKey(video);
        if (baseKey != null) {
            String byBase = store.get(PREFIX_CAPTION_BASE + baseKey);
            if (byBase != null && !byBase.isEmpty()) return byBase;
        }
        return null;
    }

    public synchronized void onRenamed(File oldFile, File newFile) {
        if (oldFile == null || newFile == null) return;
        String oldPath = oldFile.getAbsolutePath();
        String newPath = newFile.getAbsolutePath();

        String oldEmb = store.get(PREFIX_EMBED + oldPath);
        if (oldEmb != null) {
            store.remove(PREFIX_EMBED + oldPath);
            if (newFile.isFile()) {
                store.put(PREFIX_EMBED + newPath, fingerprint(newFile));
            }
        }

        String oldCap = store.get(PREFIX_CAPTION_PATH + oldPath);
        if (oldCap != null) {
            store.remove(PREFIX_CAPTION_PATH + oldPath);
            store.put(PREFIX_CAPTION_PATH + newPath, oldCap);
        }
        String oldBase = masterBaseKey(oldFile);
        String newBase = masterBaseKey(newFile);
        if (oldBase != null && newBase != null && !oldBase.equals(newBase)) {
            String baseCap = store.get(PREFIX_CAPTION_BASE + oldBase);
            if (baseCap != null) {
                store.put(PREFIX_CAPTION_BASE + newBase, baseCap);
            }
        }
    }

    public synchronized void remove(String path) {
        if (path == null || path.isEmpty()) return;
        store.remove(PREFIX_EMBED + path);
        store.remove(PREFIX_CAPTION_PATH + path);
    }

    private static String fingerprint(File file) {
        return file.length() + ":" + file.lastModified();
    }

    private static String masterBaseKey(File file) {
        if (file == null) return null;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;
        String masterBase = SubtitleCache.stripCropSuffix(base);
        File parent = file.getParentFile();
        return (parent != null ? parent.getAbsolutePath() + "/" : "") + masterBase;
    }
}
