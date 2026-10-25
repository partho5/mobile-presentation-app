package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EditGroupMathTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String file(String name, boolean content) throws IOException {
        File f = tmp.newFile(name);
        if (content) Files.write(f.toPath(), new byte[]{1, 2, 3});
        return f.getAbsolutePath();
    }

    @Test
    public void cropNamesAreRecognised() {
        assertTrue(EditGroupMath.isCropName("clip_9x16.mp4"));
        assertTrue(EditGroupMath.isCropName("clip_edit_1x1.mp4"));
        assertFalse(EditGroupMath.isCropName("clip.mp4"));
        assertFalse(EditGroupMath.isCropName("clip_edit.mp4"));
    }

    @Test
    public void masterIsTheNonCropFile() throws IOException {
        String crop = file("clip_9x16.mp4", true);
        String master = file("clip.mp4", true);
        assertEquals(master, EditGroupMath.masterOf(Arrays.asList(crop, master)));
    }

    @Test
    public void missingOrEmptyMasterGivesNull() throws IOException {
        String crop = file("clip_9x16.mp4", true);
        String empty = file("clip.mp4", false);
        assertNull(EditGroupMath.masterOf(Arrays.asList(crop, empty)));
        assertNull(EditGroupMath.masterOf(Collections.<String>emptyList()));
    }

    @Test
    public void ratiosComeNineSixteenFirst() {
        List<String> paths = Arrays.asList("/a/clip_1x1.mp4", "/a/clip.mp4", "/a/clip_4x5.mp4",
                "/a/clip_9x16.mp4", "/a/clip_3x4.mp4");
        List<int[]> ratios = EditGroupMath.cropRatiosOf(paths);
        assertEquals(3, ratios.size());
        assertArrayEquals(new int[]{9, 16}, ratios.get(0));
        assertArrayEquals(new int[]{4, 5}, ratios.get(1));
        assertArrayEquals(new int[]{1, 1}, ratios.get(2));
    }

    @Test
    public void noCropsMeansNoRatios() {
        assertTrue(EditGroupMath.cropRatiosOf(Collections.singletonList("/a/clip.mp4")).isEmpty());
    }
}
