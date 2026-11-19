package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import com.jovoc.facecampresentationrecorder.aiedit.engine.RenderNotes;
import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.CaptionPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.OutputSpec;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanComponent;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the styled-text items of a plan (keyword echo, hook, commentary): each is rendered once into a sprite when
 * it first appears, placed in its zone inside the safe rectangle and off the facecam, animated by
 * {@link TextAnimator}, and freed when it ends. Nothing is allocated per frame.
 */
public final class TextOverlayPainter implements CaptionPainter {

    /** Overlay text is this many times the caption text size. */
    static final float SIZE_FACTOR = 1.6f;
    /** The whole block, with its pill, is at most this share of the output width. */
    static final float MAX_WIDTH_SHARE = 0.8f;
    /** The blurred copy of a {@code blur_in} text is this many times smaller than the sprite. */
    static final int BLUR_DOWNSCALE = 8;
    /** The light band of a {@code shimmer} hold: this share of the sprite width, peak alpha at its centre. */
    static final float SHIMMER_WIDTH_SHARE = 0.35f;
    static final int SHIMMER_PEAK_ALPHA = 150;
    static final String DEFAULT_LOOK = "sticker";
    static final String DEFAULT_ZONE = "middle_center";

    /** One text item: its animation, and the sprite and position once it has been built. */
    private static final class Overlay {
        final PlanItem item;
        final TextLook look;
        final TextAnimator animator;
        final int wordCount;
        final int unitCount;
        TextLookRenderer.Sprite sprite;
        /** A soft copy of the sprite, made once for {@code blur_in}; drawn scaled up, so it is blurred. */
        Bitmap blurred;
        float x;
        float y;
        float tiltDeg;
        boolean built;
        /** The text fits nowhere clear of the facecam, or the sprite is already freed. */
        boolean skipped;

        Overlay(PlanItem item, TextLook look, TextAnimator animator, int wordCount, int unitCount) {
            this.item = item;
            this.look = look;
            this.animator = animator;
            this.wordCount = wordCount;
            this.unitCount = unitCount;
        }
    }

    private final EngineContract contract;
    private final OutputSpec spec;
    private final int outW;
    private final int outH;
    private final float captionSizePx;
    private final TextPlacement.Keepout facecam;
    private final TextLookRenderer renderer;
    private final RenderNotes notes;
    private final List<Overlay> overlays = new ArrayList<>();
    private final TextAnimator.State state = new TextAnimator.State();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] spot = new float[2];
    private final RectF blurDst = new RectF();
    /** The shimmer band: one unit-wide gradient, moved and scaled with {@link #shimmerMatrix} (nothing is allocated per frame). */
    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shimmerMatrix = new Matrix();

    /**
     * @param captionSizePx the caption text size of this output; overlay text is 1.6 times it
     * @param facecam       where the facecam is in output pixels, or null for none
     */
    public TextOverlayPainter(Plan plan, EngineContract contract, OutputSpec spec, int outW, int outH,
                              float captionSizePx, TextPlacement.Keepout facecam) {
        this(plan, contract, spec, outW, outH, captionSizePx, facecam, null);
    }

    /** @param notes where a skipped or shrunk text is noted (codes and item ids only), or null for none */
    public TextOverlayPainter(Plan plan, EngineContract contract, OutputSpec spec, int outW, int outH,
                              float captionSizePx, TextPlacement.Keepout facecam, RenderNotes notes) {
        this.notes = notes != null ? notes : new RenderNotes();
        this.contract = contract;
        this.spec = spec;
        this.outW = outW;
        this.outH = outH;
        this.captionSizePx = captionSizePx;
        this.facecam = facecam;
        this.renderer = new TextLookRenderer(new PaletteResolver(plan.palette, contract.getPalette()));
        shimmerPaint.setShader(new LinearGradient(0, 0, 1, 0, new int[]{0x00FFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        shimmerPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        for (PlanItem item : plan.items) addItem(item);
    }

    private void addItem(PlanItem item) {
        PlanComponent c = item.component;
        if (c == null || !c.isText() || c.text == null || c.text.trim().isEmpty() || item.endMs <= item.startMs) return;
        RegistryEntry entry = contract.effect(item.effect);
        Object registryPreset = entry != null ? entry.defaultParams.get("style_preset") : null;
        String preset = c.stylePreset != null ? c.stylePreset : item.paramString("style_preset",
                registryPreset instanceof String ? (String) registryPreset : DEFAULT_LOOK);
        TextLook look = contract.textLook(preset);
        if (look == null) look = contract.textLook(DEFAULT_LOOK);
        if (look == null) return;
        int words = c.text.trim().split("\\s+").length;
        int units = TextMotion.revealUnits(c.text.trim()).length;
        boolean rtl = TextMotion.readingDirection(c.text) == TextMotion.RTL;
        TextAnimator animator = new TextAnimator(item, contract.effect(item.effect), contract.getSpeed(), words,
                units, rtl);
        overlays.add(new Overlay(item, look, animator, words, units));
    }

    /** How many text items will be drawn. */
    public int overlayCount() {
        return overlays.size();
    }

    @Override
    public boolean draw(Canvas canvas, long outputMs, int outputW, int outputH) {
        boolean drew = false;
        for (int i = 0; i < overlays.size(); i++) {
            Overlay o = overlays.get(i);
            if (outputMs >= o.item.endMs) {
                free(o);
                continue;
            }
            if (outputMs < o.item.startMs || o.skipped) continue;
            if (!o.built) build(o);
            if (o.skipped || !o.animator.at(outputMs, state)) continue;
            drawOverlay(canvas, o);
            drew = true;
        }
        return drew;
    }

    @Override
    public float captionTextSizePx(int outputW, int outputH) {
        return captionSizePx;
    }

    private void free(Overlay o) {
        if (o.blurred != null) {
            o.blurred.recycle();
            o.blurred = null;
        }
        if (o.sprite != null) {
            o.sprite.recycle();
            o.sprite = null;
        }
        o.skipped = true;
    }

    /** Renders the sprite and finds its spot: the zone centre, kept in the safe rectangle and off the facecam. */
    private void build(Overlay o) {
        o.built = true;
        PlanComponent c = o.item.component;
        try {
            o.sprite = renderer.render(c.text, o.look, c.colorSlot, SIZE_FACTOR * captionSizePx,
                    Math.round(MAX_WIDTH_SHARE * outW));
        } catch (UnsupportedLookException e) {
            // A look the painter cannot draw completely is not drawn half styled (the plain fallback comes with C7)
            notes.add(RenderNotes.PROPERTY_NOT_DRAWN, o.item.id);
            free(o);
            return;
        }
        if (o.sprite == null) {
            notes.add(RenderNotes.OVERLAY_SKIPPED_NO_ROOM, o.item.id);
            free(o);
            return;
        }
        if (o.sprite.shrunk) notes.add(RenderNotes.TEXT_SHRUNK, o.item.id);
        if (o.sprite.wrapped) notes.add(RenderNotes.TEXT_WRAPPED, o.item.id);
        if ("blur_in".equals(o.animator.entry)) {
            // Blur once, here, never per frame: a small copy, drawn scaled up with filtering
            o.blurred = Bitmap.createScaledBitmap(o.sprite.bitmap, Math.max(1, o.sprite.width / BLUR_DOWNSCALE),
                    Math.max(1, o.sprite.height / BLUR_DOWNSCALE), true);
        }
        o.tiltDeg = tilt(o.item, o.look);
        double rad = Math.toRadians(Math.abs(o.tiltDeg));
        float halfW = (float) (o.sprite.contentW * Math.cos(rad) + o.sprite.contentH * Math.sin(rad)) / 2;
        float halfH = (float) (o.sprite.contentW * Math.sin(rad) + o.sprite.contentH * Math.cos(rad)) / 2;
        double[] zone = contract.zone(o.item.zone != null ? o.item.zone : DEFAULT_ZONE);
        if (zone == null) zone = contract.zone(DEFAULT_ZONE);
        float wantX = (float) spec.zoneX(zone[0]) * outW;
        float wantY = (float) spec.zoneY(zone[1]) * outH;
        float[] safe = {(float) spec.safeX0 * outW, (float) spec.safeY0 * outH,
                (float) spec.safeX1 * outW, (float) spec.safeY1 * outH};
        if (TextPlacement.place(wantX, wantY, halfW, halfH, safe, outW, outH, facecam,
                o.item.startMs, o.item.endMs, spot)) {
            o.x = spot[0];
            o.y = spot[1];
        } else {
            notes.add(RenderNotes.OVERLAY_SKIPPED_NO_ROOM, o.item.id);
            free(o);
        }
    }

    /**
     * A steady tilt: the item's {@code params.tilt_deg} as the server gave it, sign included, kept inside the look's
     * range (0 for a look without one). Without that param, a value inside the range picked from the item id, so a
     * re-render matches.
     */
    static float tilt(PlanItem item, TextLook look) {
        return TextMotion.tilt(item.params.get("tilt_deg"), look.tiltDeg, item.id);
    }

    private void drawOverlay(Canvas canvas, Overlay o) {
        TextLookRenderer.Sprite sp = o.sprite;
        int alpha = Math.round(255 * Math.max(0f, Math.min(1f, state.alpha)));
        paint.setAlpha(alpha);
        canvas.save();
        canvas.translate(o.x + state.dx * outW, o.y + state.dy * outH);
        canvas.rotate(o.tiltDeg);
        canvas.scale(state.scale, state.scale * Math.max(0.001f, state.scaleY));
        canvas.translate(-sp.width / 2f, -sp.height / 2f);
        drawBars(canvas, sp, alpha);
        if (state.reveal < 1) {
            float w = sp.width * state.reveal;
            canvas.clipRect(state.revealFromRight ? sp.width - w : 0, 0, state.revealFromRight ? sp.width : w, sp.height);
        }
        // A shimmer lights only what the text drew: the band is blended over the sprite inside its own layer
        boolean shimmer = state.shimmer >= 0;
        int layer = shimmer ? canvas.saveLayer(0, 0, sp.width, sp.height, null) : -1;
        if (o.blurred != null && state.blur > 0) {
            paint.setAlpha(Math.round(alpha * Math.min(1f, state.blur)));
            blurDst.set(0, 0, sp.width, sp.height);
            canvas.drawBitmap(o.blurred, null, blurDst, paint);
            paint.setAlpha(Math.round(alpha * (1 - Math.min(1f, state.blur))));
            canvas.drawBitmap(sp.bitmap, 0, 0, paint);
        } else if (state.units != TextAnimator.ALL_WORDS && sp.unitClips.length > 0) {
            int shown = state.units >= o.unitCount ? sp.unitClips.length
                    : (int) Math.ceil((double) state.units * sp.unitClips.length / o.unitCount);
            for (int w = 0; w < shown && w < sp.unitClips.length; w++) {
                canvas.save();
                canvas.clipRect(sp.unitClips[w]);
                canvas.drawBitmap(sp.bitmap, 0, 0, paint);
                canvas.restore();
            }
        } else if (state.words == TextAnimator.ALL_WORDS || state.words >= o.wordCount || sp.wordClips.length != o.wordCount) {
            canvas.drawBitmap(sp.bitmap, 0, 0, paint);
        } else {
            for (int w = 0; w < state.words && w < sp.wordClips.length; w++) {
                canvas.save();
                canvas.clipRect(sp.wordClips[w]);
                canvas.drawBitmap(sp.bitmap, 0, 0, paint);
                canvas.restore();
            }
        }
        if (shimmer) {
            float band = sp.width * SHIMMER_WIDTH_SHARE;
            float left = -band + (sp.width + band) * state.shimmer;
            shimmerMatrix.setScale(band, 1);
            shimmerMatrix.postTranslate(left, 0);
            shimmerPaint.getShader().setLocalMatrix(shimmerMatrix);
            shimmerPaint.setAlpha(Math.round(SHIMMER_PEAK_ALPHA * alpha / 255f));
            canvas.drawRect(0, 0, sp.width, sp.height, shimmerPaint);
            canvas.restoreToCount(layer);
        }
        canvas.restore();
    }

    /** The highlighter bars, swept left to right by {@code state.barProgress}. */
    private void drawBars(Canvas canvas, TextLookRenderer.Sprite sp, int alpha) {
        if (sp.bars.length == 0 || state.barProgress <= 0) return;
        barPaint.setColor(sp.barColor);
        barPaint.setAlpha(Math.round(((sp.barColor >>> 24) & 0xFF) * alpha / 255f));
        for (RectF bar : sp.bars) {
            canvas.save();
            canvas.clipRect(bar.left, bar.top, bar.left + bar.width() * state.barProgress, bar.bottom);
            canvas.drawRoundRect(bar, bar.height() * 0.15f, bar.height() * 0.15f, barPaint);
            canvas.restore();
        }
    }
}
