package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;

public class PlatformsTest {

    private static final String FULL = "{\"canManageCredentials\":false,"
            + "\"autoPublish\":{\"mode\":\"preset\",\"presetName\":\"Reels\"},\"platforms\":["
            + "{\"identifier\":\"youtube\",\"name\":\"YouTube\",\"icon\":\"https://w/icons/platforms/youtube.png\","
            + "\"group\":\"video\",\"status\":\"connected\",\"guideUrl\":\"https://w/g\",\"autoPublishSupported\":true,"
            + "\"channels\":[{\"id\":\"c1\",\"name\":\"My YT\",\"picture\":null,\"disabled\":false,"
            + "\"refreshNeeded\":true,\"autoPublish\":true},{\"name\":\"no id\"}]},"
            + "{\"identifier\":\"tiktok\",\"name\":\"Tik\\nTok\",\"icon\":null,\"group\":\"video\","
            + "\"status\":\"needs_credentials\",\"autoPublishSupported\":true,\"channels\":[]},"
            + "{\"name\":\"no identifier\"},"
            + "{\"identifier\":\"discord\",\"name\":\"Discord\",\"group\":\"other\",\"status\":\"ready_to_connect\"}]}";

    @Test
    public void parsesPlatformsChannelsAndPreset() throws Exception {
        Platforms p = Platforms.parse(FULL);
        assertFalse(p.canManageCredentials);
        assertTrue(p.usesPreset());
        assertEquals("Reels", p.presetName);
        assertEquals(3, p.platforms.size());
        assertTrue(p.anyConnected());

        Platforms.Platform yt = p.platforms.get(0);
        assertEquals("youtube", yt.identifier);
        assertTrue(yt.isConnected());
        assertTrue(yt.isVideo());
        assertTrue(yt.autoPublishSupported);
        assertEquals("https://w/icons/platforms/youtube.png", yt.icon);
        assertEquals(1, yt.channels.size());
        Platforms.ChannelState c = yt.channels.get(0);
        assertEquals("c1", c.id);
        assertNull(c.picture);
        assertTrue(c.refreshNeeded);
        assertTrue(c.autoPublish);

        Platforms.Platform tt = p.find("tiktok");
        assertEquals("Tik Tok", tt.name);
        assertEquals("", tt.icon);
        assertTrue(tt.needsCredentials());

        Platforms.Platform discord = p.platforms.get(2);
        assertFalse(discord.isVideo());
        assertFalse(discord.isConnected());
        assertFalse(discord.autoPublishSupported);
        assertTrue(discord.channels.isEmpty());
    }

    @Test
    public void missingAutoPublish_meansAutomatic() throws Exception {
        Platforms p = Platforms.parse("{\"platforms\":[]}");
        assertFalse(p.usesPreset());
        assertEquals("", p.presetName);
        assertFalse(p.anyConnected());
    }

    @Test
    public void noPlatformsArray_isAnError() {
        try {
            Platforms.parse("{\"canManageCredentials\":true}");
            fail();
        } catch (JSONException expected) {
            // mapped to a SERVER failure by PublishApi
        }
    }
}
