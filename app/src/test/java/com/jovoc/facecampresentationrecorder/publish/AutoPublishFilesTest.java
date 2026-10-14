package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Which file auto-publish uploads: the 9:16 crop when there is one, else the original. */
public class AutoPublishFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File write(String name, int bytes) throws IOException {
        File f = tmp.newFile(name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[bytes]);
        }
        return f;
    }

    @Test
    public void picksTheNineSixteenCropWhenItExists() throws Exception {
        File original = write("FaceCam_1.mp4", 100);
        File crop = write("FaceCam_1_9x16.mp4", 50);
        assertEquals(crop, AutoPublishFiles.pick(original));
    }

    @Test
    public void picksTheOriginalWhenThereIsNoCrop() throws Exception {
        File original = write("FaceCam_1.mp4", 100);
        assertEquals(original, AutoPublishFiles.pick(original));
    }

    @Test
    public void ignoresOtherRatiosAndEmptyCrops() throws Exception {
        File original = write("FaceCam_1.mp4", 100);
        write("FaceCam_1_4x5.mp4", 50);
        write("FaceCam_1_1x1.mp4", 50);
        write("FaceCam_1_9x16.mp4", 0);
        assertEquals(original, AutoPublishFiles.pick(original));
    }

    @Test
    public void renamedRecordingStillGetsTheCropMadeUnderItsProcessingName() throws Exception {
        // Renamed to FaceCam_new while processing; the crop was written next to FaceCam_old.
        File renamed = write("FaceCam_new.mp4", 100);
        File processedAs = new File(tmp.getRoot(), "FaceCam_old.mp4");
        File crop = write("FaceCam_old_9x16.mp4", 50);
        assertEquals(crop, AutoPublishFiles.pick(renamed, processedAs));
    }

    @Test
    public void cropUnderTheCurrentNameWinsAndNoCropFallsBackToTheRenamedOriginal() throws Exception {
        File renamed = write("FaceCam_new.mp4", 100);
        File processedAs = new File(tmp.getRoot(), "FaceCam_old.mp4");
        assertEquals(renamed, AutoPublishFiles.pick(renamed, processedAs));
        File newCrop = write("FaceCam_new_9x16.mp4", 50);
        write("FaceCam_old_9x16.mp4", 50);
        assertEquals(newCrop, AutoPublishFiles.pick(renamed, processedAs));
    }
}
