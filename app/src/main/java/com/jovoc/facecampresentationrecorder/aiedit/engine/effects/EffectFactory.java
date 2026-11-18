package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the camera, look, layout and transition items of a plan into {@link VideoEffect}s; ids this build does not
 * draw are skipped.
 */
public final class EffectFactory {

    private EffectFactory() {}

    /** The video effects of {@code plan} for an output without a known facecam position, in the plan's order. */
    public static List<VideoEffect> videoEffects(Plan plan, EngineContract contract) {
        return videoEffects(plan, contract, null);
    }

    /**
     * The video effects of {@code plan}, in start order (the plan's own order). {@code facecam} says where the
     * facecam is in this output; the layout effects are left out when it is null or the crop cuts it off.
     */
    public static List<VideoEffect> videoEffects(Plan plan, EngineContract contract, FacecamSource facecam) {
        List<VideoEffect> out = new ArrayList<>();
        for (PlanItem item : plan.items) {
            VideoEffect effect = create(item, contract, facecam);
            if (effect != null) out.add(effect);
        }
        return out;
    }

    /** {@link #create(PlanItem, EngineContract, FacecamSource)} for an output without a known facecam position. */
    public static VideoEffect create(PlanItem item, EngineContract contract) {
        return create(item, contract, null);
    }

    /** The effect of one item, or null when the item is not a video effect this build renders for this output. */
    public static VideoEffect create(PlanItem item, EngineContract contract, FacecamSource facecam) {
        if (item.effect == null) return null;
        switch (item.effect) {
            case PunchIn.ID:
                return new PunchIn(item, contract.effect(item.effect), contract.getSpeed());
            case PushIn.ID:
                return new PushIn(item, contract.effect(item.effect));
            case AltCrop.ID:
                return new AltCrop(item, contract.effect(item.effect));
            case Flash.ID:
                return new Flash(item, contract.effect(item.effect), contract.getSpeed());
            case RgbGlitch.ID:
                return new RgbGlitch(item, contract.effect(item.effect));
            case FlashCut.ID:
                return new FlashCut(item, contract.effect(item.effect), contract.getSpeed());
            case Whip.ID:
                return new Whip(item, contract.effect(item.effect), contract.getSpeed());
            case FaceGrowSlideShrink.ID:
                return FaceLayout.fits(item, facecam)
                        ? new FaceGrowSlideShrink(item, contract.effect(item.effect), facecam) : null;
            case SlideGrowFaceShrink.ID:
                return FaceLayout.fits(item, facecam)
                        ? new SlideGrowFaceShrink(item, contract.effect(item.effect), facecam) : null;
            default:
                return null;
        }
    }
}
