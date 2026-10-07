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

/** Saves the drawing board's strokes to {@code filesDir/drawing_board.json} and remembers the pen colour. */
public final class DrawingBoardStorage {

    private static final String FILE_NAME = "drawing_board.json";
    private static final String PREFS = "drawing_board_prefs";
    private static final String KEY_PEN_COLOR = "pen_color";

    private DrawingBoardStorage() {}

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static synchronized void save(Context context, List<DrawingBoardView.Stroke> strokes) {
        try {
            JSONArray arr = new JSONArray();
            for (DrawingBoardView.Stroke s : strokes) {
                JSONArray pts = new JSONArray();
                for (float v : s.points) pts.put(Math.round(v * 10000f) / 10000.0);
                arr.put(new JSONObject().put("t", s.type).put("c", s.color).put("p", pts));
            }
            byte[] data = new JSONObject().put("strokes", arr).toString().getBytes(StandardCharsets.UTF_8);
            File target = file(context);
            File tmp = new File(target.getParentFile(), FILE_NAME + ".tmp");
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

    public static synchronized List<DrawingBoardView.Stroke> load(Context context) {
        List<DrawingBoardView.Stroke> out = new ArrayList<>();
        File f = file(context);
        if (!f.exists()) return out;
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONObject(json).getJSONArray("strokes");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray p = o.getJSONArray("p");
                float[] pts = new float[p.length()];
                for (int j = 0; j < pts.length; j++) pts[j] = (float) p.getDouble(j);
                out.add(new DrawingBoardView.Stroke(o.getInt("t"), o.getInt("c"), pts));
            }
        } catch (Exception e) {
            out.clear();
        }
        return out;
    }

    public static int loadPenColor(Context context) {
        return prefs(context).getInt(KEY_PEN_COLOR, Color.WHITE);
    }

    public static void savePenColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_PEN_COLOR, color).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
