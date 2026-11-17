package com.jovoc.facecampresentationrecorder.subtitle;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;

import java.util.List;
import java.util.Objects;

/**
 * High-performance mobile canvas subtitle renderer for word-synchronized karaoke subtitles.
 *
 * <p>Renders styled phrases and active word highlight pills onto any Android {@link Canvas}.
 * Designed for both UI preview overlay ({@code KaraokeSubtitleView}) and hardware OpenGL video burn-in ({@code EditRenderer}).
 *
 * <p>Worldwide Language Support:
 * Uses {@link TextPaint} with {@code Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)}. Android's
 * Minikin / HarfBuzz text layout engine natively resolves glyphs, ligatures, and diacritics for all
 * major writing systems (Bengali, Hindi/Devanagari, Arabic, Japanese, Chinese, Hebrew, Thai, Cyrillic, etc.)
 * via system Noto fonts.
 *
 * <p>Performance & Zero Allocation:
 * Internal paints, rects, buffers, and gradient shaders are reused across frames to prevent GC churn
 * during 60fps video playback and high-speed export.
 */
public class SubtitleDrawer {

    public static final float REFERENCE_WIDTH = 1080.0f;
    public static final float BASE_DENSITY = 3.0f; // 1080px corresponds to 360dp width (xxhdpi)
    public static final float BASE_OUTLINE_WIDTH_DP = 2.0f;

    /**
     * Drawing target abstraction allowing both real {@link Canvas} rendering and headless/unit-test verification.
     */
    public interface DrawTarget {
        void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, Paint paint);
        void drawText(String text, float x, float y, Paint paint);
        void save();
        void scale(float sx, float sy, float px, float py);
        void restore();
    }

    /**
     * Text measurement abstraction enabling font sizing and metrics testing without device dependencies.
     */
    public interface TextMeasurer {
        float measureText(String text, float textSizePx);
        float getAscent(float textSizePx);
        float getDescent(float textSizePx);
    }

    /**
     * Plain geometry bounding box container independent of Android OS stubs.
     */
    public static class Bounds {
        public float left;
        public float top;
        public float right;
        public float bottom;

        public Bounds() {}

        public Bounds(float left, float top, float right, float bottom) {
            set(left, top, right, bottom);
        }

        public void set(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public void set(Bounds other) {
            if (other != null) {
                set(other.left, other.top, other.right, other.bottom);
            }
        }

        public float width() {
            return Math.max(0f, right - left);
        }

        public float height() {
            return Math.max(0f, bottom - top);
        }

        public float centerX() {
            return (left + right) / 2.0f;
        }

        public float centerY() {
            return (top + bottom) / 2.0f;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Bounds bounds = (Bounds) o;
            return Float.compare(bounds.left, left) == 0 &&
                    Float.compare(bounds.top, top) == 0 &&
                    Float.compare(bounds.right, right) == 0 &&
                    Float.compare(bounds.bottom, bottom) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(left, top, right, bottom);
        }

        @Override
        public String toString() {
            return "Bounds{" + "left=" + left + ", top=" + top + ", right=" + right + ", bottom=" + bottom + '}';
        }
    }

    /**
     * Default Android HarfBuzz/Minikin text measurer using {@link TextPaint}.
     */
    public static class AndroidTextMeasurer implements TextMeasurer {
        private final TextPaint paint;
        private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();

        public AndroidTextMeasurer(TextPaint paint) {
            this.paint = paint;
        }

        @Override
        public float measureText(String text, float textSizePx) {
            if (paint == null || text == null) return 0f;
            paint.setTextSize(textSizePx);
            return paint.measureText(text);
        }

        @Override
        public float getAscent(float textSizePx) {
            if (paint == null) return -textSizePx * 0.75f;
            paint.setTextSize(textSizePx);
            paint.getFontMetrics(fontMetrics);
            return fontMetrics.ascent;
        }

        @Override
        public float getDescent(float textSizePx) {
            if (paint == null) return textSizePx * 0.25f;
            paint.setTextSize(textSizePx);
            paint.getFontMetrics(fontMetrics);
            return fontMetrics.descent;
        }
    }

    /**
     * Fallback text measurer for deterministic host JVM test environments.
     */
    public static class SimpleTextMeasurer implements TextMeasurer {
        private final float charWidthRatio;
        private final float ascentRatio;
        private final float descentRatio;

        public SimpleTextMeasurer() {
            this(0.55f, -0.75f, 0.25f);
        }

        public SimpleTextMeasurer(float charWidthRatio, float ascentRatio, float descentRatio) {
            this.charWidthRatio = charWidthRatio;
            this.ascentRatio = ascentRatio;
            this.descentRatio = descentRatio;
        }

        @Override
        public float measureText(String text, float textSizePx) {
            if (text == null) return 0f;
            return text.length() * textSizePx * charWidthRatio;
        }

        @Override
        public float getAscent(float textSizePx) {
            return textSizePx * ascentRatio;
        }

        @Override
        public float getDescent(float textSizePx) {
            return textSizePx * descentRatio;
        }
    }

    /**
     * Android {@link Canvas} implementation of {@link DrawTarget}.
     */
    public static class CanvasDrawTarget implements DrawTarget {
        private Canvas canvas;

        public CanvasDrawTarget() {}

        public CanvasDrawTarget(Canvas canvas) {
            this.canvas = canvas;
        }

        public void setCanvas(Canvas canvas) {
            this.canvas = canvas;
        }

        public Canvas getCanvas() {
            return canvas;
        }

        @Override
        public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, Paint paint) {
            if (canvas != null) {
                canvas.drawRoundRect(left, top, right, bottom, rx, ry, paint);
            }
        }

        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            if (canvas != null) {
                canvas.drawText(text, x, y, paint);
            }
        }

        @Override
        public void save() {
            if (canvas != null) {
                canvas.save();
            }
        }

        @Override
        public void scale(float sx, float sy, float px, float py) {
            if (canvas != null) {
                canvas.scale(sx, sy, px, py);
            }
        }

        @Override
        public void restore() {
            if (canvas != null) {
                canvas.restore();
            }
        }
    }

    // Reusable paint instances (zero allocation)
    private TextPaint textPaint;
    private TextPaint outlinePaint;
    private Paint pillPaint;
    private TextMeasurer textMeasurer;

    // Reusable adapter for Android Canvas
    private final CanvasDrawTarget canvasDrawTarget = new CanvasDrawTarget();

    // Reusable arrays for layout calculation
    private float[] wordWidths = new float[16];
    private float[] wordStartXs = new float[16];

    // Reusable bounds objects to avoid allocation in draw
    private final Bounds tempWordBounds = new Bounds();
    private final Bounds tempPillBounds = new Bounds();

    // Cached LinearGradient to prevent allocations on identical pill dimensions
    private LinearGradient cachedGradient = null;
    private float cachedGradientLeft = 0;
    private float cachedGradientTop = 0;
    private float cachedGradientRight = 0;
    private float cachedGradientBottom = 0;
    private int cachedGradientStartColor = 0;
    private int cachedGradientEndColor = 0;

    public SubtitleDrawer() {
        initPaints();
    }

    private void initPaints() {
        try {
            textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            outlinePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            Typeface boldSans = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
            textPaint.setTypeface(boldSans);
            textPaint.setTextAlign(Paint.Align.LEFT);

            outlinePaint.setTypeface(boldSans);
            outlinePaint.setTextAlign(Paint.Align.LEFT);
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeJoin(Paint.Join.ROUND);
            outlinePaint.setStrokeCap(Paint.Cap.ROUND);

            pillPaint.setStyle(Paint.Style.FILL);

            textMeasurer = new AndroidTextMeasurer(textPaint);
        } catch (Throwable t) {
            // JVM unit test fallback when android.graphics native methods are unmocked
            textMeasurer = new SimpleTextMeasurer();
        }
    }

    public void setTextMeasurer(TextMeasurer measurer) {
        if (measurer != null) {
            this.textMeasurer = measurer;
        }
    }

    public TextMeasurer getTextMeasurer() {
        return textMeasurer;
    }

    public void setTypeface(Typeface typeface) {
        if (typeface != null) {
            if (textPaint != null) textPaint.setTypeface(typeface);
            if (outlinePaint != null) outlinePaint.setTypeface(typeface);
        }
    }

    public TextPaint getTextPaint() {
        return textPaint;
    }

    public TextPaint getOutlinePaint() {
        return outlinePaint;
    }

    public Paint getPillPaint() {
        return pillPaint;
    }

    /**
     * Renders word-synced subtitles directly onto the given Android {@link Canvas}.
     *
     * @param canvas Target Android Canvas
     * @param track Synchronized subtitle track
     * @param style Subtitle visual style configuration
     * @param playheadMs Current playback timestamp in milliseconds
     * @param viewWidth Width of the drawing area in pixels
     * @param viewHeight Height of the drawing area in pixels
     * @return True if a phrase was active and rendered; false if out-of-bounds or invalid arguments
     */
    public boolean draw(Canvas canvas, SubtitleTrack track, SubtitleStyle style, long playheadMs, int viewWidth, int viewHeight) {
        if (canvas == null) {
            return false;
        }
        canvasDrawTarget.setCanvas(canvas);
        return draw(canvasDrawTarget, track, style, playheadMs, viewWidth, viewHeight);
    }

    /**
     * Renders word-synced subtitles onto a {@link DrawTarget}.
     */
    public boolean draw(DrawTarget target, SubtitleTrack track, SubtitleStyle style, long playheadMs, int viewWidth, int viewHeight) {
        if (target == null || track == null || style == null || playheadMs < 0 || viewWidth <= 0 || viewHeight <= 0) {
            return false;
        }

        SubtitlePhrase phrase = track.findPhraseAt(playheadMs);
        if (phrase == null || phrase.words.isEmpty()) {
            return false;
        }

        int wordCount = phrase.words.size();
        ensureCapacity(wordCount);

        float fontSizePx = measureScaledFontSize(style.fontSizeSp, viewWidth);
        float spaceWidth = textMeasurer.measureText(" ", fontSizePx);

        float totalWordsWidth = 0f;
        for (int i = 0; i < wordCount; i++) {
            SubtitleWord word = phrase.words.get(i);
            float w = textMeasurer.measureText(word.text, fontSizePx);
            wordWidths[i] = w;
            totalWordsWidth += w;
        }

        float padX = measureScaledDimension(style.paddingHorizontalDp, viewWidth);
        float popBuffer = measureScaledDimension(2.0f, viewWidth);
        float wordGap = computeWordGap(spaceWidth, padX, popBuffer, totalWordsWidth, wordCount, viewWidth);

        float totalPhraseWidth = totalWordsWidth + (wordCount > 1 ? (wordCount - 1) * wordGap : 0f);

        float startX = (viewWidth - totalPhraseWidth) / 2.0f;
        float currentX = startX;
        for (int i = 0; i < wordCount; i++) {
            wordStartXs[i] = currentX;
            currentX += wordWidths[i] + wordGap;
        }

        float centerY = computeCenterY(viewWidth, viewHeight, style, fontSizePx, textMeasurer);
        float ascent = textMeasurer.getAscent(fontSizePx);
        float descent = textMeasurer.getDescent(fontSizePx);
        float baselineY = computeBaselineY(centerY, ascent, descent);

        float textTop = baselineY + ascent;
        float textBottom = baselineY + descent;

        int activeIndex = phrase.findActiveWordIndex(playheadMs);

        // 1. Draw rounded background pill for active word (if active word exists)
        if (activeIndex >= 0 && activeIndex < wordCount) {
            float activeWordLeft = wordStartXs[activeIndex];
            float activeWordRight = activeWordLeft + wordWidths[activeIndex];
            tempWordBounds.set(activeWordLeft, textTop, activeWordRight, textBottom);

            float padY = measureScaledDimension(style.paddingVerticalDp, viewWidth);
            computePillBounds(tempWordBounds, padX, padY, tempPillBounds);

            float cornerRadius = measureScaledDimension(style.cornerRadiusDp, viewWidth);

            if (pillPaint != null) {
                if (style.isGradient()) {
                    if (cachedGradient == null
                            || cachedGradientLeft != tempPillBounds.left
                            || cachedGradientTop != tempPillBounds.top
                            || cachedGradientRight != tempPillBounds.right
                            || cachedGradientBottom != tempPillBounds.bottom
                            || cachedGradientStartColor != style.pillColor
                            || cachedGradientEndColor != style.pillEndColor) {
                        cachedGradientLeft = tempPillBounds.left;
                        cachedGradientTop = tempPillBounds.top;
                        cachedGradientRight = tempPillBounds.right;
                        cachedGradientBottom = tempPillBounds.bottom;
                        cachedGradientStartColor = style.pillColor;
                        cachedGradientEndColor = style.pillEndColor;
                        try {
                            cachedGradient = new LinearGradient(
                                    tempPillBounds.left, tempPillBounds.top,
                                    tempPillBounds.right, tempPillBounds.top,
                                    style.pillColor, style.pillEndColor,
                                    Shader.TileMode.CLAMP
                            );
                        } catch (Throwable ignored) {
                            cachedGradient = null;
                        }
                    }
                    try {
                        pillPaint.setShader(cachedGradient);
                    } catch (Throwable ignored) {
                    }
                } else {
                    try {
                        pillPaint.setShader(null);
                    } catch (Throwable ignored) {
                    }
                    try {
                        pillPaint.setColor(style.pillColor);
                    } catch (Throwable ignored) {
                    }
                }
            }

            target.drawRoundRect(
                    tempPillBounds.left, tempPillBounds.top,
                    tempPillBounds.right, tempPillBounds.bottom,
                    cornerRadius, cornerRadius,
                    pillPaint
            );
        }

        // 2. Base text rendering (two-pass: outline stroke + fill)
        float outlineStrokeWidth = Math.max(1.0f, measureScaledDimension(BASE_OUTLINE_WIDTH_DP, viewWidth));
        if (outlinePaint != null) {
            try {
                outlinePaint.setTextSize(fontSizePx);
                outlinePaint.setStrokeWidth(outlineStrokeWidth);
                outlinePaint.setColor(style.outlineColor);
            } catch (Throwable ignored) {
            }
        }

        if (textPaint != null) {
            try {
                textPaint.setTextSize(fontSizePx);
                textPaint.setStyle(Paint.Style.FILL);
                textPaint.setColor(style.textColor);
            } catch (Throwable ignored) {
            }
        }

        for (int i = 0; i < wordCount; i++) {
            if (i == activeIndex) {
                continue; // Rendered in active word pass
            }
            SubtitleWord word = phrase.words.get(i);
            if (style.outlineColor != 0) {
                target.drawText(word.text, wordStartXs[i], baselineY, outlinePaint);
            }
            target.drawText(word.text, wordStartXs[i], baselineY, textPaint);
        }

        // 3. Active word rendering (with scale pop centered at active word's center)
        if (activeIndex >= 0 && activeIndex < wordCount) {
            SubtitleWord activeWord = phrase.words.get(activeIndex);
            float activeWordCenterX = wordStartXs[activeIndex] + wordWidths[activeIndex] / 2.0f;
            float activeWordCenterY = centerY;
            float scale = style.scaleActiveWord > 0 ? style.scaleActiveWord : 1.0f;

            target.save();
            if (scale != 1.0f) {
                target.scale(scale, scale, activeWordCenterX, activeWordCenterY);
            }

            // Draw outline if active text color matches base text (ensuring high contrast)
            if (style.outlineColor != 0 && style.activeTextColor == style.textColor) {
                target.drawText(activeWord.text, wordStartXs[activeIndex], baselineY, outlinePaint);
            }

            if (textPaint != null) {
                try {
                    textPaint.setColor(style.activeTextColor);
                } catch (Throwable ignored) {
                }
            }
            target.drawText(activeWord.text, wordStartXs[activeIndex], baselineY, textPaint);
            target.restore();
        }

        return true;
    }

    private void ensureCapacity(int count) {
        if (wordWidths == null || wordWidths.length < count) {
            int newCap = Math.max(count, 16);
            wordWidths = new float[newCap];
            wordStartXs = new float[newCap];
        }
    }

    // --- Helper Calculation Methods (Exposed for easy testing & preview layout) ---

    public static float computeScaleFactor(int viewWidth) {
        if (viewWidth <= 0) return 1.0f;
        return ((float) viewWidth / REFERENCE_WIDTH) * BASE_DENSITY;
    }

    public static float measureScaledFontSize(float fontSizeSp, int viewWidth) {
        return fontSizeSp * computeScaleFactor(viewWidth);
    }

    public static float measureScaledDimension(float dp, int viewWidth) {
        return dp * computeScaleFactor(viewWidth);
    }

    public static float computeBaselineY(float centerY, float ascent, float descent) {
        return centerY - (ascent + descent) / 2.0f;
    }

    /**
     * Maps the video aspect ratio (viewHeight / viewWidth) to an optimal vertical position:
     * - Tall vertical videos (>= 9:16, i.e. aspect >= 1.70): 0.95f (cleanly below FaceCam bubble)
     * - Square videos (1:1, aspect = 1.00): 0.885f
     * - Portrait videos (4:5, aspect = 1.25): ~0.908f
     * - Landscape videos (<= 16:9, aspect <= 0.60): 0.810f
     */
    public static float resolveAdaptivePositionY(int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) return SubtitleStyle.DEFAULT_POSITION_Y;
        float aspect = (float) viewHeight / (float) viewWidth;
        if (aspect >= 1.70f) {
            return 0.95f;
        } else if (aspect <= 0.60f) {
            return 0.81f;
        } else if (aspect <= 1.00f) {
            float t = (aspect - 0.60f) / (1.00f - 0.60f);
            return 0.81f + t * (0.885f - 0.81f);
        } else {
            float t = (aspect - 1.00f) / (1.70f - 1.00f);
            return 0.885f + t * (0.95f - 0.885f);
        }
    }

    /**
     * Resolves the visual vertical center Y coordinate for subtitles.
     * <p>
     * When using the default position, this dynamically adapts the position according to the aspect ratio height
     * (viewHeight / viewWidth) so subtitles stay below the FaceCam area without obscuring the face, while also
     * enforcing a minimum safe bottom clearance so that descenders, large fonts, and pills never clip at the bottom.
     *
     * @param viewWidth Width of the video/preview drawing area in pixels
     * @param viewHeight Height of the video/preview drawing area in pixels
     * @param style Subtitle style containing positionY and font sizing
     * @param fontSizePx Scaled font size in pixels
     * @param measurer Text metrics measurer
     * @return Center Y coordinate in pixels
     */
    public static float computeCenterY(int viewWidth, int viewHeight, SubtitleStyle style,
                                        float fontSizePx, TextMeasurer measurer) {
        if (viewHeight <= 0) return 0f;

        float pos;
        if (style != null && style.positionY > 0f
                && Float.compare(style.positionY, SubtitleStyle.DEFAULT_POSITION_Y) != 0) {
            pos = style.positionY;
        } else {
            pos = resolveAdaptivePositionY(viewWidth, viewHeight);
        }

        float rawCenterY = viewHeight * pos;

        if (measurer != null && viewWidth > 0) {
            float ascent = measurer.getAscent(fontSizePx);
            float descent = measurer.getDescent(fontSizePx);
            float textHalfHeight = (descent - ascent) / 2.0f;
            float padY = measureScaledDimension(
                    style != null ? style.paddingVerticalDp : SubtitleStyle.DEFAULT_PADDING_VERTICAL_DP,
                    viewWidth);
            float scale = (style != null && style.scaleActiveWord > 0) ? style.scaleActiveWord : 1.0f;
            float outlineW = Math.max(1.0f, measureScaledDimension(BASE_OUTLINE_WIDTH_DP, viewWidth));
            float halfHeightBottom = (textHalfHeight + padY) * scale + outlineW;
            float minBottomGapPx = measureScaledDimension(12.0f, viewWidth);

            float maxCenterY = viewHeight - halfHeightBottom - minBottomGapPx;
            return Math.min(rawCenterY, maxCenterY);
        }

        return rawCenterY;
    }

    public static float computeCenterY(int viewWidth, int viewHeight, SubtitleStyle style) {
        if (style == null || viewWidth <= 0 || viewHeight <= 0) return viewHeight * SubtitleStyle.DEFAULT_POSITION_Y;
        float fontSizePx = measureScaledFontSize(style.fontSizeSp, viewWidth);
        TextMeasurer measurer = new SimpleTextMeasurer();
        return computeCenterY(viewWidth, viewHeight, style, fontSizePx, measurer);
    }

    public static void computePillBounds(Bounds wordBounds, float padX, float padY, Bounds outPillBounds) {
        if (wordBounds == null || outPillBounds == null) return;
        outPillBounds.set(
                wordBounds.left - padX,
                wordBounds.top - padY,
                wordBounds.right + padX,
                wordBounds.bottom + padY
        );
    }

    public static void computePillBounds(Bounds wordBounds, SubtitleStyle style, int viewWidth, Bounds outPillBounds) {
        if (wordBounds == null || style == null || outPillBounds == null) return;
        float padX = measureScaledDimension(style.paddingHorizontalDp, viewWidth);
        float padY = measureScaledDimension(style.paddingVerticalDp, viewWidth);
        computePillBounds(wordBounds, padX, padY, outPillBounds);
    }

    public static void computePillBounds(RectF wordBounds, float padX, float padY, RectF outPillBounds) {
        if (wordBounds == null || outPillBounds == null) return;
        outPillBounds.set(
                wordBounds.left - padX,
                wordBounds.top - padY,
                wordBounds.right + padX,
                wordBounds.bottom + padY
        );
    }

    public static void computePillBounds(RectF wordBounds, SubtitleStyle style, int viewWidth, RectF outPillBounds) {
        if (wordBounds == null || style == null || outPillBounds == null) return;
        float padX = measureScaledDimension(style.paddingHorizontalDp, viewWidth);
        float padY = measureScaledDimension(style.paddingVerticalDp, viewWidth);
        computePillBounds(wordBounds, padX, padY, outPillBounds);
    }

    public static float computeWordCenterX(Bounds wordBounds) {
        return wordBounds != null ? (wordBounds.left + wordBounds.right) / 2.0f : 0f;
    }

    public static float computeWordCenterX(RectF wordBounds) {
        return wordBounds != null ? (wordBounds.left + wordBounds.right) / 2.0f : 0f;
    }

    public static float computeWordCenterY(Bounds wordBounds) {
        return wordBounds != null ? (wordBounds.top + wordBounds.bottom) / 2.0f : 0f;
    }

    public static float computeWordCenterY(RectF wordBounds) {
        return wordBounds != null ? (wordBounds.top + wordBounds.bottom) / 2.0f : 0f;
    }

    /**
     * Computes the uniform inter-word gap ensuring active word highlight pills never overlap neighboring words.
     */
    public static float computeWordGap(float spaceWidth, float padX, float popBuffer) {
        return Math.max(spaceWidth, spaceWidth + padX + popBuffer);
    }

    /**
     * Computes the uniform inter-word gap with screen boundary safety constraint.
     */
    public static float computeWordGap(float spaceWidth, float padX, float popBuffer, float totalWordsWidth, int wordCount, int viewWidth) {
        float desiredGap = computeWordGap(spaceWidth, padX, popBuffer);
        if (wordCount <= 1 || viewWidth <= 0) {
            return desiredGap;
        }
        float maxUsableWidth = viewWidth * 0.90f;
        float desiredTotalWidth = totalWordsWidth + (wordCount - 1) * desiredGap;
        if (desiredTotalWidth > maxUsableWidth) {
            float availableForGaps = maxUsableWidth - totalWordsWidth;
            if (availableForGaps > 0) {
                float compressedGap = availableForGaps / (wordCount - 1);
                return Math.max(spaceWidth, Math.min(desiredGap, compressedGap));
            } else {
                return spaceWidth;
            }
        }
        return desiredGap;
    }

    public boolean computePhraseBounds(SubtitlePhrase phrase, SubtitleStyle style, int viewWidth, int viewHeight, Bounds outBounds) {
        if (phrase == null || phrase.words.isEmpty() || style == null || outBounds == null || viewWidth <= 0 || viewHeight <= 0) {
            return false;
        }

        int wordCount = phrase.words.size();
        float fontSizePx = measureScaledFontSize(style.fontSizeSp, viewWidth);
        float spaceWidth = textMeasurer.measureText(" ", fontSizePx);

        float totalWordsWidth = 0f;
        for (int i = 0; i < wordCount; i++) {
            totalWordsWidth += textMeasurer.measureText(phrase.words.get(i).text, fontSizePx);
        }

        float padX = measureScaledDimension(style.paddingHorizontalDp, viewWidth);
        float popBuffer = measureScaledDimension(2.0f, viewWidth);
        float wordGap = computeWordGap(spaceWidth, padX, popBuffer, totalWordsWidth, wordCount, viewWidth);

        float totalPhraseWidth = totalWordsWidth + (wordCount > 1 ? (wordCount - 1) * wordGap : 0f);

        float startX = (viewWidth - totalPhraseWidth) / 2.0f;
        float centerY = computeCenterY(viewWidth, viewHeight, style, fontSizePx, textMeasurer);
        float ascent = textMeasurer.getAscent(fontSizePx);
        float descent = textMeasurer.getDescent(fontSizePx);
        float baselineY = computeBaselineY(centerY, ascent, descent);

        outBounds.set(startX, baselineY + ascent, startX + totalPhraseWidth, baselineY + descent);
        return true;
    }

    public Bounds computePhraseBounds(SubtitlePhrase phrase, SubtitleStyle style, int viewWidth, int viewHeight) {
        Bounds b = new Bounds();
        return computePhraseBounds(phrase, style, viewWidth, viewHeight, b) ? b : null;
    }

    public boolean computePhraseBounds(SubtitlePhrase phrase, SubtitleStyle style, int viewWidth, int viewHeight, RectF outBounds) {
        if (outBounds == null) return false;
        Bounds b = new Bounds();
        boolean res = computePhraseBounds(phrase, style, viewWidth, viewHeight, b);
        if (res) {
            outBounds.set(b.left, b.top, b.right, b.bottom);
        }
        return res;
    }

    public boolean computeWordBounds(SubtitlePhrase phrase, int wordIndex, SubtitleStyle style, int viewWidth, int viewHeight, Bounds outBounds) {
        if (phrase == null || phrase.words.isEmpty() || style == null || outBounds == null
                || wordIndex < 0 || wordIndex >= phrase.words.size() || viewWidth <= 0 || viewHeight <= 0) {
            return false;
        }

        int wordCount = phrase.words.size();
        ensureCapacity(wordCount);

        float fontSizePx = measureScaledFontSize(style.fontSizeSp, viewWidth);
        float spaceWidth = textMeasurer.measureText(" ", fontSizePx);

        float totalWordsWidth = 0f;
        for (int i = 0; i < wordCount; i++) {
            float w = textMeasurer.measureText(phrase.words.get(i).text, fontSizePx);
            wordWidths[i] = w;
            totalWordsWidth += w;
        }

        float padX = measureScaledDimension(style.paddingHorizontalDp, viewWidth);
        float popBuffer = measureScaledDimension(2.0f, viewWidth);
        float wordGap = computeWordGap(spaceWidth, padX, popBuffer, totalWordsWidth, wordCount, viewWidth);

        float totalPhraseWidth = totalWordsWidth + (wordCount > 1 ? (wordCount - 1) * wordGap : 0f);

        float startX = (viewWidth - totalPhraseWidth) / 2.0f;
        float currentX = startX;
        for (int i = 0; i < wordIndex; i++) {
            currentX += wordWidths[i] + wordGap;
        }

        float wordLeft = currentX;
        float wordRight = wordLeft + wordWidths[wordIndex];

        float centerY = computeCenterY(viewWidth, viewHeight, style, fontSizePx, textMeasurer);
        float ascent = textMeasurer.getAscent(fontSizePx);
        float descent = textMeasurer.getDescent(fontSizePx);
        float baselineY = computeBaselineY(centerY, ascent, descent);

        outBounds.set(wordLeft, baselineY + ascent, wordRight, baselineY + descent);
        return true;
    }

    public Bounds computeWordBounds(SubtitlePhrase phrase, int wordIndex, SubtitleStyle style, int viewWidth, int viewHeight) {
        Bounds b = new Bounds();
        return computeWordBounds(phrase, wordIndex, style, viewWidth, viewHeight, b) ? b : null;
    }

    public boolean computeWordBounds(SubtitlePhrase phrase, int wordIndex, SubtitleStyle style, int viewWidth, int viewHeight, RectF outBounds) {
        if (outBounds == null) return false;
        Bounds b = new Bounds();
        boolean res = computeWordBounds(phrase, wordIndex, style, viewWidth, viewHeight, b);
        if (res) {
            outBounds.set(b.left, b.top, b.right, b.bottom);
        }
        return res;
    }
}
