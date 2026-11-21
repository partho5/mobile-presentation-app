package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;
import com.jovoc.facecampresentationrecorder.aiedit.capture.EventLog;
import com.jovoc.facecampresentationrecorder.util.DrawingBoardStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the drawing board's tool bar (Pen, Eraser, Text, Undo, Redo, Clear) and colour palette to its
 * {@link DrawingBoardView}, and saves/restores the drawing.
 */
public class DrawingBoardController implements DrawingBoardView.Listener, DrawingBoardView.TextListener {

    private static final float ACTIVE_SCALE = 2f;
    private static final long SCALE_ANIM_MS = 150L;
    private static final float DISABLED_ALPHA = 0.3f;
    private static final float SIZE_UNSELECTED_ALPHA = 0.45f;

    private static final int TOOL_PEN = 0;
    private static final int TOOL_ERASER = 1;
    private static final int TOOL_TEXT = 2;

    private static final int[] PALETTE = {
            0xFFFFFFFF, // white
            0xFF00E5FF, // cyan
            0xFFFFB300, // amber
            0xFF00E676, // green
            0xFFFF1744, // red
            0xFFD500F9, // purple
    };
    private static final long PALETTE_HIDE_MS = 3000L;
    private static final long PALETTE_PICK_HIDE_MS = 200L;
    private static final long PALETTE_FADE_OUT_MS = 120L;
    private static final long SWATCH_STAGGER_MS = 45L;
    private static final long SWATCH_FLY_MS = 260L;
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
    private final ImageView toolText;
    private final EditText textInput;
    private final View textBar;
    private final TextView[] textSizes = new TextView[3];
    private final ImageView toolUndo;
    private final ImageView toolRedo;
    private final View toolClear;
    private final FrameLayout palette;
    private final TextView intro;
    private final List<View> swatches = new ArrayList<>();

    private int tool = TOOL_PEN;
    private View paletteAnchor;
    private boolean updatingInput = false;
    private boolean dirty = false;
    private int slideId = SlideInkController.NO_SLIDE;
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
        toolText = container.findViewById(R.id.tool_text);
        textInput = container.findViewById(R.id.drawing_board_text_input);
        textBar = container.findViewById(R.id.drawing_board_text_bar);
        textSizes[0] = container.findViewById(R.id.text_size_s);
        textSizes[1] = container.findViewById(R.id.text_size_m);
        textSizes[2] = container.findViewById(R.id.text_size_l);
        paletteAnchor = toolPen;
        toolClear = container.findViewById(R.id.tool_clear);
        palette = container.findViewById(R.id.drawing_board_palette);
        intro = container.findViewById(R.id.drawing_board_intro);

        toolPen.setOnClickListener(v -> {
            ActivityTracker.log("click", "drawing pen");
            boolean wasPen = tool == TOOL_PEN;
            setTool(TOOL_PEN, true);
            paletteAnchor = toolPen;
            // Pen already active and palette open: a second tap closes it.
            showPalette(!wasPen || palette.getVisibility() != View.VISIBLE);
        });
        toolEraser.setOnClickListener(v -> {
            ActivityTracker.log("click", "drawing eraser");
            setTool(TOOL_ERASER, true);
            showPalette(false);
        });
        toolText.setOnClickListener(v -> {
            ActivityTracker.log("click", "drawing text");
            boolean wasText = tool == TOOL_TEXT;
            setTool(TOOL_TEXT, true);
            paletteAnchor = toolText;
            showPalette(!wasText || palette.getVisibility() != View.VISIBLE);
        });
        toolUndo.setOnClickListener(v -> {
            ActivityTracker.log("click", "drawing undo");
            board.undo();
            onActionCommitted();
        });
        toolRedo.setOnClickListener(v -> {
            ActivityTracker.log("click", "drawing redo");
            board.redo();
            onActionCommitted();
        });
        toolClear.setOnClickListener(v -> {
            ActivityTracker.log("click", "drawing clear");
            board.clearBoard();
        });

        buildSwatches();
        board.setListener(this);
        board.setTextListener(this);
        setPenColor(DrawingBoardStorage.loadPenColor(context));
        setTextColor(DrawingBoardStorage.loadTextColor(context));
        board.setTextSize(DrawingBoardStorage.loadTextSize(context));
        setTool(TOOL_PEN, false);
        setupTextInput(container);
    }

    private void setupTextInput(View container) {
        textInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!updatingInput) board.setEditingText(s.toString());
            }
        });
        textInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            board.finishEditing();
            return true;
        });
        container.findViewById(R.id.text_done).setOnClickListener(v -> board.finishEditing());
        for (int i = 0; i < textSizes.length; i++) {
            final int level = i;
            textSizes[i].setOnClickListener(v -> {
                board.setTextSize(level);
                DrawingBoardStorage.saveTextSize(context, level);
                styleTextSizes(level);
            });
        }
        styleTextSizes(board.getTextSizeLevel());
    }

    private void styleTextSizes(int selected) {
        for (int i = 0; i < textSizes.length; i++) {
            textSizes[i].setAlpha(i == selected ? 1f : SIZE_UNSELECTED_ALPHA);
        }
    }

    /** Applies the colour to new texts (and the one being typed) and tints the T icon's fill. */
    public void setTextColor(int color) {
        board.setTextColor(color);
        Drawable d = toolText.getDrawable();
        if (d instanceof LayerDrawable) {
            Drawable fill = ((LayerDrawable) d).findDrawableByLayerId(R.id.text_fill);
            if (fill != null) fill.mutate().setTint(color);
        }
        toolText.invalidate();
        refreshSwatches();
    }

    // ---- DrawingBoardView.TextListener ----

    @Override
    public void onTextEditStart(String text, int color, int sizeLevel) {
        showPalette(false);
        setTextColor(color);
        styleTextSizes(sizeLevel);
        updatingInput = true;
        textInput.setText(text);
        textInput.setSelection(text.length());
        updatingInput = false;
        textBar.setVisibility(View.VISIBLE);
        textInput.setVisibility(View.VISIBLE);
        textInput.requestFocus();
        textInput.post(() -> {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    @Override
    public void onTextEditEnd() {
        textBar.setVisibility(View.GONE);
        textInput.clearFocus();
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
        textInput.setVisibility(View.GONE);
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
        refreshSwatches();
    }

    /** Ticks the swatch that matches the colour of the active tool (text colour for Text, else pen). */
    private void refreshSwatches() {
        int color = tool == TOOL_TEXT ? board.getTextColor() : board.getPenColor();
        for (int i = 0; i < swatches.size(); i++) styleSwatch(swatches.get(i), PALETTE[i] == color);
    }

    /** Switches the board to this slide's own drawing (saving the previous slide's first). No-op for the same slide. */
    public void setSlide(int id) {
        if (id == slideId) return;
        board.finishEditing();
        flush();
        slideId = id;
        board.importStrokes(DrawingBoardStorage.loadSlide(context, id));
    }

    /** Drops the board of a slide for good (slide deleted, or its image/video replaced). */
    public void discard(int id) {
        if (id == slideId) {
            handler.removeCallbacks(saveRunnable);
            dirty = false;
            board.importStrokes(null);
        }
        saver.execute(() -> DrawingBoardStorage.deleteSlide(context, id));
    }

    /** Call when the board starts to slide in: the very first time, label it "Drawing Board". */
    public void onBoardShown() {
        EventLog.boardShown(true);
        adoptLegacyDrawing();
        if (!DrawingBoardStorage.consumeIntro(context)) return;
        intro.animate().cancel();
        intro.setAlpha(1f);
        intro.setVisibility(View.VISIBLE);
        intro.animate().alpha(0f).setStartDelay(1800L).setDuration(500L)
                .withEndAction(() -> intro.setVisibility(View.GONE)).start();
    }

    /** The old single board drawing moves into the first slide that opens an empty board. */
    private void adoptLegacyDrawing() {
        if (slideId == SlideInkController.NO_SLIDE || !board.exportStrokes().isEmpty()) return;
        List<DrawingBoardView.Stroke> legacy = DrawingBoardStorage.adoptLegacy(context, slideId);
        if (!legacy.isEmpty()) board.importStrokes(legacy);
    }

    /** Call when the board slides away: closes the palette and saves right away. */
    public void onBoardHidden() {
        EventLog.boardShown(false);
        board.finishEditing();
        handler.removeCallbacks(hidePalette);
        palette.animate().cancel();
        palette.setVisibility(View.INVISIBLE);
        intro.animate().cancel();
        intro.setVisibility(View.GONE);
        flush();
    }

    /** Saves now if anything changed since the last save (used on hide and onStop). */
    public void flush() {
        handler.removeCallbacks(saveRunnable);
        if (!dirty) return;
        dirty = false;
        if (slideId != SlideInkController.NO_SLIDE) {
            DrawingBoardStorage.saveSlide(context, slideId, board.exportStrokes());
        }
    }

    // ---- palette ----

    private void buildSwatches() {
        int size = Math.round(SWATCH_DP * density);
        for (int color : PALETTE) {
            View sw = new View(context);
            palette.addView(sw, new FrameLayout.LayoutParams(size, size));
            sw.setOnClickListener(v -> {
                if (tool == TOOL_TEXT) {
                    setTextColor(color);
                    DrawingBoardStorage.saveTextColor(context, color);
                } else {
                    setPenColor(color);
                    DrawingBoardStorage.savePenColor(context, color);
                }
                // A pick mid-cascade: snap this swatch to its slot so the pop happens in place.
                sw.animate().cancel();
                layoutArc();
                sw.setAlpha(1f);
                bounce(sw);
                scheduleHidePalette(PALETTE_PICK_HIDE_MS);
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

    // INVISIBLE, never GONE: a GONE palette is not laid out, so layoutArc() would see a 0x0 size.
    private void showPalette(boolean show) {
        handler.removeCallbacks(hidePalette);
        palette.animate().cancel();
        if (!show) {
            if (palette.getVisibility() != View.VISIBLE) return;
            palette.animate().alpha(0f).setDuration(PALETTE_FADE_OUT_MS)
                    .withEndAction(() -> palette.setVisibility(View.INVISIBLE)).start();
            return;
        }
        layoutArc();
        palette.setAlpha(1f);
        palette.setVisibility(View.VISIBLE);
        flowSwatchesIn();
        scheduleHidePalette(PALETTE_HIDE_MS);
    }

    /** Swatches fly out from the Pen button to their arc slots, one after another. */
    private void flowSwatchesIn() {
        float penX = paletteAnchor.getLeft() + paletteAnchor.getWidth() / 2f;
        float penY = palette.getHeight();
        float half = SWATCH_DP * density / 2f;
        for (int i = 0; i < swatches.size(); i++) {
            View sw = swatches.get(i);
            float tx = sw.getX();
            float ty = sw.getY();
            sw.animate().cancel();
            sw.setX(penX - half);
            sw.setY(penY - half);
            sw.setScaleX(0f);
            sw.setScaleY(0f);
            sw.setAlpha(0f);
            sw.animate().x(tx).y(ty).scaleX(1f).scaleY(1f).alpha(1f)
                    .setStartDelay(i * SWATCH_STAGGER_MS).setDuration(SWATCH_FLY_MS)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
        }
    }

    private void scheduleHidePalette(long delayMs) {
        handler.removeCallbacks(hidePalette);
        handler.postDelayed(hidePalette, delayMs);
    }

    /** Quick pop on the tapped swatch. */
    private void bounce(View sw) {
        sw.animate().cancel();
        sw.animate().scaleX(1.4f).scaleY(1.4f).setDuration(110L)
                .withEndAction(() -> sw.animate().scaleX(1f).scaleY(1f).setDuration(140L)
                        .setInterpolator(new android.view.animation.OvershootInterpolator()).start())
                .start();
    }

    /** Fans the swatches out above the Pen button, bottom-anchored to the canvas. */
    private void layoutArc() {
        int w = palette.getWidth();
        int h = palette.getHeight();
        if (w <= 0 || h <= 0) return;
        float half = SWATCH_DP * density / 2f;
        float cx = paletteAnchor.getLeft() + paletteAnchor.getWidth() / 2f;
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

    private void setTool(int newTool, boolean animate) {
        tool = newTool;
        board.setEraserMode(newTool == TOOL_ERASER);
        board.setTextMode(newTool == TOOL_TEXT);
        scale(toolPen, newTool == TOOL_PEN ? ACTIVE_SCALE : 1f, animate);
        scale(toolEraser, newTool == TOOL_ERASER ? ACTIVE_SCALE : 1f, animate);
        scale(toolText, newTool == TOOL_TEXT ? ACTIVE_SCALE : 1f, animate);
        refreshSwatches();
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
        if (slideId == SlideInkController.NO_SLIDE) return;
        int id = slideId;
        List<DrawingBoardView.Stroke> snapshot = board.exportStrokes();
        saver.execute(() -> DrawingBoardStorage.saveSlide(context, id, snapshot));
    }

    @Override
    public void onHistoryChanged(boolean canUndo, boolean canRedo) {
        toolUndo.setEnabled(canUndo);
        toolUndo.setAlpha(canUndo ? 1f : DISABLED_ALPHA);
        toolRedo.setEnabled(canRedo);
        toolRedo.setAlpha(canRedo ? 1f : DISABLED_ALPHA);
    }
}
