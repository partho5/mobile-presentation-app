package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Transparent drawing layer (the black comes from the view background).
 *
 * The drawing is an action list (stroke / erase / clear) with an undo cursor. Ink lives in an
 * offscreen bitmap that is replayed from the list on undo and resize; erasing punches holes with
 * PorterDuff.CLEAR so it never paints black over strokes. Points are stored normalised (0..1).
 *
 * Text items live beside the ink: each add / move / delete / edit is one TYPE_TEXTS action holding the
 * whole list of texts, so undo, redo and clear treat them like strokes. They are drawn on top of the
 * ink in onDraw (the eraser does not touch them).
 */
public class DrawingBoardView extends View {

    public static final int TYPE_STROKE = 0;
    public static final int TYPE_ERASE = 1;
    public static final int TYPE_CLEAR = 2;
    /** A snapshot of all text items (the latest one in the visible history is what is shown). */
    public static final int TYPE_TEXTS = 3;

    public static final int TEXT_SIZE_SMALL = 0;
    public static final int TEXT_SIZE_MEDIUM = 1;
    public static final int TEXT_SIZE_LARGE = 2;

    /** How many actions can be undone. */
    public static final int MAX_UNDO = 200;

    private static final float PEN_WIDTH_DP = 4f;
    private static final float ERASER_WIDTH_DP = 40f;
    private static final float MIN_POINT_GAP_PX = 1.5f;
    /** Text height as a share of the board width, per size level. */
    private static final float[] TEXT_SIZE_FRACTION = {0.05f, 0.08f, 0.12f};
    private static final float TEXT_MAX_WIDTH_FRACTION = 0.92f;
    private static final float TEXT_HIT_PAD_DP = 12f;
    private static final float TRASH_RADIUS_DP = 28f;
    private static final float TRASH_BOTTOM_DP = 44f;
    private static final float TRASH_HIT_DP = 56f;
    private static final long CARET_BLINK_MS = 500L;

    public interface Listener {
        /** A stroke, erase or clear was finished (not undo/redo). */
        void onActionCommitted();

        void onHistoryChanged(boolean canUndo, boolean canRedo);
    }

    /** Talks to the UI that owns the keyboard while a text is being typed. */
    public interface TextListener {
        void onTextEditStart(String text, int color, int sizeLevel);

        void onTextEditEnd();
    }

    /** One text overlay: x,y is its centre in 0..1, size is a TEXT_SIZE_* level. */
    public static final class TextItem {
        public String text;
        public float x;
        public float y;
        public int color;
        public int size;

        public TextItem(String text, float x, float y, int color, int size) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.color = color;
            this.size = size;
        }

        TextItem copy() {
            return new TextItem(text, x, y, color, size);
        }
    }

    /** Immutable snapshot of one action for persistence: points are x,y pairs in 0..1. */
    public static final class Stroke {
        public final int type;
        public final int color;
        public final float[] points;
        /** Only for TYPE_TEXTS. */
        public final List<TextItem> texts;

        public Stroke(int type, int color, float[] points) {
            this(type, color, points, null);
        }

        public Stroke(int type, int color, float[] points, List<TextItem> texts) {
            this.type = type;
            this.color = color;
            this.points = points;
            this.texts = texts;
        }
    }

    private static final class Action {
        final int type;
        final int color;
        float[] pts = new float[32];
        int n;
        List<TextItem> items; // TYPE_TEXTS only

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
    private boolean inputEnabled = true;
    private Listener listener;

    // text
    private List<TextItem> texts = new ArrayList<>();   // what the cursor shows (live copy)
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float touchSlop;
    private final float textHitPad;
    private boolean textMode = false;
    private int textColor = Color.WHITE;
    private int textSize = TEXT_SIZE_MEDIUM;
    private TextListener textListener;
    private TextItem editing;          // the text being typed; not in `texts` meanwhile
    private TextItem editingOrig;      // its state before this edit (null for a new text)
    private int editingIndex;
    private TextItem dragging;
    private boolean textGesture;
    private boolean dragMoved;
    private boolean overTrash;
    private float downX, downY, dragDx, dragDy, dragStartX, dragStartY;

    public DrawingBoardView(Context context) {
        this(context, null);
    }

    public DrawingBoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        textHitPad = TEXT_HIT_PAD_DP * density;
        textPaint.setTextAlign(Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        trashPaint.setStrokeCap(Paint.Cap.ROUND);
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

    public boolean isEraserMode() {
        return eraserMode;
    }

    /** When off the view ignores touches entirely, so they fall through to whatever is below it. */
    public void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        if (!enabled) {
            finishEditing();
            cancelTextGesture();
            if (current != null) endAction();
        }
    }

    // ---- text API ----

    public void setTextListener(TextListener l) {
        textListener = l;
    }

    /** In text mode a tap adds a text (or edits the one under the finger) and a drag moves one. */
    public void setTextMode(boolean on) {
        if (textMode == on) return;
        if (!on) {
            finishEditing();
            cancelTextGesture();
        }
        textMode = on;
    }

    /** The colour of new texts; also recolours the text being typed. */
    public void setTextColor(int color) {
        textColor = color;
        if (editing != null) {
            editing.color = color;
            invalidate();
        }
    }

    public int getTextColor() {
        return textColor;
    }

    /** The size of new texts (TEXT_SIZE_*); also resizes the text being typed. */
    public void setTextSize(int level) {
        textSize = Math.max(TEXT_SIZE_SMALL, Math.min(TEXT_SIZE_LARGE, level));
        if (editing != null) {
            editing.size = textSize;
            invalidate();
        }
    }

    public int getTextSizeLevel() {
        return textSize;
    }

    /** Keeps the text on the board in step with what the keyboard field holds. */
    public void setEditingText(String s) {
        if (editing == null) return;
        editing.text = s;
        invalidate();
    }

    public boolean isEditingText() {
        return editing != null;
    }

    /** Ends typing: keeps the text (one undo step if something changed) or drops it when empty. */
    public void finishEditing() {
        if (editing == null) return;
        TextItem e = editing;
        editing = null;
        boolean empty = e.text.trim().isEmpty();
        boolean changed;
        if (editingOrig == null) {
            changed = !empty;
        } else {
            changed = empty || !e.text.equals(editingOrig.text)
                    || e.color != editingOrig.color || e.size != editingOrig.size;
        }
        if (!empty) {
            if (editingOrig != null) texts.add(Math.min(editingIndex, texts.size()), e);
            else texts.add(e);
        }
        editingOrig = null;
        if (changed) commitTexts();
        invalidate();
        if (textListener != null) textListener.onTextEditEnd();
    }

    public boolean canUndo() {
        return cursor > undoFloor;
    }

    public boolean canRedo() {
        return cursor < actions.size();
    }

    public void undo() {
        finishEditing();
        if (!canUndo()) return;
        cursor--;
        recomputeTexts();
        replay();
        notifyHistory();
    }

    public void redo() {
        finishEditing();
        if (!canRedo()) return;
        Action a = actions.get(cursor);
        cursor++;
        drawAction(a);
        recomputeTexts();
        invalidate();
        notifyHistory();
    }

    /** Wipes the board as one undoable action. Does nothing when there is no ink to wipe. */
    public void clearBoard() {
        finishEditing();
        if (!hasInk()) return;
        commit(new Action(TYPE_CLEAR, 0));
        recomputeTexts();
        if (bitmap != null) bitmap.eraseColor(Color.TRANSPARENT);
        invalidate();
    }

    /** Visible actions (everything after the last clear, up to the undo cursor). */
    public List<Stroke> exportStrokes() {
        List<Stroke> out = new ArrayList<>();
        for (int i = firstVisibleIndex(); i < cursor; i++) {
            Action a = actions.get(i);
            if (a.type == TYPE_TEXTS) continue;
            out.add(new Stroke(a.type, a.color, Arrays.copyOf(a.pts, a.n * 2)));
        }
        if (!texts.isEmpty()) out.add(new Stroke(TYPE_TEXTS, 0, new float[0], copyTexts(texts)));
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
                if (s.type == TYPE_TEXTS) a.items = s.texts == null ? new ArrayList<>() : copyTexts(s.texts);
                actions.add(a);
            }
        }
        cursor = actions.size();
        undoFloor = cursor;
        editing = null;
        editingOrig = null;
        recomputeTexts();
        replay();
        notifyHistory();
    }

    // ---- touch ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!inputEnabled) return false;
        if (textMode) return onTextTouch(e);
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

    // ---- text touch ----

    private boolean onTextTouch(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (editing != null) {
                    // A tap anywhere while typing is "done".
                    finishEditing();
                    textGesture = false;
                    return true;
                }
                textGesture = true;
                dragMoved = false;
                overTrash = false;
                downX = x;
                downY = y;
                dragging = hitText(x, y);
                if (dragging != null) {
                    dragStartX = dragging.x;
                    dragStartY = dragging.y;
                    dragDx = x - dragging.x * getWidth();
                    dragDy = y - dragging.y * getHeight();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!textGesture || dragging == null) return true;
                if (!dragMoved && Math.hypot(x - downX, y - downY) > touchSlop) dragMoved = true;
                if (dragMoved) {
                    dragging.x = clamp01((x - dragDx) / Math.max(1, getWidth()));
                    dragging.y = clamp01((y - dragDy) / Math.max(1, getHeight()));
                    overTrash = inTrash(x, y);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP: {
                if (!textGesture) return true;
                textGesture = false;
                TextItem d = dragging;
                dragging = null;
                if (d != null) {
                    if (dragMoved) {
                        if (overTrash) texts.remove(d);
                        overTrash = false;
                        commitTexts();
                    } else {
                        beginEditing(d, d.x, d.y);
                    }
                } else if (Math.hypot(x - downX, y - downY) <= touchSlop) {
                    beginEditing(null, clamp(x / Math.max(1, getWidth())), clamp(y / Math.max(1, getHeight())));
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelTextGesture();
                return true;
            default:
                return true;
        }
    }

    private void cancelTextGesture() {
        if (dragging != null) {
            dragging.x = dragStartX;
            dragging.y = dragStartY;
        }
        dragging = null;
        textGesture = false;
        dragMoved = false;
        overTrash = false;
        invalidate();
    }

    /** Starts typing: an existing text (moved out of the list while it is edited) or a new one at x,y. */
    private void beginEditing(TextItem existing, float x, float y) {
        if (existing != null) {
            editingOrig = existing;
            editingIndex = texts.indexOf(existing);
            texts.remove(existing);
            editing = existing.copy();
        } else {
            editingOrig = null;
            editing = new TextItem("", x, y, textColor, textSize);
        }
        invalidate();
        if (textListener != null) textListener.onTextEditStart(editing.text, editing.color, editing.size);
    }

    private TextItem hitText(float x, float y) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            TextItem t = texts.get(i);
            float px = applyTextPaint(t);
            float halfW = textPaint.measureText(t.text) / 2f + textHitPad;
            float halfH = px * 0.7f + textHitPad;
            float cx = t.x * getWidth();
            float cy = t.y * getHeight();
            if (Math.abs(x - cx) <= halfW && Math.abs(y - cy) <= halfH) return t;
        }
        return null;
    }

    private float trashX() {
        return getWidth() / 2f;
    }

    private float trashY() {
        return getHeight() - TRASH_BOTTOM_DP * getResources().getDisplayMetrics().density;
    }

    private boolean inTrash(float x, float y) {
        float hit = TRASH_HIT_DP * getResources().getDisplayMetrics().density;
        return Math.hypot(x - trashX(), y - trashY()) <= hit;
    }

    private void commitTexts() {
        Action a = new Action(TYPE_TEXTS, 0);
        a.items = copyTexts(texts);
        commit(a);
    }

    /** The texts shown at the cursor: the newest snapshot since the last clear, else none. */
    private void recomputeTexts() {
        texts = new ArrayList<>();
        for (int i = cursor - 1; i >= firstVisibleIndex(); i--) {
            Action a = actions.get(i);
            if (a.type == TYPE_TEXTS) {
                texts = copyTexts(a.items);
                break;
            }
        }
    }

    private static List<TextItem> copyTexts(List<TextItem> src) {
        List<TextItem> out = new ArrayList<>();
        if (src != null) for (TextItem t : src) out.add(t.copy());
        return out;
    }

    private static float clamp(float v) {
        return Math.max(0.05f, Math.min(0.95f, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
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
        return !texts.isEmpty();
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
        for (TextItem t : texts) drawText(canvas, t, false, t == dragging && overTrash);
        if (editing != null) drawText(canvas, editing, true, false);
        if (dragging != null && dragMoved) drawTrash(canvas);
    }

    /** Sets the paint for this text (shrunk to fit the board) and returns its pixel size. */
    private float applyTextPaint(TextItem t) {
        float px = TEXT_SIZE_FRACTION[t.size] * getWidth();
        textPaint.setTextSize(px);
        float w = textPaint.measureText(t.text);
        float max = getWidth() * TEXT_MAX_WIDTH_FRACTION;
        if (w > max && w > 0f) {
            px *= max / w;
            textPaint.setTextSize(px);
        }
        textPaint.setColor(t.color);
        return px;
    }

    private void drawText(Canvas canvas, TextItem t, boolean caret, boolean fade) {
        float px = applyTextPaint(t);
        if (fade) textPaint.setAlpha(110);
        float cx = t.x * getWidth();
        float cy = t.y * getHeight();
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(t.text, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint);
        if (caret) {
            boolean on = (SystemClock.uptimeMillis() / CARET_BLINK_MS) % 2 == 0;
            if (on) {
                float density = getResources().getDisplayMetrics().density;
                float x = cx + textPaint.measureText(t.text) / 2f + 2f * density;
                textPaint.setStrokeWidth(2f * density);
                canvas.drawLine(x, cy - px * 0.6f, x, cy + px * 0.6f, textPaint);
            }
            postInvalidateDelayed(CARET_BLINK_MS);
        }
    }

    /** The bin that appears while a text is dragged; the text deletes when let go over it. */
    private void drawTrash(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float r = TRASH_RADIUS_DP * density * (overTrash ? 1.25f : 1f);
        float cx = trashX();
        float cy = trashY();
        trashPaint.setStyle(Paint.Style.FILL);
        trashPaint.setColor(overTrash ? 0xFFFF1744 : 0xAA808080);
        canvas.drawCircle(cx, cy, r, trashPaint);
        trashPaint.setStyle(Paint.Style.STROKE);
        trashPaint.setColor(Color.WHITE);
        trashPaint.setStrokeWidth(2.5f * density);
        float k = r * 0.35f;
        canvas.drawLine(cx - k, cy - k, cx + k, cy + k, trashPaint);
        canvas.drawLine(cx - k, cy + k, cx + k, cy - k, trashPaint);
    }

    private void replay() {
        if (bitmap == null) return;
        bitmap.eraseColor(Color.TRANSPARENT);
        for (int i = firstVisibleIndex(); i < cursor; i++) drawAction(actions.get(i));
        invalidate();
    }

    private void drawAction(Action a) {
        if (bitmap == null) return;
        if (a.type == TYPE_TEXTS) return;
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
