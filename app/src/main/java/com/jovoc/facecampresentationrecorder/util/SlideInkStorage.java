package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.jovoc.facecampresentationrecorder.ui.DrawingBoardView;

import java.io.File;
import java.util.List;

/**
 * Saves the pen marks drawn on each slide to {@code filesDir/slide_ink/<slideId>.json} (same JSON as
 * the drawing board; points are normalised to the slide's own rectangle) and remembers the pen colour.
 */
public final class SlideInkStorage {

    private static final String DIR_NAME = "slide_ink";
    private static final String PREFS = "slide_ink_prefs";
    private static final String KEY_PEN_COLOR = "pen_color";
    private static final int DEFAULT_PEN_COLOR = 0xFFFF1744;

    private SlideInkStorage() {}

    private static File file(Context context, int slideId) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, slideId + ".json");
    }

    /** Saves the marks; an empty list removes the file so cleared slides leave nothing behind. */
    public static synchronized void save(Context context, int slideId, List<DrawingBoardView.Stroke> strokes) {
        if (strokes.isEmpty()) {
            delete(context, slideId);
            return;
        }
        DrawingBoardStorage.writeStrokes(file(context, slideId), strokes);
    }

    public static synchronized List<DrawingBoardView.Stroke> load(Context context, int slideId) {
        return DrawingBoardStorage.readStrokes(file(context, slideId));
    }

    public static synchronized void delete(Context context, int slideId) {
        File f = file(context, slideId);
        if (f.exists()) f.delete();
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
