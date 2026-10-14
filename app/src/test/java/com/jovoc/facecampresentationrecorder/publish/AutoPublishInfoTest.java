package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;

public class AutoPublishInfoTest {

    private static final String CONFIGURED = "{\"configured\":true,\"setName\":\"Reels\","
            + "\"timing\":\"next_slot\",\"channels\":["
            + "{\"id\":\"c1\",\"name\":\"My YT\",\"identifier\":\"youtube\",\"picture\":null,\"disabled\":false},"
            + "{\"id\":\"c2\",\"name\":\"My TT\",\"identifier\":\"tiktok\",\"picture\":\"https://x/p.png\",\"disabled\":false},"
            + "{\"id\":\"c3\",\"name\":\"Off\",\"identifier\":\"instagram\",\"disabled\":true},"
            + "{\"id\":\"c4\",\"name\":\"My YT 2\",\"identifier\":\"youtube\",\"disabled\":false}],"
            + "\"cost\":6,\"available\":120,"
            + "\"setupUrl\":\"https://app.example.com/w/o1/settings?tab=sets\"}";

    @Test
    public void configured_isParsedWithChannelsCostAndBalance() throws Exception {
        AutoPublishInfo i = AutoPublishInfo.parse(CONFIGURED);
        assertTrue(i.configured);
        assertEquals("Reels", i.setName);
        assertEquals("next_slot", i.timing);
        assertEquals(4, i.channels.size());
        assertEquals(3, i.activeChannels().size());
        assertEquals(6L, i.cost);
        assertEquals(120L, i.available);
        assertEquals("https://app.example.com/w/o1/settings?tab=sets", i.setupUrl);
        assertTrue(i.canAfford());
    }

    @Test
    public void providerNames_areDistinct_ordered_andSkipDisabledChannels() throws Exception {
        assertEquals(Arrays.asList("YouTube", "TikTok"),
                AutoPublishInfo.parse(CONFIGURED).providerNames());
    }

    @Test
    public void notConfigured_hasNoSetNameOrChannels() throws Exception {
        AutoPublishInfo i = AutoPublishInfo.parse("{\"configured\":false,\"channels\":[],"
                + "\"cost\":0,\"available\":3,\"setupUrl\":\"https://app.example.com/w/o1/settings?tab=sets\"}");
        assertFalse(i.configured);
        assertNull(i.setName);
        assertNull(i.timing);
        assertTrue(i.channels.isEmpty());
        assertEquals(3L, i.available);
        assertEquals("https://app.example.com/w/o1/settings?tab=sets", i.setupUrl);
    }

    @Test
    public void nullFields_andMissingOptionals_areTolerated() throws Exception {
        AutoPublishInfo i = AutoPublishInfo.parse(
                "{\"configured\":true,\"setName\":null,\"timing\":null}");
        assertTrue(i.configured);
        assertNull(i.setName);
        assertNull(i.timing);
        assertTrue(i.channels.isEmpty());
        assertEquals(0L, i.cost);
        assertEquals("", i.setupUrl);
    }

    @Test
    public void costAboveBalance_isNotAffordable() throws Exception {
        AutoPublishInfo i = AutoPublishInfo.parse(
                "{\"configured\":true,\"cost\":10,\"available\":4,\"channels\":[]}");
        assertFalse(i.canAfford());
    }

    @Test
    public void withoutConfigured_isAParseError() {
        try {
            AutoPublishInfo.parse("{\"cost\":1}");
            fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
        try {
            AutoPublishInfo.parse("<html>");
            fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
    }

    @Test
    public void providerName_mapsKnownBrands_andTidiesTheRest() {
        assertEquals("YouTube", AutoPublishInfo.providerName("youtube"));
        assertEquals("TikTok", AutoPublishInfo.providerName("tiktok"));
        assertEquals("LinkedIn", AutoPublishInfo.providerName("linkedin-page"));
        assertEquals("Instagram", AutoPublishInfo.providerName("instagram-standalone"));
        assertEquals("X", AutoPublishInfo.providerName("x"));
        assertEquals("Threads", AutoPublishInfo.providerName("threads"));
        assertEquals("", AutoPublishInfo.providerName(null));
    }
}
