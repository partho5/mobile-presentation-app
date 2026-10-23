package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.publish.AiCaptionsSwitch.State;

import org.junit.Test;

import java.util.Arrays;

public class AiCaptionsSwitchTest {

    @Test
    public void defaultLowestPlanIsStarter() {
        assertEquals("starter", Feature.DEFAULT_LOWEST.get(Feature.AI_CAPTIONS));
        assertEquals("ai_captions", Feature.AI_CAPTIONS);
        assertFalse(Feature.isFreeByDefault(Feature.AI_CAPTIONS));
        assertFalse(Feature.planCovers("free", Feature.DEFAULT_LOWEST.get(Feature.AI_CAPTIONS)));
        assertTrue(Feature.planCovers("starter", Feature.DEFAULT_LOWEST.get(Feature.AI_CAPTIONS)));
    }

    @Test
    public void noSessionHidesTheSwitch() {
        assertEquals(State.HIDDEN, AiCaptionsSwitch.state(false, true, true));
        assertEquals(State.HIDDEN, AiCaptionsSwitch.state(false, false, false));
    }

    @Test
    public void freePlanIsLockedWhateverWasStored() {
        assertEquals(State.LOCKED, AiCaptionsSwitch.state(true, false, true));
        assertEquals(State.LOCKED, AiCaptionsSwitch.state(true, false, false));
        assertFalse(AiCaptionsSwitch.active(true, false, true));
    }

    @Test
    public void paidPlanFollowsTheStoredChoice() {
        assertEquals(State.ON, AiCaptionsSwitch.state(true, true, true));
        assertEquals(State.OFF, AiCaptionsSwitch.state(true, true, false));
        assertTrue(AiCaptionsSwitch.active(true, true, true));
        assertFalse(AiCaptionsSwitch.active(true, true, false));
    }

    @Test
    public void priceLineNeedsAPriceFromTheServer() {
        assertFalse(AiCaptionsSwitch.showsCostPerMinute(AutoPublishInfo.COST_UNKNOWN));
        assertTrue(AiCaptionsSwitch.showsCostPerMinute(0));
        assertTrue(AiCaptionsSwitch.showsCostPerMinute(1));
    }

    @Test
    public void autoPublishInfoReadsTheCostPerMinute() throws Exception {
        AutoPublishInfo with = AutoPublishInfo.parse(
                "{\"configured\":true,\"channels\":[],\"aiCaptions\":{\"costPerMinute\":2}}");
        assertEquals(2L, with.aiCaptionsCostPerMinute);
        AutoPublishInfo free = AutoPublishInfo.parse(
                "{\"configured\":true,\"channels\":[],\"aiCaptions\":{\"costPerMinute\":0}}");
        assertEquals(0L, free.aiCaptionsCostPerMinute);
        AutoPublishInfo old = AutoPublishInfo.parse("{\"configured\":true,\"channels\":[]}");
        assertEquals(AutoPublishInfo.COST_UNKNOWN, old.aiCaptionsCostPerMinute);
    }

    @Test
    public void chargePreviewSendsAiSecondsOnlyWhenAsked() throws Exception {
        org.json.JSONObject asked = new org.json.JSONObject(
                PublishRequestBuilder.chargePreviewBody(Arrays.asList("a"), 170));
        assertEquals(170, asked.getInt("aiCaptionsSeconds"));
        org.json.JSONObject plain = new org.json.JSONObject(
                PublishRequestBuilder.chargePreviewBody(Arrays.asList("a"), -1));
        assertFalse(plain.has("aiCaptionsSeconds"));
        org.json.JSONObject legacy = new org.json.JSONObject(
                PublishRequestBuilder.chargePreviewBody(Arrays.asList("a")));
        assertFalse(legacy.has("aiCaptionsSeconds"));
    }

    @Test
    public void chargePreviewParsesAiCredits() throws Exception {
        ChargePreview p = ChargePreview.parse("{\"amount\":3,\"aiCaptions\":2}");
        assertEquals(3, p.amount);
        assertEquals(2, p.aiCaptions);
        ChargePreview old = ChargePreview.parse("{\"amount\":1}");
        assertEquals(0, old.aiCaptions);
    }
}
