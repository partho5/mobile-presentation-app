package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EditHistoryTest {

    private static final long DUR = 10_000;

    private static EditProject whole() {
        return EditProject.wholeFile("/m.mp4");
    }

    @Test
    public void freshHistoryIsCleanAndCannotUndoOrRedo() {
        EditHistory h = new EditHistory(whole());
        assertFalse(h.canUndo());
        assertFalse(h.canRedo());
        assertFalse(h.isDirty());
        assertNull(h.undo());
        assertNull(h.redo());
    }

    @Test
    public void pushUndoRedoWalkTheSnapshots() {
        EditProject a = whole();
        EditProject b = a.withSplit(4000, DUR);
        EditProject c = b.withDeleted(0, 4000, DUR);
        EditHistory h = new EditHistory(a);
        assertTrue(h.push(b));
        assertTrue(h.push(c));
        assertEquals(c, h.current());
        assertEquals(b, h.undo());
        assertEquals(a, h.undo());
        assertFalse(h.canUndo());
        assertEquals(b, h.redo());
        assertEquals(c, h.redo());
        assertFalse(h.canRedo());
    }

    @Test
    public void pushingTheSameProjectIsIgnored() {
        EditHistory h = new EditHistory(whole());
        assertFalse(h.push(whole()));
        assertFalse(h.canUndo());
    }

    @Test
    public void newEditClearsRedo() {
        EditProject a = whole();
        EditHistory h = new EditHistory(a);
        h.push(a.withSplit(4000, DUR));
        h.undo();
        assertTrue(h.canRedo());
        h.push(a.withSplit(6000, DUR));
        assertFalse(h.canRedo());
    }

    @Test
    public void dirtyFollowsTheSavedSnapshot() {
        EditProject a = whole();
        EditHistory h = new EditHistory(a);
        h.push(a.withSplit(4000, DUR));
        assertTrue(h.isDirty());
        h.markSaved();
        assertFalse(h.isDirty());
        h.undo();
        assertTrue(h.isDirty());
        h.redo();
        assertFalse(h.isDirty());
    }

    @Test
    public void undoDepthIsCapped() {
        EditHistory h = new EditHistory(whole());
        for (int i = 1; i <= EditHistory.MAX_UNDO + 20; i++) {
            h.push(whole().withSplit(i * 10L, DUR));
        }
        int steps = 0;
        while (h.undo() != null) steps++;
        assertEquals(EditHistory.MAX_UNDO, steps);
    }
}
