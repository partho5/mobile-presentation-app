package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Finding a waiting auto-publish again after its recording was renamed during processing. */
public class RenameAliasesTest {

    @Test
    public void renamedRecordingIsFoundByItsProcessingPath() {
        RenameAliases a = new RenameAliases();
        a.renamed("/v/a.mp4", "/v/b.mp4");
        assertEquals("/v/a.mp4", a.rootOf("/v/b.mp4"));
        assertEquals("/v/b.mp4", a.currentFor("/v/a.mp4"));
    }

    @Test
    public void severalRenamesKeepTheFirstPathAsRoot() {
        RenameAliases a = new RenameAliases();
        a.renamed("/v/a.mp4", "/v/b.mp4");
        a.renamed("/v/b.mp4", "/v/c.mp4");
        assertEquals("/v/a.mp4", a.rootOf("/v/c.mp4"));
        assertEquals("/v/c.mp4", a.currentFor("/v/a.mp4"));
        assertNull(a.rootOf("/v/b.mp4"));
    }

    @Test
    public void unknownPathsAndForgottenOnesGiveNull() {
        RenameAliases a = new RenameAliases();
        assertNull(a.rootOf("/v/x.mp4"));
        assertNull(a.currentFor("/v/x.mp4"));
        a.renamed("/v/a.mp4", "/v/b.mp4");
        a.forget("/v/b.mp4");
        assertNull(a.currentFor("/v/a.mp4"));
    }
}
