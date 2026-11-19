package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

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
    static final String DEFAULT_LOOK = "sticker";
    static final String DEFAULT_ZONE = "middle_center";

    /** One text item: its animation, and the sprite and position once it has been built. */
    private static final class Overlay {
        final PlanItem item;
        final TextLook look;
        final TextAnimator animator;
        final int wordCount;
        TextLookRenderer.Sprite sprite;
        float x;
        float y;
        float tiltDeg;
        boolean built;
        /** The text fits nowhere clear of the facecam, or the sprite is already freed. */
        boolean skipped;

        Overlay(PlanItem item, TextLook look, TextAnimator animator, int wordCount) {
            this.item = item;
            this.look = look;
            this.animator = animator;
            this.wordCount = wordCount;
        }
    }

    private final EngineContract contract;
    private final OutputSpec spec;
    private final int outW;
    private final int outH;
    private final float captionSizePx;
    private final TextPlacement.Keepout facecam;
    private final TextLookRenderer renderer;
    private final List<Overlay> overlays = new ArrayList<>();
    private final TextAnimator.State state = new TextAnimator.State();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] spot = new float[2];

    /**
     * @param captionSizePx the caption text size of this output; overlay text is 1.6 times it
     * @param facecam       where the facecam is in output pixels, or null for none
     */
    public TextOverlayPainter(Plan plan, EngineContract contract, OutputSpec spec, int outW, int outH,
                              float captionSizePx, TextPlacement.Keepout facecam) {
        this.contract = contract;
        this.spec = spec;
        this.outW = outW;
        this.outH = outH;
        this.captionSizePx = captionSizePx;
        this.facecam = facecam;
        this.renderer = new TextLookRenderer(new PaletteResolver(plan.palette, contract.getPalette()));
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
        TextAnimator animator = new TextAnimator(item, contract.effect(item.effect), contract.getSpeed(), words);
        overlays.add(new Overlay(item, look, animator, words));
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
        o.sprite = renderer.render(c.text, o.look, c.colorSlot, SIZE_FACTOR * captionSizePx,
                Math.round(MAX_WIDTH_SHARE * outW));
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
            free(o);
        }
    }

    /** A steady tilt inside the look's range, picked from the item id so a re-render matches. */
    static float tilt(PlanItem item, TextLook look) {
        if (look.tiltDeg == null) return 0;
        int h = item.id != null ? item.id.hashCode() : 0;
        double u = (h & 0x7fffffff) % 1000 / 999.0;
        double lo = look.tiltDeg[0];
        double hi = look.tiltDeg[1];
        Object asked = item.params.get("tilt_deg");
        if (asked instanceof Number) {
            double mag = Math.min(Math.abs(((Number) asked).doubleValue()), Math.max(Math.abs(lo), Math.abs(hi)));
            return (float) ((h & 1) == 0 ? mag : -mag);
        }
        return (float) (lo + (hi - lo) * u);
    }

    private void drawOverlay(Canvas canvas, Overlay o) {
        TextLookRenderer.Sprite sp = o.sprite;
        int alpha = Math.round(255 * Math.max(0f, Math.min(1f, state.alpha)));
        paint.setAlpha(alpha);
        canvas.save();
        canvas.translate(o.x + state.dx * outW, o.y + state.dy * outH);
        canvas.rotate(o.tiltDeg);
        canvas.scale(state.scale, state.scale);
        canvas.translate(-sp.width / 2f, -sp.height / 2f);
        drawBars(canvas, sp, alpha);
        if (state.words == TextAnimator.ALL_WORDS || state.words >= o.wordCount || sp.wordClips.length != o.wordCount) {
            canvas.drawBitmap(sp.bitmap, 0, 0, paint);
        } else {
            for (int w = 0; w < state.words && w < sp.wordClips.length; w++) {
                canvas.save();
                canvas.clipRect(sp.wordClips[w]);
                canvas.drawBitmap(sp.bitmap, 0, 0, paint);
                canvas.restore();
            }
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
