package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.jovoc.facecampresentationrecorder.edit.EditProject.Range;
import com.jovoc.facecampresentationrecorder.edit.EditTimelineMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The editor's timeline: a strip of video thumbnails over the master's whole length, with deleted
 * stretches dimmed, a playhead, and two big trim handles on the segment under the playhead.
 * Dragging anywhere else scrubs. The view only draws and reports gestures; the editor screen
 * owns the {@code EditProject} and feeds the ranges back in. Times are master milliseconds.
 */
public class EditTimelineView extends View {

    public interface Listener {
        /** A finger went down to scrub or trim: pause playback. */
        void onGestureStart();

        /** The playhead was dragged to {@code ms} (not yet snapped to a kept stretch). */
        void onScrub(long ms);

        /** A trim handle is being dragged; {@code edgeMs} is the edge to preview. */
        void onTrimPreview(long edgeMs);

        /** A trim handle was let go; {@code ranges} is the full new list of kept ranges. */
        void onTrimCommit(List<Range> ranges);
    }

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_START = 1;
    private static final int HANDLE_END = 2;

    private final Paint stripPaint = new Paint();
    private final Paint thumbPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint splitPaint = new Paint();
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    private final float density;
    private final float handleW;
    private final float handleTouch;

    private long durationMs;
    private List<Range> ranges = Collections.emptyList();
    private Bitmap[] thumbs = new Bitmap[0];
    private long playheadMs;
    private Listener listener;

    // Trim drag
    private int dragHandle = HANDLE_NONE;
    private int dragIndex = -1;
    private boolean scrubbing;
    private List<Range> dragRanges;

    public EditTimelineView(Context context) {
        this(context, null);
    }

    public EditTimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        handleW = 22 * density;
        handleTouch = 36 * density;

        stripPaint.setColor(0xFF2C2C2C);
        dimPaint.setColor(0xCC000000);
        framePaint.setColor(0xFFFFC107);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3 * density);
        handlePaint.setColor(0xFFFFC107);
        gripPaint.setColor(0xFF3A2E00);
        splitPaint.setColor(0xFFFFFFFF);
        splitPaint.setStrokeWidth(2 * density);
        playheadPaint.setColor(0xFFFFFFFF);
        playheadPaint.setStrokeWidth(3 * density);
        setContentDescription(context.getString(
                com.jovoc.facecampresentationrecorder.R.string.editor_timeline_desc));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setDuration(long ms) {
        this.durationMs = Math.max(0, ms);
        invalidate();
    }

    /** The kept ranges to show (sorted, non-overlapping). Ignored while a handle is being dragged. */
    public void setRanges(List<Range> kept) {
        if (dragRanges != null) return;
        this.ranges = new ArrayList<>(kept);
        invalidate();
    }

    public void setThumbnails(Bitmap[] frames) {
        this.thumbs = frames == null ? new Bitmap[0] : frames;
        invalidate();
    }

    public void setPlayhead(long ms) {
        if (ms == playheadMs) return;
        playheadMs = ms;
        invalidate();
    }

    // ---------------------------------------------------------------- geometry

    private float trackLeft() {
        return handleW;
    }

    private float trackRight() {
        return getWidth() - handleW;
    }

    private float trackTop() {
        return 12 * density;
    }

    private float trackBottom() {
        return getHeight() - 12 * density;
    }

    private float xOf(long ms) {
        if (durationMs <= 0) return trackLeft();
        return trackLeft() + (trackRight() - trackLeft()) * ms / (float) durationMs;
    }

    private long msOf(float x) {
        float w = trackRight() - trackLeft();
        if (w <= 0 || durationMs <= 0) return 0;
        float f = (x - trackLeft()) / w;
        return (long) (Math.max(0f, Math.min(1f, f)) * durationMs);
    }

    private List<Range> shown() {
        return dragRanges != null ? dragRanges : ranges;
    }

    private int selectedIndex() {
        return dragRanges != null ? dragIndex : EditTimelineMath.indexAt(ranges, playheadMs);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        if (durationMs <= 0) return;
        float l = trackLeft(), r = trackRight(), t = trackTop(), b = trackBottom();
        c.drawRect(l, t, r, b, stripPaint);

        // Thumbnails, evenly spread over the whole master
        int n = thumbs.length;
        for (int i = 0; i < n; i++) {
            Bitmap bmp = thumbs[i];
            if (bmp == null || bmp.isRecycled()) continue;
            int x0 = Math.round(l + (r - l) * i / n);
            int x1 = Math.round(l + (r - l) * (i + 1) / n);
            // centre-crop the frame into its slot
            float slotAspect = (x1 - x0) / Math.max(1f, b - t);
            float bmpAspect = bmp.getWidth() / (float) bmp.getHeight();
            if (bmpAspect > slotAspect) {
                int w = Math.round(bmp.getHeight() * slotAspect);
                src.set((bmp.getWidth() - w) / 2, 0, (bmp.getWidth() + w) / 2, bmp.getHeight());
            } else {
                int h = Math.round(bmp.getWidth() / slotAspect);
                src.set(0, (bmp.getHeight() - h) / 2, bmp.getWidth(), (bmp.getHeight() + h) / 2);
            }
            dst.set(x0, Math.round(t), x1, Math.round(b));
            c.drawBitmap(bmp, src, dst, thumbPaint);
        }

        // Deleted stretches: dimmed
        List<Range> kept = shown();
        long cursor = 0;
        for (Range k : kept) {
            if (k.startMs > cursor) c.drawRect(xOf(cursor), t, xOf(k.startMs), b, dimPaint);
            cursor = Math.max(cursor, k.endMs);
        }
        if (cursor < durationMs) c.drawRect(xOf(cursor), t, r, b, dimPaint);

        // Split points between touching segments
        for (int i = 1; i < kept.size(); i++) {
            if (kept.get(i).startMs == kept.get(i - 1).endMs) {
                float x = xOf(kept.get(i).startMs);
                c.drawLine(x, t, x, b, splitPaint);
            }
        }

        // Selected segment: frame and handles
        int sel = selectedIndex();
        if (sel >= 0 && sel < kept.size()) {
            Range s = kept.get(sel);
            float xs = xOf(s.startMs), xe = xOf(s.endMs);
            c.drawRect(xs, t, xe, b, framePaint);
            rect.set(xs - handleW, t - 4 * density, xs, b + 4 * density);
            c.drawRoundRect(rect, 6 * density, 6 * density, handlePaint);
            drawGrip(c, xs - handleW / 2, (t + b) / 2);
            rect.set(xe, t - 4 * density, xe + handleW, b + 4 * density);
            c.drawRoundRect(rect, 6 * density, 6 * density, handlePaint);
            drawGrip(c, xe + handleW / 2, (t + b) / 2);
        }

        // Playhead
        float px = xOf(Math.max(0, Math.min(durationMs, playheadMs)));
        c.drawLine(px, 4 * density, px, getHeight() - 4 * density, playheadPaint);
        c.drawCircle(px, 6 * density, 6 * density, playheadPaint);
    }

    private void drawGrip(Canvas c, float cx, float cy) {
        rect.set(cx - 2 * density, cy - 12 * density, cx + 2 * density, cy + 12 * density);
        c.drawRoundRect(rect, 2 * density, 2 * density, gripPaint);
    }

    // ---------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (durationMs <= 0) return false;
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                if (listener != null) listener.onGestureStart();
                int handle = hitHandle(x);
                if (handle != HANDLE_NONE) {
                    dragHandle = handle;
                    dragIndex = selectedIndex();
                    dragRanges = new ArrayList<>(ranges);
                } else {
                    scrubbing = true;
                    if (listener != null) listener.onScrub(msOf(x));
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragRanges != null) {
                    Range old = dragRanges.get(dragIndex);
                    long ms = msOf(x);
                    List<Range> next = dragHandle == HANDLE_START
                            ? EditTimelineMath.resize(dragRanges, dragIndex, ms, old.endMs, durationMs)
                            : EditTimelineMath.resize(dragRanges, dragIndex, old.startMs, ms, durationMs);
                    dragRanges = next;
                    Range moved = next.get(dragIndex);
                    invalidate();
                    if (listener != null) {
                        listener.onTrimPreview(dragHandle == HANDLE_START
                                ? moved.startMs : Math.max(moved.startMs, moved.endMs - 1));
                    }
                } else if (scrubbing && listener != null) {
                    listener.onScrub(msOf(x));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                List<Range> result = dragRanges;
                boolean commit = result != null && e.getActionMasked() == MotionEvent.ACTION_UP;
                dragRanges = null;
                dragHandle = HANDLE_NONE;
                dragIndex = -1;
                scrubbing = false;
                if (commit) {
                    ranges = result;
                    if (listener != null) listener.onTrimCommit(result);
                }
                invalidate();
                return true;
            default:
                return true;
        }
    }

    /** Which handle of the selected segment is under {@code x}, with a generous touch area. */
    private int hitHandle(float x) {
        int sel = EditTimelineMath.indexAt(ranges, playheadMs);
        if (sel < 0) return HANDLE_NONE;
        Range s = ranges.get(sel);
        float xs = xOf(s.startMs), xe = xOf(s.endMs);
        float dStart = Math.abs(x - (xs - handleW / 2));
        float dEnd = Math.abs(x - (xe + handleW / 2));
        float reach = handleTouch / 2;
        if (dStart <= reach && dStart <= dEnd) return HANDLE_START;
        if (dEnd <= reach) return HANDLE_END;
        return HANDLE_NONE;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
