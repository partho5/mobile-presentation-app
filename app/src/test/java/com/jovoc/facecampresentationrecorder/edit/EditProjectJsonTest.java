package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class EditProjectJsonTest {

    @Test
    public void roundTripKeepsEverything() {
        EditProject p = new EditProject("/a/b/talk.mp4",
                Arrays.asList(new EditProject.Range(0, 3000), new EditProject.Range(5000, 9000)),
                new EditProject.AudioSettings(1.3f, true, false),
                Collections.singletonList(new EditProject.AddedAudio("content://x/1", 500, 4000, 1200, 0.7f)));
        assertEquals(p, EditProjectJson.fromJson(EditProjectJson.toJson(p)));
    }

    @Test
    public void wholeFileRoundTrips() {
        EditProject p = EditProject.wholeFile("/m.mp4");
        assertEquals(p, EditProjectJson.fromJson(EditProjectJson.toJson(p)));
    }

    @Test
    public void damagedOrInvalidTextGivesNull() {
        assertNull(EditProjectJson.fromJson(null));
        assertNull(EditProjectJson.fromJson(""));
        assertNull(EditProjectJson.fromJson("{\"master\":\"/m.mp4\"}"));
        assertNull(EditProjectJson.fromJson("{\"master\":\"/m.mp4\",\"keep\":[[5,3]],\"volume\":1,"
                + "\"muted\":false,\"removed\":false,\"added\":[]}"));
        assertNull(EditProjectJson.fromJson("{\"master\":\"/m.mp4\",\"keep\":[[0,5],[3,9]],\"volume\":1,"
                + "\"muted\":false,\"removed\":false,\"added\":[]}"));
    }
}
