package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.jovoc.facecampresentationrecorder.ui.DrawingBoardView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves the pen marks drawn on each slide to {@code filesDir/slide_ink/<slideId>.json} (same JSON as
 * the drawing board; points are normalised to the slide's own rectangle) and remembers the pen colour.
 *
 * <p>When {@link #isPersistentDrawingSlide} is true, slide marks persist across app restarts.
 * When false (default), marks are held in memory during the active session only, and forgotten after restart.
 */
public final class SlideInkStorage {

    /**
     * Controls whether slide ink persists across app restarts.
     * Defaults to false (forget slide drawing after app restart).
     */
    public static boolean isPersistentDrawingSlide = false;

    private static final String DIR_NAME = "slide_ink";
    private static final String PREFS = "slide_ink_prefs";
    private static final String KEY_PEN_COLOR = "pen_color";
    private static final int DEFAULT_PEN_COLOR = 0xFFFF1744;

    private static final Map<Integer, List<DrawingBoardView.Stroke>> inMemoryStrokes = new HashMap<>();

    private SlideInkStorage() {}

    private static File file(Context context, int slideId) {
        return file(context != null ? context.getFilesDir() : null, slideId);
    }

    static File file(File filesDir, int slideId) {
        if (filesDir == null) return null;
        File dir = new File(filesDir, DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, slideId + ".json");
    }

    /** Saves the marks; an empty list removes the file/entry so cleared slides leave nothing behind. */
    public static synchronized void save(Context context, int slideId, List<DrawingBoardView.Stroke> strokes) {
        save(context != null ? context.getFilesDir() : null, slideId, strokes);
    }

    static synchronized void save(File filesDir, int slideId, List<DrawingBoardView.Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            delete(filesDir, slideId);
            return;
        }
        inMemoryStrokes.put(slideId, new ArrayList<>(strokes));
        if (isPersistentDrawingSlide && filesDir != null) {
            File target = file(filesDir, slideId);
            if (target != null) DrawingBoardStorage.writeStrokes(target, strokes);
        } else if (filesDir != null) {
            File f = file(filesDir, slideId);
            if (f != null && f.exists()) f.delete();
        }
    }

    public static synchronized List<DrawingBoardView.Stroke> load(Context context, int slideId) {
        return load(context != null ? context.getFilesDir() : null, slideId);
    }

    static synchronized List<DrawingBoardView.Stroke> load(File filesDir, int slideId) {
        if (isPersistentDrawingSlide) {
            if (inMemoryStrokes.containsKey(slideId)) {
                return new ArrayList<>(inMemoryStrokes.get(slideId));
            }
            if (filesDir != null) {
                File target = file(filesDir, slideId);
                List<DrawingBoardView.Stroke> fromDisk = target != null
                        ? DrawingBoardStorage.readStrokes(target)
                        : new ArrayList<>();
                if (!fromDisk.isEmpty()) {
                    inMemoryStrokes.put(slideId, new ArrayList<>(fromDisk));
                }
                return fromDisk;
            }
            return new ArrayList<>();
        } else {
            List<DrawingBoardView.Stroke> strokes = inMemoryStrokes.get(slideId);
            return strokes != null ? new ArrayList<>(strokes) : new ArrayList<>();
        }
    }

    public static synchronized void delete(Context context, int slideId) {
        delete(context != null ? context.getFilesDir() : null, slideId);
    }

    static synchronized void delete(File filesDir, int slideId) {
        inMemoryStrokes.remove(slideId);
        if (filesDir != null) {
            File f = file(filesDir, slideId);
            if (f != null && f.exists()) f.delete();
        }
    }

    /** Clears in-memory marks and deletes all slide ink files on disk. */
    public static synchronized void clearAll(Context context) {
        clearAll(context != null ? context.getFilesDir() : null);
    }

    static synchronized void clearAll(File filesDir) {
        clearMemory();
        clearSavedFiles(filesDir);
    }

    /** Clears in-memory marks. */
    public static synchronized void clearMemory() {
        inMemoryStrokes.clear();
    }

    /** Deletes all slide ink files on disk. */
    public static synchronized void clearSavedFiles(Context context) {
        clearSavedFiles(context != null ? context.getFilesDir() : null);
    }

    static synchronized void clearSavedFiles(File filesDir) {
        if (filesDir == null) return;
        File dir = new File(filesDir, DIR_NAME);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }
    }

    public static int loadPenColor(Context context) {
        return prefs(context).getInt(KEY_PEN_COLOR, DEFAULT_PEN_COLOR);
    }

    public static void savePenColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_PEN_COLOR, color).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
