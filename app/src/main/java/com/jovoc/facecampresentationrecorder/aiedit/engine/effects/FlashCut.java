package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * {@code tr_flash_cut}: a white flash that peaks on the cut point (the middle of the item, where the plan centres
 * a transition) and fades on both sides, hiding the jump between the two shots.
 */
public final class FlashCut implements VideoEffect {

    public static final String ID = "tr_flash_cut";

    final long startMs;
    final long durationMs;
    final double peakAlpha;

    public FlashCut(PlanItem item, RegistryEntry entry, EngineContract.SpeedRules speed) {
        startMs = item.startMs;
        durationMs = Params.clamp(item.durationMs(), speed.transitionMs[0], speed.transitionMs[1]);
        peakAlpha = Params.number(item, entry, "peak_alpha", 0.85);
    }

    /** The white laid over the frame at {@code outputMs}: 0 outside the transition, the peak at its middle. */
    double alphaAt(long outputMs) {
        if (outputMs < startMs || outputMs >= startMs + durationMs) return 0;
        double p = Ease.progress(outputMs, startMs, durationMs);
        if (p < 0.5) return peakAlpha * Ease.outCubic(p * 2);
        return peakAlpha * (1 - Ease.outCubic((p - 0.5) * 2));
    }

    @Override
    public boolean apply(long outputMs, VideoTransform transform) {
        if (outputMs < startMs || outputMs >= startMs + durationMs) return false;
        transform.flash(alphaAt(outputMs));
        return true;
    }
}
