package com.jovoc.facecampresentationrecorder.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class VideoPlayerDialogTest {

    @Test
    public void testCleanSubtitleStatusTextStripsLeadingEmojis() {
        assertEquals("Generating AI subtitles…", VideoPlayerDialog.cleanSubtitleStatusText("⏳ Generating AI subtitles…"));
        assertEquals("Embedding subtitles…", VideoPlayerDialog.cleanSubtitleStatusText("⏳ Embedding subtitles…"));
        assertEquals("Subtitles embedded", VideoPlayerDialog.cleanSubtitleStatusText("✓ Subtitles embedded"));
        assertEquals("Subtitle embed failed", VideoPlayerDialog.cleanSubtitleStatusText("✗ Subtitle embed failed"));
        assertEquals("Subtitles ready", VideoPlayerDialog.cleanSubtitleStatusText("✓ Subtitles ready"));
        assertEquals("Plain text without emoji", VideoPlayerDialog.cleanSubtitleStatusText("Plain text without emoji"));
        assertEquals("", VideoPlayerDialog.cleanSubtitleStatusText(null));
    }

    @Test
    public void testExtractSubtitleStatusIcon() {
        assertEquals("⏳", VideoPlayerDialog.extractSubtitleStatusIcon("⏳ Generating AI subtitles…"));
        assertEquals("✓", VideoPlayerDialog.extractSubtitleStatusIcon("✓ Subtitles embedded"));
        assertEquals("✗", VideoPlayerDialog.extractSubtitleStatusIcon("✗ Subtitle embed failed"));
        assertEquals("⏳", VideoPlayerDialog.extractSubtitleStatusIcon("Embedding 9:16 (1/2)…"));
        assertEquals("⏳", VideoPlayerDialog.extractSubtitleStatusIcon(null));
    }

    @Test
    public void testRatioLabelOf() {
        assertEquals("9:16", VideoPlayerDialog.ratioLabelOf(new File("/path/to/vid_9x16.mp4")));
        assertEquals("4:5", VideoPlayerDialog.ratioLabelOf(new File("/path/to/vid_4x5.mp4")));
        assertEquals("1:1", VideoPlayerDialog.ratioLabelOf(new File("/path/to/vid_1x1.mp4")));
        assertEquals("variant", VideoPlayerDialog.ratioLabelOf(new File("/path/to/vid_raw.mp4")));
        assertEquals("variant", VideoPlayerDialog.ratioLabelOf(null));
    }

    @Test
    public void testFilterCropVariantPathsExcludesMasterAndNonCrops() {
        String master = "/storage/emulated/0/DCIM/talk.mp4";
        String crop916 = "/storage/emulated/0/DCIM/talk_9x16.mp4";
        String crop45 = "/storage/emulated/0/DCIM/talk_4x5.mp4";
        String crop11 = "/storage/emulated/0/DCIM/talk_1x1.mp4";
        String otherFile = "/storage/emulated/0/DCIM/unrelated.mp4";

        List<String> groupPaths = Arrays.asList(master, crop916, crop45, crop11, otherFile);

        List<String> filtered = VideoPlayerDialog.filterCropVariantPaths(groupPaths, master);

        assertEquals(3, filtered.size());
        assertTrue(filtered.contains(crop916));
        assertTrue(filtered.contains(crop45));
        assertTrue(filtered.contains(crop11));

        // Strictly verify master and non-crop files are NEVER in the variants list
        assertFalse("Master file must NEVER be included in variant targets", filtered.contains(master));
        assertFalse("Unrelated non-crop files must not be included", filtered.contains(otherFile));
    }

    @Test
    public void testFilterCropVariantPathsHandlesEmptyAndNullGracefully() {
        assertTrue(VideoPlayerDialog.filterCropVariantPaths(null, "/path/master.mp4").isEmpty());
        assertTrue(VideoPlayerDialog.filterCropVariantPaths(Collections.emptyList(), "/path/master.mp4").isEmpty());
        assertTrue(VideoPlayerDialog.filterCropVariantPaths(Collections.singletonList("/path/master.mp4"), "/path/master.mp4").isEmpty());
    }
}
