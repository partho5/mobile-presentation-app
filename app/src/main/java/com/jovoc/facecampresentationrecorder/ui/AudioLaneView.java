package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The editor's audio lane: one tall row per sound (the recording's own audio first, then each
 * added track) on the edited result's timeline, drawn as a loudness profile so the user sees where
 * sound is. Tap a row to select it; drag an added track sideways to move it. The view only draws
 * and reports gestures; the editor screen owns the {@code EditProject}.
 */
public class AudioLaneView extends View {

    public interface Listener {
        /** A row was tapped or touched; {@code track} is ORIGINAL_TRACK or the added index. */
        void onTrackSelected(int track);

        /** An added track was dragged and let go; {@code startMs} is its new start on the result. */
        void onTrackMoved(int track, long startMs);
    }

    /** One row. {@code columns} is the loudness over the clip (null = not known yet). */
    public static final class Row {
        public final int track;
        public final String label;
        public final float[] columns;
        public final long startMs;
        public final long lengthMs;
        public final boolean dimmed;

        public Row(int track, String label, float[] columns, long startMs, long lengthMs,
                   boolean dimmed) {
            this.track = track;
            this.label = label;
            this.columns = columns;
            this.startMs = startMs;
            this.lengthMs = lengthMs;
            this.dimmed = dimmed;
        }
    }

    private static final int ORIGINAL_COLOR = 0xFF5C7CFA;
    private static final int ADDED_COLOR = 0xFF2BB5A0;

    private final Paint rowBgPaint = new Paint();
    private final Paint clipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float density;
    private final float rowH;
    private final float gap;
    private final float pad;
    private final float touchSlop;

    private List<Row> rows = Collections.emptyList();
    private long outputDurationMs;
    private int selected = VideoEditorViewModel.NO_TRACK;
    private Listener listener;

    // Drag of an added track
    private int dragRow = -1;
    private float downX;
    private float downY;
    private long dragStartMs;
    private long dragNowMs;
    private boolean dragging;

    public AudioLaneView(Context context) {
        this(context, null);
    }

    public AudioLaneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        rowH = 52 * density;
        gap = 6 * density;
        pad = 22 * density;
        touchSlop = 8 * density;

        rowBgPaint.setColor(0xFF242424);
        framePaint.setColor(0xFFFFC107);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3 * density);
        wavePaint.setColor(0xFFFFFFFF);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(12 * density);
        setMinimumHeight((int) rowH);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setRows(List<Row> newRows, long outputDurationMs, int selectedTrack) {
        if (dragging) return;
        this.rows = new ArrayList<>(newRows);
        this.outputDurationMs = Math.max(0, outputDurationMs);
        this.selected = selectedTrack;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = (int) (Math.max(1, rows.size()) * (rowH + gap));
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthSpec), h);
    }

    private float left() {
        return pad;
    }

    private float right() {
        return getWidth() - pad;
    }

    private float msToX(long ms) {
        if (outputDurationMs <= 0) return left();
        return left() + (right() - left()) * ms / (float) outputDurationMs;
    }

    private long dxToMs(float dx) {
        float w = right() - left();
        return w <= 0 ? 0 : (long) (dx * outputDurationMs / w);
    }

    private float rowTop(int i) {
        return i * (rowH + gap);
    }

    private int rowAt(float y) {
        int i = (int) (y / (rowH + gap));
        return i >= 0 && i < rows.size() ? i : -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            float top = rowTop(i);
            rect.set(left(), top, right(), top + rowH);
            canvas.drawRoundRect(rect, 6 * density, 6 * density, rowBgPaint);

            long start = (dragging && i == dragRow) ? dragNowMs : row.startMs;
            float x0 = Math.max(left(), msToX(start));
            float x1 = Math.min(right(), msToX(start + row.lengthMs));
            if (x1 > x0) {
                rect.set(x0, top, x1, top + rowH);
                clipPaint.setColor(row.track == VideoEditorViewModel.ORIGINAL_TRACK
                        ? ORIGINAL_COLOR : ADDED_COLOR);
                clipPaint.setAlpha(row.dimmed ? 70 : 255);
                canvas.drawRoundRect(rect, 6 * density, 6 * density, clipPaint);
                drawWave(canvas, row, start, x0, x1, top);
                textPaint.setAlpha(row.dimmed ? 140 : 255);
                canvas.save();
                canvas.clipRect(x0, top, x1, top + rowH);
                canvas.drawText(row.label, x0 + 8 * density, top + 16 * density, textPaint);
                canvas.restore();
            }
            if (row.track == selected) {
                rect.set(left(), top, right(), top + rowH);
                canvas.drawRoundRect(rect, 6 * density, 6 * density, framePaint);
            }
        }
    }

    private void drawWave(Canvas canvas, Row row, long startMs, float x0, float x1, float top) {
        float mid = top + rowH * 0.62f;
        float maxHalf = rowH * 0.32f;
        wavePaint.setAlpha(row.dimmed ? 60 : 200);
        float[] cols = row.columns;
        float step = 3 * density;
        wavePaint.setStrokeWidth(2 * density);
        if (cols == null || cols.length == 0) {
            // Not computed (yet): a plain thin bar still shows where the clip sits
            canvas.drawLine(x0, mid, x1, mid, wavePaint);
            return;
        }
        float clipW = Math.max(1, msToX(row.lengthMs) - msToX(0));
        float clipX = msToX(startMs);
        for (float x = x0 + step / 2; x < x1; x += step) {
            // Column under x, measured from the clip's own start so the profile moves with it
            float frac = (x - clipX) / clipW;
            int c = (int) Math.max(0, Math.min(cols.length - 1, frac * cols.length));
            float h = Math.max(1.5f * density, cols[c] * maxHalf);
            canvas.drawLine(x, mid - h, x, mid + h, wavePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int i = rowAt(e.getY());
                if (i < 0) return false;
                Row row = rows.get(i);
                downX = e.getX();
                downY = e.getY();
                dragRow = row.track >= 0 ? i : -1;
                dragStartMs = row.startMs;
                dragNowMs = row.startMs;
                dragging = false;
                if (listener != null) listener.onTrackSelected(row.track);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragRow < 0) return true;
                float dx = e.getX() - downX;
                if (!dragging) {
                    if (Math.abs(dx) < touchSlop || Math.abs(dx) < Math.abs(e.getY() - downY)) return true;
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                dragNowMs = Math.max(0, Math.min(outputDurationMs, dragStartMs + dxToMs(dx)));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging && dragRow >= 0) {
                    int track = rows.get(dragRow).track;
                    long to = dragNowMs;
                    dragging = false;
                    dragRow = -1;
                    if (listener != null) listener.onTrackMoved(track, to);
                    invalidate();
                }
                dragging = false;
                dragRow = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                dragRow = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
