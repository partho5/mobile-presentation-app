package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Transparent drawing layer (the black comes from the view background).
 *
 * The drawing is an action list (stroke / erase / clear) with an undo cursor. Ink lives in an
 * offscreen bitmap that is replayed from the list on undo and resize; erasing punches holes with
 * PorterDuff.CLEAR so it never paints black over strokes. Points are stored normalised (0..1).
 */
public class DrawingBoardView extends View {

    public static final int TYPE_STROKE = 0;
    public static final int TYPE_ERASE = 1;
    public static final int TYPE_CLEAR = 2;

    /** How many actions can be undone. */
    public static final int MAX_UNDO = 200;

    private static final float PEN_WIDTH_DP = 4f;
    private static final float ERASER_WIDTH_DP = 40f;
    private static final float MIN_POINT_GAP_PX = 1.5f;

    public interface Listener {
        /** A stroke, erase or clear was finished (not undo/redo). */
        void onActionCommitted();

        void onHistoryChanged(boolean canUndo, boolean canRedo);
    }

    /** Immutable snapshot of one action for persistence: points are x,y pairs in 0..1. */
    public static final class Stroke {
        public final int type;
        public final int color;
        public final float[] points;

        public Stroke(int type, int color, float[] points) {
            this.type = type;
            this.color = color;
            this.points = points;
        }
    }

    private static final class Action {
        final int type;
        final int color;
        float[] pts = new float[32];
        int n;

        Action(int type, int color) {
            this.type = type;
            this.color = color;
        }

        void add(float nx, float ny) {
            if (n * 2 + 2 > pts.length) pts = Arrays.copyOf(pts, pts.length * 2);
            pts[n * 2] = nx;
            pts[n * 2 + 1] = ny;
            n++;
        }
    }

    private final List<Action> actions = new ArrayList<>();
    private int cursor = 0;      // actions[0..cursor) are applied
    private int undoFloor = 0;   // actions below this index can no longer be undone

    private Action current;
    private int activePointerId = -1;

    private Bitmap bitmap;
    private Canvas bitmapCanvas;
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final float penWidth;
    private final float eraserWidth;
    private int penColor = Color.WHITE;
    private boolean eraserMode = false;
    private Listener listener;

    public DrawingBoardView(Context context) {
        this(context, null);
    }

    public DrawingBoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        penWidth = PEN_WIDTH_DP * density;
        eraserWidth = ERASER_WIDTH_DP * density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    // ---- public API ----

    public void setListener(Listener listener) {
        this.listener = listener;
        notifyHistory();
    }

    public void setPenColor(int color) {
        penColor = color;
    }

    public int getPenColor() {
        return penColor;
    }

    public void setEraserMode(boolean eraser) {
        eraserMode = eraser;
    }

    public boolean canUndo() {
        return cursor > undoFloor;
    }

    public boolean canRedo() {
        return cursor < actions.size();
    }

    public void undo() {
        if (!canUndo()) return;
        cursor--;
        replay();
        notifyHistory();
    }

    public void redo() {
        if (!canRedo()) return;
        Action a = actions.get(cursor);
        cursor++;
        drawAction(a);
        invalidate();
        notifyHistory();
    }

    /** Wipes the board as one undoable action. Does nothing when there is no ink to wipe. */
    public void clearBoard() {
        if (!hasInk()) return;
        commit(new Action(TYPE_CLEAR, 0));
        if (bitmap != null) bitmap.eraseColor(Color.TRANSPARENT);
        invalidate();
    }

    /** Visible actions (everything after the last clear, up to the undo cursor). */
    public List<Stroke> exportStrokes() {
        List<Stroke> out = new ArrayList<>();
        for (int i = firstVisibleIndex(); i < cursor; i++) {
            Action a = actions.get(i);
            out.add(new Stroke(a.type, a.color, Arrays.copyOf(a.pts, a.n * 2)));
        }
        return out;
    }

    /** Replaces the drawing. Undo and redo start empty. */
    public void importStrokes(List<Stroke> strokes) {
        actions.clear();
        if (strokes != null) {
            for (Stroke s : strokes) {
                if (s == null || s.points == null) continue;
                Action a = new Action(s.type, s.color);
                a.pts = Arrays.copyOf(s.points, Math.max(2, s.points.length));
                a.n = s.points.length / 2;
                actions.add(a);
            }
        }
        cursor = actions.size();
        undoFloor = cursor;
        replay();
        notifyHistory();
    }

    // ---- touch ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (bitmap == null) return true;
                activePointerId = e.getPointerId(0);
                beginAction(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (current == null) return true;
                int idx = e.findPointerIndex(activePointerId);
                if (idx < 0) return true;
                for (int h = 0; h < e.getHistorySize(); h++) {
                    addPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h));
                }
                addPoint(e.getX(idx), e.getY(idx));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                // Only the finger that started the stroke matters; other fingers never draw.
                if (current != null && e.getPointerId(e.getActionIndex()) == activePointerId) {
                    endAction();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (current != null) endAction();
                return true;
            default:
                return true;
        }
    }

    private void beginAction(float x, float y) {
        current = new Action(eraserMode ? TYPE_ERASE : TYPE_STROKE, penColor);
        current.add(norm(x, getWidth()), norm(y, getHeight()));
        // A single tap leaves a dot / erased spot.
        drawDot(current);
        invalidate();
    }

    private void addPoint(float x, float y) {
        x = Math.max(0f, Math.min(x, getWidth()));
        y = Math.max(0f, Math.min(y, getHeight()));
        int last = current.n - 1;
        float dx = x - current.pts[last * 2] * getWidth();
        float dy = y - current.pts[last * 2 + 1] * getHeight();
        if (dx * dx + dy * dy < MIN_POINT_GAP_PX * MIN_POINT_GAP_PX) return;
        current.add(norm(x, getWidth()), norm(y, getHeight()));
        drawLatestSegment(current);
    }

    private void endAction() {
        Action a = current;
        current = null;
        activePointerId = -1;
        if (a.n >= 2) {
            // Close the smoothed curve with a straight run to the last point.
            preparePaint(a);
            float w = getWidth(), h = getHeight();
            path.reset();
            path.moveTo(mid(a.pts[(a.n - 2) * 2], a.pts[(a.n - 1) * 2]) * w,
                    mid(a.pts[(a.n - 2) * 2 + 1], a.pts[(a.n - 1) * 2 + 1]) * h);
            path.lineTo(a.pts[(a.n - 1) * 2] * w, a.pts[(a.n - 1) * 2 + 1] * h);
            bitmapCanvas.drawPath(path, strokePaint);
        }
        commit(a);
        invalidate();
    }

    // ---- history ----

    private void commit(Action a) {
        if (cursor < actions.size()) actions.subList(cursor, actions.size()).clear();
        actions.add(a);
        cursor = actions.size();
        undoFloor = Math.max(undoFloor, cursor - MAX_UNDO);
        compact();
        notifyHistory();
        if (listener != null) listener.onActionCommitted();
    }

    /** Drops actions that can no longer be seen or undone (everything before a clear below the floor). */
    private void compact() {
        int lastClear = -1;
        for (int i = 0; i < undoFloor; i++) {
            if (actions.get(i).type == TYPE_CLEAR) lastClear = i;
        }
        if (lastClear < 0) return;
        int drop = lastClear + 1;
        actions.subList(0, drop).clear();
        cursor -= drop;
        undoFloor -= drop;
    }

    private int firstVisibleIndex() {
        for (int i = cursor - 1; i >= 0; i--) {
            if (actions.get(i).type == TYPE_CLEAR) return i + 1;
        }
        return 0;
    }

    private boolean hasInk() {
        for (int i = firstVisibleIndex(); i < cursor; i++) {
            if (actions.get(i).type == TYPE_STROKE) return true;
        }
        return false;
    }

    private void notifyHistory() {
        if (listener != null) listener.onHistoryChanged(canUndo(), canRedo());
    }

    // ---- rendering ----

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (bitmap != null) bitmap.recycle();
        if (w > 0 && h > 0) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmapCanvas = new Canvas(bitmap);
            replay();
        } else {
            bitmap = null;
            bitmapCanvas = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap != null) canvas.drawBitmap(bitmap, 0f, 0f, null);
    }

    private void replay() {
        if (bitmap == null) return;
        bitmap.eraseColor(Color.TRANSPARENT);
        for (int i = firstVisibleIndex(); i < cursor; i++) drawAction(actions.get(i));
        invalidate();
    }

    private void drawAction(Action a) {
        if (bitmap == null) return;
        if (a.type == TYPE_CLEAR) {
            bitmap.eraseColor(Color.TRANSPARENT);
            return;
        }
        if (a.n == 1) {
            drawDot(a);
            return;
        }
        preparePaint(a);
        float w = getWidth(), h = getHeight();
        path.reset();
        path.moveTo(a.pts[0] * w, a.pts[1] * h);
        for (int i = 1; i < a.n; i++) {
            float cx = a.pts[(i - 1) * 2], cy = a.pts[(i - 1) * 2 + 1];
            path.quadTo(cx * w, cy * h,
                    mid(cx, a.pts[i * 2]) * w, mid(cy, a.pts[i * 2 + 1]) * h);
        }
        path.lineTo(a.pts[(a.n - 1) * 2] * w, a.pts[(a.n - 1) * 2 + 1] * h);
        bitmapCanvas.drawPath(path, strokePaint);
    }

    /** Draws just the newest smoothed segment of the stroke in progress. */
    private void drawLatestSegment(Action a) {
        if (a.n < 2) return;
        preparePaint(a);
        float w = getWidth(), h = getHeight();
        int n = a.n;
        float cx = a.pts[(n - 2) * 2], cy = a.pts[(n - 2) * 2 + 1];
        float sx = n == 2 ? cx : mid(a.pts[(n - 3) * 2], cx);
        float sy = n == 2 ? cy : mid(a.pts[(n - 3) * 2 + 1], cy);
        path.reset();
        path.moveTo(sx * w, sy * h);
        path.quadTo(cx * w, cy * h,
                mid(cx, a.pts[(n - 1) * 2]) * w, mid(cy, a.pts[(n - 1) * 2 + 1]) * h);
        bitmapCanvas.drawPath(path, strokePaint);
    }

    private void drawDot(Action a) {
        if (bitmapCanvas == null) return;
        if (a.type == TYPE_ERASE) {
            dotPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            dotPaint.setColor(Color.BLACK);
        } else {
            dotPaint.setXfermode(null);
            dotPaint.setColor(a.color);
        }
        float r = (a.type == TYPE_ERASE ? eraserWidth : penWidth) / 2f;
        bitmapCanvas.drawCircle(a.pts[0] * getWidth(), a.pts[1] * getHeight(), r, dotPaint);
    }

    private void preparePaint(Action a) {
        if (a.type == TYPE_ERASE) {
            strokePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            strokePaint.setColor(Color.BLACK);
            strokePaint.setStrokeWidth(eraserWidth);
        } else {
            strokePaint.setXfermode(null);
            strokePaint.setColor(a.color);
            strokePaint.setStrokeWidth(penWidth);
        }
    }

    private static float mid(float a, float b) {
        return (a + b) / 2f;
    }

    private static float norm(float v, int size) {
        return size > 0 ? v / size : 0f;
    }
}
