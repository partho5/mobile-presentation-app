package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * {@code v_alt_crop}: the second virtual camera. The picture switches to a close crop ({@code scale_to} about the
 * point {@code center_x} / {@code center_y}) within {@code switch_ms}, stays there for the item, and switches back.
 */
public final class AltCrop implements VideoEffect {

    public static final String ID = "v_alt_crop";

    private static final double DEFAULT_SCALE = 1.18;

    final long startMs;
    final long endMs;
    final long switchMs;
    final double scaleTo;
    final String ease;
    private final double cx;
    private final double cy;

    public AltCrop(PlanItem item, RegistryEntry entry) {
        startMs = item.startMs;
        endMs = item.endMs;
        long wanted = Math.round(Params.number(item, entry, "switch_ms", 120));
        switchMs = Math.max(0, Math.min(wanted, (endMs - startMs) / 2));
        scaleTo = Math.max(1, Params.number(item, entry, "scale_to", DEFAULT_SCALE));
        ease = Params.ease(item, entry, "out_cubic");
        cx = Params.number(item, entry, "center_x", 0.5);
        cy = Params.number(item, entry, "center_y", 0.4);
    }

    /** The zoom at {@code outputMs}: 1 outside the item. */
    double scaleAt(long outputMs) {
        if (outputMs < startMs || outputMs >= endMs) return 1;
        if (outputMs < startMs + switchMs) {
            return Ease.lerp(1, scaleTo, Ease.byName(ease, Ease.progress(outputMs, startMs, switchMs)));
        }
        if (outputMs >= endMs - switchMs) {
            return Ease.lerp(scaleTo, 1, Ease.outCubic(Ease.progress(outputMs, endMs - switchMs, switchMs)));
        }
        return scaleTo;
    }

    @Override
    public boolean apply(long outputMs, VideoTransform transform) {
        if (outputMs < startMs || outputMs >= endMs) return false;
        transform.zoom(scaleAt(outputMs), cx, cy);
        return true;
    }
}
