package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

import android.graphics.Canvas;

import com.jovoc.facecampresentationrecorder.aiedit.engine.RenderNotes;
import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.CaptionPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.OutputSpec;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanComponent;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.TextPlacement;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the icon items of a plan ({@code i_scale_pop}): each icon image or mp4 clip (its keyed frames) is loaded
 * when its item first appears, placed in its zone inside the safe rectangle and off the facecam, animated by
 * {@link IconAnimator}, and freed when the item ends. An item whose file is missing or unreadable, or that fits
 * nowhere clear of the facecam, is left out; the rest of the edit is unaffected. Nothing is allocated per frame.
 */
public final class IconOverlayPainter implements CaptionPainter {

    /** The effect ids this painter draws; the other {@code i_*} ids are not in {@code Capabilities} yet. */
    static final String SCALE_POP = "i_scale_pop";
    /** The longer side of an icon is this share of the output width. */
    static final float SIZE_SHARE = 0.26f;
    static final String DEFAULT_ZONE = "upper_right";

    /** One icon item: its animation, and the layer and position once it has been built. */
    private static final class Overlay {
        final PlanItem item;
        /** The icon image file, or the folder of keyed frames of an mp4 clip. */
        final File file;
        final IconAnimator animator;
        OverlayLayer layer;
        float x;
        float y;
        float boxW;
        float boxH;
        boolean built;
        /** The file cannot be drawn or fits nowhere, or the layer is already freed. */
        boolean skipped;

        Overlay(PlanItem item, File file, IconAnimator animator) {
            this.item = item;
            this.file = file;
            this.animator = animator;
        }
    }

    private final EngineContract contract;
    private final OutputSpec spec;
    private final int outW;
    private final int outH;
    private final TextPlacement.Keepout facecam;
    private final RenderNotes notes;
    private final List<Overlay> overlays = new ArrayList<>();
    private final IconAnimator.State state = new IconAnimator.State();
    private final float[] spot = new float[2];

    /**
     * @param assetsDir where the downloaded assets are, or null for none (then nothing is drawn)
     * @param facecam   where the facecam is in output pixels, or null for none
     */
    public IconOverlayPainter(Plan plan, EngineContract contract, OutputSpec spec, int outW, int outH,
                              File assetsDir, TextPlacement.Keepout facecam) {
        this(plan, contract, spec, outW, outH, assetsDir, facecam, null);
    }

    /** @param notes where a missing asset or a skipped icon is noted (codes and item ids only), or null for none */
    public IconOverlayPainter(Plan plan, EngineContract contract, OutputSpec spec, int outW, int outH,
                              File assetsDir, TextPlacement.Keepout facecam, RenderNotes notes) {
        this.notes = notes != null ? notes : new RenderNotes();
        this.contract = contract;
        this.spec = spec;
        this.outW = outW;
        this.outH = outH;
        this.facecam = facecam;
        for (PlanItem item : plan.items) addItem(item, plan, assetsDir);
    }

    private void addItem(PlanItem item, Plan plan, File assetsDir) {
        PlanComponent c = item.component;
        if (c == null || c.isText() || c.assetId == null || item.endMs <= item.startMs) return;
        if (!SCALE_POP.equals(item.effect)) return;
        boolean clip = PlanComponent.ICON_VIDEO.equals(c.type);
        File file = clip
                ? AssetFiles.existingFrames(assetsDir, c.assetId)
                : AssetFiles.existing(assetsDir, c.assetId);
        if (file == null || plan.asset(c.assetId) == null) {
            notes.add(RenderNotes.ASSET_MISSING, item.id);
            return;
        }
        overlays.add(new Overlay(item, file,
                new IconAnimator(item, contract.effect(item.effect), contract.getSpeed())));
    }

    /** How many icon items will be drawn. */
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
            canvas.save();
            canvas.translate(o.x, o.y);
            canvas.scale(state.scale, state.scale);
            o.layer.draw(canvas, o.boxW, o.boxH, outputMs - o.item.startMs,
                    Math.round(255 * Math.max(0f, Math.min(1f, state.alpha))));
            canvas.restore();
            drew = true;
        }
        return drew;
    }

    private void free(Overlay o) {
        if (o.layer != null) {
            o.layer.release();
            o.layer = null;
        }
        o.skipped = true;
    }

    /** Loads the picture and finds its spot: the zone centre, kept in the safe rectangle and off the facecam. */
    private void build(Overlay o) {
        o.built = true;
        int side = Math.max(1, Math.round(SIZE_SHARE * outW));
        PlanComponent c = o.item.component;
        o.layer = PlanComponent.ICON_VIDEO.equals(c.type)
                ? FrameSequenceLayer.load(o.file,
                        ClipFit.factor(c.fitMode, c.fitFactor, contract.getSpeed().clipMaxFactor))
                : IconLayer.load(o.file, side);
        if (o.layer == null) {
            notes.add(RenderNotes.ASSET_MISSING, o.item.id);
            o.skipped = true;
            return;
        }
        // A square box holds the longer side; the picture keeps its own proportions inside it
        float aspect = o.layer.aspect();
        o.boxW = side;
        o.boxH = side;
        float halfW = (aspect >= 1 ? side : side * aspect) / 2f;
        float halfH = (aspect >= 1 ? side / aspect : side) / 2f;
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
}
