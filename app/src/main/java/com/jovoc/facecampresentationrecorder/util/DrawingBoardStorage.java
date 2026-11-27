package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import com.jovoc.facecampresentationrecorder.ui.DrawingBoardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves the drawing board's strokes per slide to {@code filesDir/board_slides/<slideId>.json}, remembers per slide
 * whether the user left the board open (marker file {@code <slideId>.shown}), and remembers the pen colour.
 * The old single board ({@code filesDir/drawing_board.json}) is adopted once by the first slide that opens the board.
 */
public final class DrawingBoardStorage {

    private static final String LEGACY_FILE_NAME = "drawing_board.json";
    private static final String DIR_NAME = "board_slides";
    private static final String PREFS = "drawing_board_prefs";
    private static final String KEY_PEN_COLOR = "pen_color";
    private static final String KEY_TEXT_COLOR = "text_color";
    private static final String KEY_TEXT_SIZE = "text_size";
    private static final String KEY_INTRO_SHOWN = "intro_shown";

    private DrawingBoardStorage() {}

    private static File strokesFile(File filesDir, int slideId) {
        File dir = new File(filesDir, DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, slideId + ".json");
    }

    private static File shownFile(File filesDir, int slideId) {
        return new File(strokesFile(filesDir, slideId).getParentFile(), slideId + ".shown");
    }

    /** Saves this slide's board; an empty board removes the file so cleared slides leave nothing behind. */
    public static synchronized void saveSlide(Context context, int slideId, List<DrawingBoardView.Stroke> strokes) {
        saveSlide(context.getFilesDir(), slideId, strokes);
    }

    static synchronized void saveSlide(File filesDir, int slideId, List<DrawingBoardView.Stroke> strokes) {
        File target = strokesFile(filesDir, slideId);
        if (strokes == null || strokes.isEmpty()) {
            target.delete();
        } else {
            writeStrokes(target, strokes);
        }
    }

    public static synchronized List<DrawingBoardView.Stroke> loadSlide(Context context, int slideId) {
        return loadSlide(context.getFilesDir(), slideId);
    }

    static synchronized List<DrawingBoardView.Stroke> loadSlide(File filesDir, int slideId) {
        return readStrokes(strokesFile(filesDir, slideId));
    }

    /** True when the user left the board open on this slide, so it opens by itself when the slide starts. */
    public static synchronized boolean isShownOnSlide(Context context, int slideId) {
        return isShownOnSlide(context.getFilesDir(), slideId);
    }

    static synchronized boolean isShownOnSlide(File filesDir, int slideId) {
        return shownFile(filesDir, slideId).exists();
    }

    /** Remember the user's own choice (toggle), never an automatic hide. */
    public static synchronized void setShownOnSlide(Context context, int slideId, boolean shown) {
        setShownOnSlide(context.getFilesDir(), slideId, shown);
    }

    static synchronized void setShownOnSlide(File filesDir, int slideId, boolean shown) {
        File f = shownFile(filesDir, slideId);
        try {
            if (shown) {
                f.createNewFile();
            } else {
                f.delete();
            }
        } catch (IOException ignored) {
            // Losing the "open by itself" hint is harmless; never crash the recorder over it.
        }
    }

    /** Forgets the board of a deleted slide, or of a slide whose content was replaced. */
    public static synchronized void deleteSlide(Context context, int slideId) {
        deleteSlide(context.getFilesDir(), slideId);
    }

    static synchronized void deleteSlide(File filesDir, int slideId) {
        strokesFile(filesDir, slideId).delete();
        shownFile(filesDir, slideId).delete();
    }

    /**
     * Moves the old single board drawing into this slide (first come, first served) and deletes the old file.
     * Returns the strokes, or an empty list when there is nothing to adopt.
     */
    public static synchronized List<DrawingBoardView.Stroke> adoptLegacy(Context context, int slideId) {
        return adoptLegacy(context.getFilesDir(), slideId);
    }

    static synchronized List<DrawingBoardView.Stroke> adoptLegacy(File filesDir, int slideId) {
        File legacy = new File(filesDir, LEGACY_FILE_NAME);
        if (!legacy.exists()) return new ArrayList<>();
        List<DrawingBoardView.Stroke> strokes = readStrokes(legacy);
        if (!strokes.isEmpty()) writeStrokes(strokesFile(filesDir, slideId), strokes);
        legacy.delete();
        return strokes;
    }

    /** Atomically writes the strokes as JSON (tmp file + rename). Also used by {@link SlideInkStorage}. */
    static void writeStrokes(File target, List<DrawingBoardView.Stroke> strokes) {
        try {
            JSONArray arr = new JSONArray();
            for (DrawingBoardView.Stroke s : strokes) {
                JSONArray pts = new JSONArray();
                for (float v : s.points) pts.put(Math.round(v * 10000f) / 10000.0);
                JSONObject o = new JSONObject().put("t", s.type).put("c", s.color).put("p", pts);
                if (s.texts != null) {
                    JSONArray items = new JSONArray();
                    for (DrawingBoardView.TextItem t : s.texts) {
                        items.put(new JSONObject().put("s", t.text).put("x", Math.round(t.x * 10000f) / 10000.0)
                                .put("y", Math.round(t.y * 10000f) / 10000.0).put("c", t.color).put("z", t.size));
                    }
                    o.put("x", items);
                }
                arr.put(o);
            }
            byte[] data = new JSONObject().put("strokes", arr).toString().getBytes(StandardCharsets.UTF_8);
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            if (!tmp.renameTo(target)) {
                Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // A failed save only loses the drawing on next launch; never crash the recorder over it.
        }
    }

    static List<DrawingBoardView.Stroke> readStrokes(File f) {
        List<DrawingBoardView.Stroke> out = new ArrayList<>();
        if (!f.exists()) return out;
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONObject(json).getJSONArray("strokes");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray p = o.getJSONArray("p");
                float[] pts = new float[p.length()];
                for (int j = 0; j < pts.length; j++) pts[j] = (float) p.getDouble(j);
                List<DrawingBoardView.TextItem> items = null;
                JSONArray x = o.optJSONArray("x");
                if (x != null) {
                    items = new ArrayList<>();
                    for (int j = 0; j < x.length(); j++) {
                        JSONObject t = x.getJSONObject(j);
                        items.add(new DrawingBoardView.TextItem(t.getString("s"), (float) t.getDouble("x"),
                                (float) t.getDouble("y"), t.getInt("c"),
                                Math.max(0, Math.min(DrawingBoardView.TEXT_SIZE_LARGE, t.optInt("z", 1)))));
                    }
                }
                out.add(new DrawingBoardView.Stroke(o.getInt("t"), o.getInt("c"), pts, items));
            }
        } catch (Exception e) {
            out.clear();
        }
        return out;
    }

    public static int loadPenColor(Context context) {
        return prefs(context).getInt(KEY_PEN_COLOR, 0xFFFF1744); // palette red
    }

    public static void savePenColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_PEN_COLOR, color).apply();
    }

    public static int loadTextColor(Context context) {
        return prefs(context).getInt(KEY_TEXT_COLOR, 0xFFFFB300); // palette amber
    }

    public static void saveTextColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_TEXT_COLOR, color).apply();
    }

    public static int loadTextSize(Context context) {
        return prefs(context).getInt(KEY_TEXT_SIZE, DrawingBoardView.TEXT_SIZE_MEDIUM);
    }

    public static void saveTextSize(Context context, int level) {
        prefs(context).edit().putInt(KEY_TEXT_SIZE, level).apply();
    }

    /** True exactly once: the first time the board is ever shown. */
    public static boolean consumeIntro(Context context) {
        if (prefs(context).getBoolean(KEY_INTRO_SHOWN, false)) return false;
        prefs(context).edit().putBoolean(KEY_INTRO_SHOWN, true).apply();
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
