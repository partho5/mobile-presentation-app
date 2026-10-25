package com.jovoc.facecampresentationrecorder.edit;

import java.util.ArrayList;
import java.util.List;

/**
 * Undo/redo for the editor: a stack of immutable {@link EditProject} snapshots. Free of Android
 * classes so it is unit-testable. Also remembers which snapshot was last saved, so the editor
 * knows whether leaving would lose work.
 */
public final class EditHistory {

    /** Oldest snapshots are dropped beyond this, so a very long session cannot grow without bound. */
    static final int MAX_UNDO = 200;

    private final List<EditProject> undo = new ArrayList<>();
    private final List<EditProject> redo = new ArrayList<>();
    private EditProject current;
    private EditProject saved;

    public EditHistory(EditProject initial) {
        this.current = initial;
        this.saved = initial;
    }

    public EditProject current() {
        return current;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Makes {@code next} the current snapshot and clears redo. Returns false when nothing changed. */
    public boolean push(EditProject next) {
        if (next == null || next.equals(current)) return false;
        undo.add(current);
        if (undo.size() > MAX_UNDO) undo.remove(0);
        redo.clear();
        current = next;
        return true;
    }

    /** Steps back; returns the new current snapshot, or null when there is nothing to undo. */
    public EditProject undo() {
        if (undo.isEmpty()) return null;
        redo.add(current);
        current = undo.remove(undo.size() - 1);
        return current;
    }

    /** Steps forward; returns the new current snapshot, or null when there is nothing to redo. */
    public EditProject redo() {
        if (redo.isEmpty()) return null;
        undo.add(current);
        current = redo.remove(redo.size() - 1);
        return current;
    }

    /** Records that the current snapshot has been written out. */
    public void markSaved() {
        saved = current;
    }

    /** True when the current snapshot differs from the last saved (or initial) one. */
    public boolean isDirty() {
        return !current.equals(saved);
    }
}
