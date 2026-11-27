package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;
import com.jovoc.facecampresentationrecorder.tracker.Meta;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.entitlements.FeatureSnapshot;
import com.jovoc.facecampresentationrecorder.util.SlideInkStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pen marks on the slide. Owns the ink layer (a {@link DrawingBoardView} that MainActivity keeps
 * fitted over the current slide, so its 0..1 points are slide-relative), the pen toggle and the small
 * Undo / colour swatches / Clear row, and loads/saves the marks of whichever slide is current.
 */
public class SlideInkController implements DrawingBoardView.Listener {

    public interface Listener {
        void onPenModeChanged(boolean penMode);
    }

    public static final int NO_SLIDE = -1;

    // Same colours, same order as the drawing board's palette.
    private static final int[] PALETTE = {
            0xFFFFFFFF, // white
            0xFF00E5FF, // cyan
            0xFFFFB300, // amber
            0xFF00E676, // green
            0xFFFF1744, // red
            0xFFD500F9, // purple
    };
    private static final float SWATCH_CELL_DP = 34f;
    private static final float SWATCH_INSET_DP = 4f;
    private static final long SAVE_DEBOUNCE_MS = 1000L;
    private static final float DISABLED_ALPHA = 0.3f;
    private static final long TOOLS_IN_MS = 220L;
    private static final long TOOLS_OUT_MS = 160L;
    private static final float TOOLS_OFFSET_DP = 10f;
    private static final float TOOLS_START_SCALE = 0.94f;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // One thread for every write/delete, so an older snapshot can never land after a newer one.
    private final ExecutorService saver = Executors.newSingleThreadExecutor();
    private final float density;

    private final DrawingBoardView ink;
    private final ImageButton btnPen;
    private final ToolIconAnimator penIcon;
    private final View tools;
    private final View toolUndo;
    private final LinearLayout palette;
    private final List<View> swatches = new ArrayList<>();
    private final View toolClear;

    private Listener listener;
    private int slideId = NO_SLIDE;
    private boolean penMode = false;
    private boolean dirty = false;
    private final Runnable saveRunnable = this::saveInBackground;

    public SlideInkController(View root) {
        context = root.getContext().getApplicationContext();
        density = root.getResources().getDisplayMetrics().density;
        ink = root.findViewById(R.id.slide_ink_canvas);
        btnPen = root.findViewById(R.id.btn_slide_pen);
        tools = root.findViewById(R.id.slide_ink_tools);
        toolUndo = root.findViewById(R.id.slide_ink_undo);
        palette = root.findViewById(R.id.slide_ink_palette);
        toolClear = root.findViewById(R.id.slide_ink_clear);
        penIcon = new ToolIconAnimator(btnPen);

        buildSwatches();
        ink.setInputEnabled(false);
        ink.setListener(this);
        setPenColor(SlideInkStorage.loadPenColor(context));

        btnPen.setOnClickListener(v -> {
            if (!penMode && !FeatureSnapshot.allows(Feature.SLIDE_INK)) {
                // No sheet (it could end up in the video); the pen is free, this only guards a plan change.
                Gate.lockedWhileRecording(context, Feature.SLIDE_INK);
                return;
            }
            ActivityTracker.log("click", "ink pen", Meta.of("on", !penMode));
            setPenMode(!penMode);
        });
        toolUndo.setOnClickListener(v -> {
            ActivityTracker.log("click", "ink undo");
            ink.undo();
            onActionCommitted();
        });
        toolClear.setOnClickListener(v -> {
            ActivityTracker.log("click", "ink clear");
            ink.clearBoard();
        });
        stylePenButton();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public DrawingBoardView getInkView() {
        return ink;
    }

    public boolean isPenMode() {
        return penMode;
    }

    public void setPenMode(boolean on) {
        if (penMode == on) return;
        // Whatever calls this: the plan this recording started with decides (never mid-recording).
        if (on && !FeatureSnapshot.allows(Feature.SLIDE_INK)) return;
        penMode = on;
        ink.setInputEnabled(on);
        showTools(on);
        stylePenButton();
        if (!on) flush();
        if (listener != null) listener.onPenModeChanged(on);
    }

    /** Shows the marks of this slide (saving the previous slide's first). No-op for the same slide. */
    public void setSlide(int id) {
        if (id == slideId) return;
        flush();
        slideId = id;
        ink.animate().cancel();
        ink.setAlpha(1f);
        ink.importStrokes(id == NO_SLIDE ? null : SlideInkStorage.load(context, id));
    }

    /** Drops the marks of a slide for good (slide deleted, or its image/video replaced). */
    public void discard(int id) {
        if (id == slideId) {
            handler.removeCallbacks(saveRunnable);
            dirty = false;
            ink.importStrokes(null);
        }
        saver.execute(() -> SlideInkStorage.delete(context, id));
    }

    /** Saves now if anything changed since the last save (pen off, slide change, onStop). */
    public void flush() {
        handler.removeCallbacks(saveRunnable);
        saveInBackground();
    }

    // ---- tools ----

    private void buildSwatches() {
        int cell = Math.round(SWATCH_CELL_DP * density);
        for (int color : PALETTE) {
            View sw = new View(context);
            sw.setContentDescription("Pen colour");
            sw.setOnClickListener(v -> {
                setPenColor(color);
                SlideInkStorage.savePenColor(context, color);
            });
            palette.addView(sw, new LinearLayout.LayoutParams(cell, cell));
            swatches.add(sw);
        }
    }

    private void setPenColor(int color) {
        ink.setPenColor(color);
        for (int i = 0; i < swatches.size(); i++) styleSwatch(swatches.get(i), PALETTE[i] == color);
        stylePenButton();
    }

    /** Same look as the drawing board's swatches: the chosen one gets a thick white ring. */
    private void styleSwatch(View sw, boolean selected) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(PALETTE[swatches.indexOf(sw)]);
        dot.setStroke(Math.round((selected ? 3f : 1f) * density), selected ? 0xFFFFFFFF : 0xFF808080);
        int inset = Math.round(SWATCH_INSET_DP * density);
        sw.setBackground(new InsetDrawable(dot, inset));
    }

    /** Idle: plain white icon. Pen mode: lit up in the active colour (animated both ways). */
    private void stylePenButton() {
        penIcon.setActive(penMode, ink.getPenColor(), true);
    }

    /** Undo / swatches / Clear row: rises and fades in with pen mode, sinks and fades out after it. */
    private void showTools(boolean show) {
        tools.animate().cancel();
        float offset = TOOLS_OFFSET_DP * density;
        if (show) {
            if (tools.getVisibility() != View.VISIBLE) {
                tools.setAlpha(0f);
                tools.setTranslationY(offset);
                tools.setScaleX(TOOLS_START_SCALE);
                tools.setScaleY(TOOLS_START_SCALE);
                tools.setVisibility(View.VISIBLE);
            }
            tools.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(TOOLS_IN_MS).setInterpolator(new DecelerateInterpolator()).start();
        } else if (tools.getVisibility() == View.VISIBLE && tools.isShown()) {
            tools.animate().alpha(0f).translationY(offset)
                    .scaleX(TOOLS_START_SCALE).scaleY(TOOLS_START_SCALE)
                    .setDuration(TOOLS_OUT_MS).setInterpolator(new AccelerateInterpolator())
                    .withEndAction(this::resetToolsHidden).start();
        } else {
            resetToolsHidden();
        }
    }

    private void resetToolsHidden() {
        tools.setVisibility(View.GONE);
        tools.setAlpha(1f);
        tools.setTranslationY(0f);
        tools.setScaleX(1f);
        tools.setScaleY(1f);
    }

    // ---- saving ----

    private void saveInBackground() {
        if (!dirty || slideId == NO_SLIDE) {
            dirty = false;
            return;
        }
        dirty = false;
        int id = slideId;
        List<DrawingBoardView.Stroke> snapshot = ink.exportStrokes();
        saver.execute(() -> SlideInkStorage.save(context, id, snapshot));
    }

    // ---- DrawingBoardView.Listener ----

    @Override
    public void onActionCommitted() {
        dirty = true;
        handler.removeCallbacks(saveRunnable);
        handler.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS);
    }

    @Override
    public void onHistoryChanged(boolean canUndo, boolean canRedo) {
        toolUndo.setEnabled(canUndo);
        toolUndo.setAlpha(canUndo ? 1f : DISABLED_ALPHA);
    }
}
