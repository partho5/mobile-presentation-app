package com.jovoc.facecampresentationrecorder.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UserBackgroundsTest {

    @Test
    public void allowsUploadsUpToTheLimit() {
        assertTrue(UserBackgrounds.canAddMore(0));
        assertTrue(UserBackgrounds.canAddMore(UserBackgrounds.MAX_UPLOADS - 1));
        assertFalse(UserBackgrounds.canAddMore(UserBackgrounds.MAX_UPLOADS));
        assertFalse(UserBackgrounds.canAddMore(UserBackgrounds.MAX_UPLOADS + 1));
    }

    @Test
    public void limitIsFive() {
        assertEquals(5, UserBackgrounds.MAX_UPLOADS);
    }

    @Test
    public void tellsUploadedFilesFromBundledAssets() {
        assertTrue(UserBackgrounds.isUserPath("/data/user/0/app/files/user_backgrounds/bg_1.jpg"));
        assertFalse(UserBackgrounds.isUserPath("bg_samples/room.jpg"));
        assertFalse(UserBackgrounds.isUserPath(""));
        assertFalse(UserBackgrounds.isUserPath(null));
    }

    @Test
    public void smallImagesAreNotDownsampled() {
        assertEquals(1, UserBackgrounds.sampleSizeFor(800, 600, 1920));
        assertEquals(1, UserBackgrounds.sampleSizeFor(1920, 1080, 1920));
    }

    @Test
    public void largeImagesAreSampledButStayAtLeastTargetSize() {
        assertEquals(2, UserBackgrounds.sampleSizeFor(4000, 3000, 1920));
        // 12000 / 4 = 3000 >= 1920, but 12000 / 8 = 1500 would be too small
        assertEquals(4, UserBackgrounds.sampleSizeFor(12000, 9000, 1920));
        // Portrait: the longest edge is the height
        assertEquals(2, UserBackgrounds.sampleSizeFor(3000, 4000, 1920));
    }
}
