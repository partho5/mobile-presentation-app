package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.util.DrawingBoardStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the drawing board's tool bar (Pen, Eraser, Undo, Redo, Clear) and colour palette to its
 * {@link DrawingBoardView}, and saves/restores the drawing.
 */
public class DrawingBoardController implements DrawingBoardView.Listener {

    private static final float ACTIVE_SCALE = 2f;
    private static final long SCALE_ANIM_MS = 150L;
    private static final float DISABLED_ALPHA = 0.3f;

    private static final int[] PALETTE = {
            0xFFFFFFFF, // white
            0xFF00E5FF, // cyan
            0xFFFFB300, // amber
            0xFF00E676, // green
            0xFFFF1744, // red
            0xFFD500F9, // purple
    };
    private static final long PALETTE_HIDE_MS = 2000L;
    private static final long SAVE_DEBOUNCE_MS = 1000L;
    private static final float SWATCH_DP = 36f;
    private static final float ARC_RADIUS_DP = 150f;
    private static final float ARC_START_DEG = 82f;
    private static final float ARC_END_DEG = 6f;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService saver = Executors.newSingleThreadExecutor();
    private final float density;

    private final DrawingBoardView board;
    private final ImageView toolPen;
    private final ImageView toolEraser;
    private final ImageView toolUndo;
    private final ImageView toolRedo;
    private final ImageView toolClear;
    private final FrameLayout palette;
    private final List<View> swatches = new ArrayList<>();

    private boolean dirty = false;
    private final Runnable hidePalette = () -> showPalette(false);
    private final Runnable saveRunnable = this::saveInBackground;

    public DrawingBoardController(View container) {
        context = container.getContext().getApplicationContext();
        density = container.getResources().getDisplayMetrics().density;
        board = container.findViewById(R.id.drawing_board_canvas);
        toolPen = container.findViewById(R.id.tool_pen);
        toolEraser = container.findViewById(R.id.tool_eraser);
        toolUndo = container.findViewById(R.id.tool_undo);
        toolRedo = container.findViewById(R.id.tool_redo);
        toolClear = container.findViewById(R.id.tool_clear);
        palette = container.findViewById(R.id.drawing_board_palette);

        toolPen.setOnClickListener(v -> {
            setEraser(false, true);
            showPalette(true);
        });
        toolEraser.setOnClickListener(v -> {
            setEraser(true, true);
            showPalette(false);
        });
        toolUndo.setOnClickListener(v -> {
            board.undo();
            onActionCommitted();
        });
        toolRedo.setOnClickListener(v -> {
            board.redo();
            onActionCommitted();
        });
        toolClear.setOnClickListener(v -> board.clearBoard());

        buildSwatches();
        board.setListener(this);
        board.importStrokes(DrawingBoardStorage.load(context));
        setPenColor(DrawingBoardStorage.loadPenColor(context));
        setEraser(false, false);
    }

    public DrawingBoardView getBoard() {
        return board;
    }

    /** Applies the colour to the board and tints the pen icon's fill (its outline stays black). */
    public void setPenColor(int color) {
        board.setPenColor(color);
        Drawable d = toolPen.getDrawable();
        if (d instanceof LayerDrawable) {
            Drawable fill = ((LayerDrawable) d).findDrawableByLayerId(R.id.pen_fill);
            if (fill != null) fill.mutate().setTint(color);
        }
        toolPen.invalidate();
        for (int i = 0; i < swatches.size(); i++) styleSwatch(swatches.get(i), PALETTE[i] == color);
    }

    /** Call when the board slides away: closes the palette and saves right away. */
    public void onBoardHidden() {
        handler.removeCallbacks(hidePalette);
        palette.setVisibility(View.GONE);
        flush();
    }

    /** Saves now if anything changed since the last save (used on hide and onStop). */
    public void flush() {
        handler.removeCallbacks(saveRunnable);
        if (!dirty) return;
        dirty = false;
        DrawingBoardStorage.save(context, board.exportStrokes());
    }

    // ---- palette ----

    private void buildSwatches() {
        int size = Math.round(SWATCH_DP * density);
        for (int color : PALETTE) {
            View sw = new View(context);
            palette.addView(sw, new FrameLayout.LayoutParams(size, size));
            sw.setOnClickListener(v -> {
                setPenColor(color);
                DrawingBoardStorage.savePenColor(context, color);
                scheduleHidePalette();
            });
            swatches.add(sw);
        }
    }

    private void styleSwatch(View sw, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(PALETTE[swatches.indexOf(sw)]);
        bg.setStroke(Math.round((selected ? 3f : 1f) * density), selected ? Color.WHITE : 0xFF808080);
        sw.setBackground(bg);
    }

    private void showPalette(boolean show) {
        handler.removeCallbacks(hidePalette);
        if (!show) {
            palette.setVisibility(View.GONE);
            return;
        }
        layoutArc();
        palette.setAlpha(0f);
        palette.setVisibility(View.VISIBLE);
        palette.animate().alpha(1f).setDuration(SCALE_ANIM_MS).start();
        scheduleHidePalette();
    }

    private void scheduleHidePalette() {
        handler.removeCallbacks(hidePalette);
        handler.postDelayed(hidePalette, PALETTE_HIDE_MS);
    }

    /** Fans the swatches out above the Pen button, bottom-anchored to the canvas. */
    private void layoutArc() {
        int w = palette.getWidth();
        int h = palette.getHeight();
        if (w <= 0 || h <= 0) return;
        float half = SWATCH_DP * density / 2f;
        float cx = toolPen.getLeft() + toolPen.getWidth() / 2f;
        float cy = h;
        float r = Math.min(ARC_RADIUS_DP * density, Math.min(h - half - 4 * density, w - cx - half));
        int n = swatches.size();
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(ARC_START_DEG + (ARC_END_DEG - ARC_START_DEG) * i / (n - 1));
            View sw = swatches.get(i);
            sw.setX((float) (cx + r * Math.cos(a)) - half);
            sw.setY((float) (cy - r * Math.sin(a)) - half);
        }
    }

    // ---- tools ----

    private void setEraser(boolean eraser, boolean animate) {
        board.setEraserMode(eraser);
        scale(toolPen, eraser ? 1f : ACTIVE_SCALE, animate);
        scale(toolEraser, eraser ? ACTIVE_SCALE : 1f, animate);
    }

    private void scale(View v, float target, boolean animate) {
        if (animate) {
            v.animate().scaleX(target).scaleY(target).setDuration(SCALE_ANIM_MS).start();
        } else {
            v.setScaleX(target);
            v.setScaleY(target);
        }
    }

    // ---- DrawingBoardView.Listener ----

    @Override
    public void onActionCommitted() {
        dirty = true;
        handler.removeCallbacks(saveRunnable);
        handler.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS);
    }

    private void saveInBackground() {
        if (!dirty) return;
        dirty = false;
        List<DrawingBoardView.Stroke> snapshot = board.exportStrokes();
        saver.execute(() -> DrawingBoardStorage.save(context, snapshot));
    }

    @Override
    public void onHistoryChanged(boolean canUndo, boolean canRedo) {
        toolUndo.setEnabled(canUndo);
        toolUndo.setAlpha(canUndo ? 1f : DISABLED_ALPHA);
        toolRedo.setEnabled(canRedo);
        toolRedo.setAlpha(canRedo ? 1f : DISABLED_ALPHA);
    }
}
