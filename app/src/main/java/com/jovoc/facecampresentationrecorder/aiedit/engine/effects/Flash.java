package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * {@code l_flash}: a white flash on a beat. It reaches {@code peak_alpha} within the first fifth of the item and
 * fades out with out-cubic; the whole flash lasts inside the contract's {@code flash_ms}.
 */
public final class Flash implements VideoEffect {

    public static final String ID = "l_flash";

    /** Share of the flash spent rising to the peak. */
    static final double ATTACK_SHARE = 0.2;

    final long startMs;
    final long durationMs;
    final double peakAlpha;

    public Flash(PlanItem item, RegistryEntry entry, EngineContract.SpeedRules speed) {
        startMs = item.startMs;
        durationMs = Params.clamp(item.durationMs(), speed.flashMs[0], speed.flashMs[1]);
        peakAlpha = Params.number(item, entry, "peak_alpha", 0.85);
    }

    /** The white laid over the frame at {@code outputMs}: 0 outside the flash. */
    double alphaAt(long outputMs) {
        if (outputMs < startMs || outputMs >= startMs + durationMs) return 0;
        double p = Ease.progress(outputMs, startMs, durationMs);
        if (p < ATTACK_SHARE) return peakAlpha * (p / ATTACK_SHARE);
        return peakAlpha * (1 - Ease.outCubic((p - ATTACK_SHARE) / (1 - ATTACK_SHARE)));
    }

    @Override
    public boolean apply(long outputMs, VideoTransform transform) {
        if (outputMs < startMs || outputMs >= startMs + durationMs) return false;
        transform.flash(alphaAt(outputMs));
        return true;
    }
}
