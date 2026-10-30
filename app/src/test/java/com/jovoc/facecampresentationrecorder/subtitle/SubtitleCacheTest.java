package com.jovoc.facecampresentationrecorder.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

public class SubtitleCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testFileForPath() {
        File cacheDir = new File("/data/user/0/com.jovoc.app/cache");
        File expected = new File(new File(cacheDir, "subtitles"), "sub_42.json");
        assertEquals(expected, SubtitleCache.fileFor(cacheDir, 42L));
        assertNull(SubtitleCache.fileFor(null, 42L));
    }

    @Test
    public void testSaveLoadDeleteCycle() throws Exception {
        File cacheDir = tmp.newFolder("cache");
        long jobId = 101L;

        assertFalse(SubtitleCache.exists(cacheDir, jobId));
        assertNull(SubtitleCache.load(cacheDir, jobId));

        SubtitleWord w1 = new SubtitleWord("Hello", 0, 500, true);
        SubtitleWord w2 = new SubtitleWord("World", 600, 1000, true);
        SubtitleTrack track = new SubtitleTrack("en", Arrays.asList(w1, w2));
        String json = track.toJsonString();

        SubtitleCache.save(cacheDir, jobId, json);

        assertTrue(SubtitleCache.exists(cacheDir, jobId));

        SubtitleTrack loaded = SubtitleCache.load(cacheDir, jobId);
        assertNotNull(loaded);
        assertEquals("en", loaded.language);
        assertEquals(2, loaded.rawWords.size());
        assertEquals("Hello", loaded.rawWords.get(0).text);
        assertEquals("World", loaded.rawWords.get(1).text);

        SubtitleCache.delete(cacheDir, jobId);
        assertFalse(SubtitleCache.exists(cacheDir, jobId));
        assertNull(SubtitleCache.load(cacheDir, jobId));
    }

    @Test
    public void testCorruptedJsonReturnsNullWithoutThrowing() throws Exception {
        File cacheDir = tmp.newFolder("cache2");
        long jobId = 202L;

        SubtitleCache.save(cacheDir, jobId, "{ not valid json :::");
        assertTrue(SubtitleCache.exists(cacheDir, jobId));

        // Corrupted file load returns null gracefully
        SubtitleTrack loaded = SubtitleCache.load(cacheDir, jobId);
        assertNull(loaded);
    }

    @Test
    public void testNullSafety() {
        SubtitleCache.save(null, 1, "{}");
        SubtitleCache.save(new File("/tmp"), 1, null);
        assertNull(SubtitleCache.load(null, 1));
        SubtitleCache.delete(null, 1);
        assertFalse(SubtitleCache.exists(null, 1));
    }

    @Test
    public void testSaveAndLoadForVideo() throws Exception {
        File cacheDir = tmp.newFolder("cache3");
        File videoDir = tmp.newFolder("videos");
        File videoFile = new File(videoDir, "presentation.mp4");
        videoFile.createNewFile();

        assertNull(SubtitleCache.loadForVideo(cacheDir, videoFile));

        SubtitleWord w1 = new SubtitleWord("Demo", 0, 400, true);
        SubtitleTrack track = new SubtitleTrack("en", Arrays.asList(w1));
        String json = track.toJsonString();

        // Save for video
        SubtitleCache.saveForVideo(cacheDir, videoFile, json);

        // Load for video from cache
        SubtitleTrack loaded = SubtitleCache.loadForVideo(cacheDir, videoFile);
        assertNotNull(loaded);
        assertEquals(1, loaded.rawWords.size());
        assertEquals("Demo", loaded.rawWords.get(0).text);

        // Also verify sidecar .sub.json was saved beside video
        File sidecar = new File(videoDir, "presentation.sub.json");
        assertTrue(sidecar.exists());

        // Verify loading with null cacheDir loads from sidecar
        SubtitleTrack fromSidecar = SubtitleCache.loadForVideo(null, videoFile);
        assertNotNull(fromSidecar);
        assertEquals("Demo", fromSidecar.rawWords.get(0).text);

        assertNull(SubtitleCache.loadForVideo(cacheDir, null));
        SubtitleCache.saveForVideo(null, null, null);
    }
}
