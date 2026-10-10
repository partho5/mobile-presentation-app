package com.jovoc.facecampresentationrecorder.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public class SettingsHelpTest {

    @Test
    public void unknownOrNullSectionHasNoHelp() {
        assertTrue(SettingsHelp.forSection("nope").isEmpty());
        assertTrue(SettingsHelp.forSection(null).isEmpty());
    }

    @Test
    public void everyRegisteredSectionIsWellFormed() {
        assertFalse(SettingsHelp.sectionIds().isEmpty());
        for (String id : SettingsHelp.sectionIds()) {
            List<SettingsHelp.Block> blocks = SettingsHelp.forSection(id);
            assertFalse(id + " is registered but empty", blocks.isEmpty());
            for (SettingsHelp.Block b : blocks) {
                switch (b.type) {
                    case TEXT:
                    case IMAGE:
                        assertNotEquals(id, 0, b.resId);
                        break;
                    case YOUTUBE:
                        assertTrue(SettingsHelp.isValidVideoId(b.videoId));
                        break;
                }
            }
        }
    }

    @Test
    public void helpKeepsBlockOrder() {
        List<SettingsHelp.Block> blocks = SettingsHelp.help(
                SettingsHelp.text(1), SettingsHelp.image(2, 3), SettingsHelp.youtube("abcdEFGhij_"));
        assertEquals(3, blocks.size());
        assertEquals(SettingsHelp.Type.TEXT, blocks.get(0).type);
        assertEquals(SettingsHelp.Type.IMAGE, blocks.get(1).type);
        assertEquals(3, blocks.get(1).descResId);
        assertEquals(SettingsHelp.Type.YOUTUBE, blocks.get(2).type);
        assertEquals("abcdEFGhij_", blocks.get(2).videoId);
    }

    @Test
    public void youtubeRejectsMalformedIds() {
        for (String bad : new String[]{"", "short", "has space!!", "waytoolongvideoid", null}) {
            try {
                SettingsHelp.youtube(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void youtubeUrls() {
        assertEquals("https://www.youtube-nocookie.com/embed/abcdEFGhij_?playsinline=1&rel=0",
                SettingsHelp.embedUrl("abcdEFGhij_"));
        assertEquals("https://www.youtube.com/watch?v=abcdEFGhij_", SettingsHelp.watchUrl("abcdEFGhij_"));
    }
}
