package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * {@code v_push_in}: a fast push to {@code scale_to} (106 to 110 %) across the item, then a quick ease back to 100 %
 * so the next shot starts on the plain frame. The zoom centre is the output centre unless the item gives
 * {@code cx} / {@code cy}.
 */
public final class PushIn implements VideoEffect {

    public static final String ID = "v_push_in";

    /** Share of the item spent pushing in; the rest eases back. */
    static final double IN_SHARE = 0.7;

    private static final double DEFAULT_SCALE = 1.08;

    final long startMs;
    final long endMs;
    final double scaleTo;
    final String ease;
    private final double cx;
    private final double cy;

    public PushIn(PlanItem item, RegistryEntry entry) {
        startMs = item.startMs;
        endMs = item.endMs;
        scaleTo = Math.max(1, Params.number(item, entry, "scale_to", DEFAULT_SCALE));
        ease = Params.ease(item, entry, "out_cubic");
        cx = item.paramNumber("cx", 0.5);
        cy = item.paramNumber("cy", 0.5);
    }

    /** The zoom at {@code outputMs}: 1 outside the item. */
    double scaleAt(long outputMs) {
        if (outputMs < startMs || outputMs >= endMs) return 1;
        double p = Ease.progress(outputMs, startMs, endMs - startMs);
        if (p < IN_SHARE) return 1 + (scaleTo - 1) * Ease.byName(ease, p / IN_SHARE);
        return Ease.lerp(scaleTo, 1, Ease.outCubic((p - IN_SHARE) / (1 - IN_SHARE)));
    }

    @Override
    public boolean apply(long outputMs, VideoTransform transform) {
        if (outputMs < startMs || outputMs >= endMs) return false;
        transform.zoom(scaleAt(outputMs), cx, cy);
        return true;
    }
}
