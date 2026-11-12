package com.jovoc.facecampresentationrecorder.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

public class SubtitleEmbedStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SubtitleEmbedStore store;

    @Before
    public void setUp() {
        Map<String, String> backing = new HashMap<>();
        store = new SubtitleEmbedStore(new SubtitleEmbedStore.Store() {
            @Override
            public String get(String key) {
                return backing.get(key);
            }

            @Override
            public void put(String key, String value) {
                backing.put(key, value);
            }

            @Override
            public void remove(String key) {
                backing.remove(key);
            }
        });
    }

    @Test
    public void unembeddedOrMissingFilesReturnFalse() throws Exception {
        File missing = new File(tmp.getRoot(), "missing.mp4");
        File file = tmp.newFile("clip.mp4");
        assertFalse(store.isEmbedded(null));
        assertFalse(store.isEmbedded(missing));
        assertFalse(store.isEmbedded(file));
    }

    @Test
    public void markEmbeddedDetectsFileAndInvalidatesWhenOverwritten() throws Exception {
        File file = tmp.newFile("clip.mp4");
        writeBytes(file, 100);

        store.markEmbedded(file);
        assertTrue(store.isEmbedded(file));

        // External overwrite changes length -> must no longer claim embedded
        writeBytes(file, 250);
        assertFalse(store.isEmbedded(file));
    }

    @Test
    public void renameMovesEmbeddedAndCaptionId() throws Exception {
        File oldFile = tmp.newFile("old_name.mp4");
        writeBytes(oldFile, 128);
        store.markEmbedded(oldFile);
        store.saveCaptionId(oldFile, "cap_123");

        File newFile = new File(tmp.getRoot(), "new_name.mp4");
        assertTrue(oldFile.renameTo(newFile));
        store.onRenamed(oldFile, newFile);

        assertFalse(store.isEmbedded(oldFile));
        assertTrue(store.isEmbedded(newFile));
        assertEquals("cap_123", store.getCaptionId(newFile));
    }

    @Test
    public void removeClearsEmbeddedAndPathCaption() throws Exception {
        File file = tmp.newFile("clip.mp4");
        writeBytes(file, 64);
        store.markEmbedded(file);
        store.saveCaptionId(file, "cap_456");

        store.remove(file.getAbsolutePath());
        assertFalse(store.isEmbedded(file));
    }

    @Test
    public void captionIdResolvesAcrossCropAndEditSuffixes() throws Exception {
        File master = tmp.newFile("recording_1.mp4");
        store.saveCaptionId(master, "cap_master");

        File crop916 = new File(tmp.getRoot(), "recording_1_9x16.mp4");
        File editCrop = new File(tmp.getRoot(), "recording_1_edit_2_9x16.mp4");
        assertEquals("cap_master", store.getCaptionId(crop916));
        assertEquals("cap_master", store.getCaptionId(editCrop));
    }

    @Test
    public void ignoresInvalidCaptionIds() throws Exception {
        File file = tmp.newFile("clip.mp4");
        store.saveCaptionId(file, null);
        store.saveCaptionId(file, "");
        store.saveCaptionId(file, "none");
        assertNull(store.getCaptionId(file));
    }

    private static void writeBytes(File f, int count) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(new byte[count]);
        }
    }
}
