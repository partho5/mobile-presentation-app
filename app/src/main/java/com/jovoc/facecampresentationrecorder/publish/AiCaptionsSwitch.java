package com.jovoc.facecampresentationrecorder.publish;

/**
 * What the "AI captions" switch shows. Pure so the rule is tested without a screen: hidden without
 * a session, locked (crown, opens the upgrade sheet) when the plan lacks the feature, else the
 * stored choice (KEY_AI_CAPTIONS, on by default). A lock never overwrites the stored choice.
 */
public final class AiCaptionsSwitch {

    public enum State { HIDDEN, LOCKED, ON, OFF }

    private AiCaptionsSwitch() {
    }

    public static State state(boolean signedIn, boolean planHasFeature, boolean storedChoice) {
        if (!signedIn) return State.HIDDEN;
        if (!planHasFeature) return State.LOCKED;
        return storedChoice ? State.ON : State.OFF;
    }

    /** True only when AI captions will really run: the switch is on and the plan covers it. */
    public static boolean active(boolean signedIn, boolean planHasFeature, boolean storedChoice) {
        return state(signedIn, planHasFeature, storedChoice) == State.ON;
    }

    /** The price line is shown only when the server sent a price (an older one does not). */
    public static boolean showsCostPerMinute(long costPerMinute) {
        return costPerMinute != AutoPublishInfo.COST_UNKNOWN;
    }
}
