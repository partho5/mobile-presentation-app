package com.jovoc.facecampresentationrecorder.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.ui.DrawingBoardView;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class DrawingBoardStorageTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File filesDir;

    @Before
    public void setUp() {
        filesDir = tempFolder.getRoot();
    }

    private static List<DrawingBoardView.Stroke> stroke(int color) {
        return Collections.singletonList(
                new DrawingBoardView.Stroke(DrawingBoardView.TYPE_STROKE, color, new float[]{0.1f, 0.2f, 0.3f, 0.4f}));
    }

    @Test
    public void eachSlideKeepsItsOwnDrawing() {
        DrawingBoardStorage.saveSlide(filesDir, 1, stroke(0xFFFF0000));
        DrawingBoardStorage.saveSlide(filesDir, 2, stroke(0xFF00FF00));

        assertEquals(0xFFFF0000, DrawingBoardStorage.loadSlide(filesDir, 1).get(0).color);
        assertEquals(0xFF00FF00, DrawingBoardStorage.loadSlide(filesDir, 2).get(0).color);
        assertTrue(DrawingBoardStorage.loadSlide(filesDir, 3).isEmpty());
    }

    @Test
    public void savingAnEmptyBoardLeavesNothingBehind() {
        DrawingBoardStorage.saveSlide(filesDir, 1, stroke(0xFFFFFFFF));
        DrawingBoardStorage.saveSlide(filesDir, 1, Collections.emptyList());

        assertTrue(DrawingBoardStorage.loadSlide(filesDir, 1).isEmpty());
    }

    @Test
    public void shownFlagIsPerSlideAndToggles() {
        assertFalse(DrawingBoardStorage.isShownOnSlide(filesDir, 1));

        DrawingBoardStorage.setShownOnSlide(filesDir, 1, true);
        assertTrue(DrawingBoardStorage.isShownOnSlide(filesDir, 1));
        assertFalse(DrawingBoardStorage.isShownOnSlide(filesDir, 2));

        DrawingBoardStorage.setShownOnSlide(filesDir, 1, false);
        assertFalse(DrawingBoardStorage.isShownOnSlide(filesDir, 1));
    }

    @Test
    public void deleteSlideRemovesDrawingAndFlag() {
        DrawingBoardStorage.saveSlide(filesDir, 1, stroke(0xFFFFFFFF));
        DrawingBoardStorage.setShownOnSlide(filesDir, 1, true);

        DrawingBoardStorage.deleteSlide(filesDir, 1);

        assertTrue(DrawingBoardStorage.loadSlide(filesDir, 1).isEmpty());
        assertFalse(DrawingBoardStorage.isShownOnSlide(filesDir, 1));
    }

    @Test
    public void legacyBoardIsAdoptedOnceByTheFirstSlide() {
        DrawingBoardStorage.writeStrokes(new File(filesDir, "drawing_board.json"), stroke(0xFF123456));

        List<DrawingBoardView.Stroke> adopted = DrawingBoardStorage.adoptLegacy(filesDir, 7);

        assertEquals(1, adopted.size());
        assertEquals(0xFF123456, DrawingBoardStorage.loadSlide(filesDir, 7).get(0).color);
        assertFalse(new File(filesDir, "drawing_board.json").exists());
        assertTrue(DrawingBoardStorage.adoptLegacy(filesDir, 8).isEmpty());
    }
}
