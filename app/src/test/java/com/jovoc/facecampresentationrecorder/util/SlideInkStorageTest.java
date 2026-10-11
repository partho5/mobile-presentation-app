package com.jovoc.facecampresentationrecorder.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.ui.DrawingBoardView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class SlideInkStorageTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File filesDir;

    @Before
    public void setUp() {
        filesDir = tempFolder.getRoot();
        SlideInkStorage.isPersistentDrawingSlide = false;
        SlideInkStorage.clearAll(filesDir);
    }

    @After
    public void tearDown() {
        SlideInkStorage.isPersistentDrawingSlide = false;
        SlideInkStorage.clearAll(filesDir);
    }

    @Test
    public void defaultIsFalse() {
        assertFalse(SlideInkStorage.isPersistentDrawingSlide);
    }

    @Test
    public void nonPersistent_retainsInMemoryDuringSession() {
        SlideInkStorage.isPersistentDrawingSlide = false;
        List<DrawingBoardView.Stroke> strokes = Collections.singletonList(
                new DrawingBoardView.Stroke(DrawingBoardView.TYPE_STROKE, 0xFFFFFFFF, new float[]{0.1f, 0.2f, 0.3f, 0.4f})
        );

        SlideInkStorage.save(filesDir, 10, strokes);

        List<DrawingBoardView.Stroke> loaded = SlideInkStorage.load(filesDir, 10);
        assertEquals(1, loaded.size());
        assertEquals(0xFFFFFFFF, loaded.get(0).color);

        // Verify it was NOT written to disk
        File onDisk = SlideInkStorage.file(filesDir, 10);
        assertFalse(onDisk.exists());
    }

    @Test
    public void nonPersistent_forgetsAfterRestart() {
        SlideInkStorage.isPersistentDrawingSlide = false;
        List<DrawingBoardView.Stroke> strokes = Collections.singletonList(
                new DrawingBoardView.Stroke(DrawingBoardView.TYPE_STROKE, 0xFFFF1744, new float[]{0.5f, 0.5f})
        );

        SlideInkStorage.save(filesDir, 42, strokes);
        assertEquals(1, SlideInkStorage.load(filesDir, 42).size());

        // Simulate app restart by clearing memory
        SlideInkStorage.clearMemory();

        // After restart, strokes are forgotten
        List<DrawingBoardView.Stroke> afterRestart = SlideInkStorage.load(filesDir, 42);
        assertTrue(afterRestart.isEmpty());
    }

    @Test
    public void persistent_savesToDiskAndRestoresAfterRestart() {
        SlideInkStorage.isPersistentDrawingSlide = true;
        List<DrawingBoardView.Stroke> strokes = Collections.singletonList(
                new DrawingBoardView.Stroke(DrawingBoardView.TYPE_STROKE, 0xFF00E5FF, new float[]{0.1f, 0.9f})
        );

        SlideInkStorage.save(filesDir, 7, strokes);

        // Check file exists on disk
        File onDisk = SlideInkStorage.file(filesDir, 7);
        assertTrue(onDisk.exists());

        // Simulate app restart (clear in-memory cache)
        SlideInkStorage.clearMemory();

        // With persistent mode on, strokes are restored from disk
        List<DrawingBoardView.Stroke> reloaded = SlideInkStorage.load(filesDir, 7);
        assertEquals(1, reloaded.size());
        assertEquals(0xFF00E5FF, reloaded.get(0).color);
    }

    @Test
    public void nonPersistent_ignoresStaleDiskFiles() {
        // Suppose persistent mode previously wrote a file to disk
        SlideInkStorage.isPersistentDrawingSlide = true;
        List<DrawingBoardView.Stroke> strokes = Collections.singletonList(
                new DrawingBoardView.Stroke(DrawingBoardView.TYPE_STROKE, 0xFF00E676, new float[]{0.2f, 0.8f})
        );
        SlideInkStorage.save(filesDir, 99, strokes);
        assertTrue(SlideInkStorage.file(filesDir, 99).exists());

        // Switch to non-persistent and simulate app restart
        SlideInkStorage.isPersistentDrawingSlide = false;
        SlideInkStorage.clearMemory();

        // Non-persistent mode must not load disk files
        List<DrawingBoardView.Stroke> loaded = SlideInkStorage.load(filesDir, 99);
        assertTrue(loaded.isEmpty());

        // Also startup cleanup deletes stale disk files
        SlideInkStorage.clearSavedFiles(filesDir);
        assertFalse(SlideInkStorage.file(filesDir, 99).exists());
    }

    @Test
    public void deleteRemovesBothMemoryAndDisk() {
        SlideInkStorage.isPersistentDrawingSlide = true;
        List<DrawingBoardView.Stroke> strokes = Collections.singletonList(
                new DrawingBoardView.Stroke(DrawingBoardView.TYPE_STROKE, 0xFFD500F9, new float[]{0.3f, 0.7f})
        );
        SlideInkStorage.save(filesDir, 15, strokes);
        assertTrue(SlideInkStorage.file(filesDir, 15).exists());

        SlideInkStorage.delete(filesDir, 15);
        assertTrue(SlideInkStorage.load(filesDir, 15).isEmpty());
        assertFalse(SlideInkStorage.file(filesDir, 15).exists());
    }
}
