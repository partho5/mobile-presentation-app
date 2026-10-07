package com.jovoc.facecampresentationrecorder.ui;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.ImageView;

import com.jovoc.facecampresentationrecorder.R;

/** Wires the drawing board's tool bar (Pen, Eraser, Undo, Redo, Clear) to its {@link DrawingBoardView}. */
public class DrawingBoardController implements DrawingBoardView.Listener {

    private static final float ACTIVE_SCALE = 2f;
    private static final long SCALE_ANIM_MS = 150L;
    private static final float DISABLED_ALPHA = 0.3f;

    private final DrawingBoardView board;
    private final ImageView toolPen;
    private final ImageView toolEraser;
    private final ImageView toolUndo;
    private final ImageView toolRedo;
    private final ImageView toolClear;

    public DrawingBoardController(View container) {
        board = container.findViewById(R.id.drawing_board_canvas);
        toolPen = container.findViewById(R.id.tool_pen);
        toolEraser = container.findViewById(R.id.tool_eraser);
        toolUndo = container.findViewById(R.id.tool_undo);
        toolRedo = container.findViewById(R.id.tool_redo);
        toolClear = container.findViewById(R.id.tool_clear);

        toolPen.setOnClickListener(v -> setEraser(false, true));
        toolEraser.setOnClickListener(v -> setEraser(true, true));
        toolUndo.setOnClickListener(v -> board.undo());
        toolRedo.setOnClickListener(v -> board.redo());
        toolClear.setOnClickListener(v -> board.clearBoard());

        board.setListener(this);
        setPenColor(board.getPenColor());
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
    }

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

    @Override
    public void onActionCommitted() {
        // Persistence hooks in here (Part 3).
    }

    @Override
    public void onHistoryChanged(boolean canUndo, boolean canRedo) {
        toolUndo.setEnabled(canUndo);
        toolUndo.setAlpha(canUndo ? 1f : DISABLED_ALPHA);
        toolRedo.setEnabled(canRedo);
        toolRedo.setAlpha(canRedo ? 1f : DISABLED_ALPHA);
    }
}
