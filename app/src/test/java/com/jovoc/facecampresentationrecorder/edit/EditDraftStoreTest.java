package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

public class EditDraftStoreTest {

    private static final long NOW = 1_000_000_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File master() throws Exception {
        File m = tmp.newFile("talk.mp4");
        try (FileWriter w = new FileWriter(m)) {
            w.write("video bytes");
        }
        return m;
    }

    private EditProject edit(File master) {
        return EditProject.wholeFile(master.getAbsolutePath()).withSplit(4000, 10_000);
    }

    @Test
    public void savedDraftComesBackWithPlayhead() throws Exception {
        File dir = tmp.newFolder("drafts");
        File m = master();
        EditProject p = edit(m);
        EditDraftStore.save(dir, p, 4321, NOW);
        EditDraftStore.Draft d = EditDraftStore.load(dir, m.getAbsolutePath(), NOW + 1000);
        assertNotNull(d);
        assertEquals(p, d.project);
        assertEquals(4321, d.playheadMs);
    }

    @Test
    public void noDraftGivesNull() throws Exception {
        assertNull(EditDraftStore.load(tmp.newFolder("drafts"), master().getAbsolutePath(), NOW));
    }

    @Test
    public void draftIsDroppedWhenTheMasterChanged() throws Exception {
        File dir = tmp.newFolder("drafts");
        File m = master();
        EditDraftStore.save(dir, edit(m), 0, NOW);
        try (FileWriter w = new FileWriter(m, true)) {
            w.write("more");
        }
        assertNull(EditDraftStore.load(dir, m.getAbsolutePath(), NOW));
        assertFalse(EditDraftStore.fileFor(dir, m.getAbsolutePath()).exists());
    }

    @Test
    public void oldDraftIsDropped() throws Exception {
        File dir = tmp.newFolder("drafts");
        File m = master();
        EditDraftStore.save(dir, edit(m), 0, NOW);
        assertNull(EditDraftStore.load(dir, m.getAbsolutePath(), NOW + EditDraftStore.MAX_AGE_MS + 1));
    }

    @Test
    public void garbageFileIsDroppedAndClearRemovesDraft() throws Exception {
        File dir = tmp.newFolder("drafts");
        File m = master();
        File f = EditDraftStore.fileFor(dir, m.getAbsolutePath());
        try (FileWriter w = new FileWriter(f)) {
            w.write("not json");
        }
        assertNull(EditDraftStore.load(dir, m.getAbsolutePath(), NOW));
        assertFalse(f.exists());

        EditDraftStore.save(dir, edit(m), 0, NOW);
        assertTrue(f.exists());
        EditDraftStore.clear(dir, m.getAbsolutePath());
        assertFalse(f.exists());
    }
}
