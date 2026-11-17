package com.jovoc.facecampresentationrecorder.aiedit.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class EventLogStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static EventLogData sample() {
        return new EventLogData("11111111-2222-3333-4444-555555555555", 1080, 2400, 118000,
                Arrays.asList(
                        new EventLogData.Activity("a1", "brush", 12000, 15400, 0.1, 0.2, 0.6, 0.3, 1.0),
                        new EventLogData.Activity("a2", "slide_change", 20000, 20000, 0, 0, 1, 1, 1.0)),
                Arrays.asList(new EventLogData.FacecamKey(0, 0.78, 0.62, 0.13),
                        new EventLogData.FacecamKey(41200, 0.78, 0.7, 0.13)));
    }

    @Test
    public void jsonShapeIsTheStoreFileAndTheRequestFields() throws Exception {
        JSONObject o = sample().toJson();
        assertEquals("11111111-2222-3333-4444-555555555555", o.getString("sessionId"));
        assertEquals(1080, o.getJSONObject("video").getInt("width"));
        assertEquals(2400, o.getJSONObject("video").getInt("height"));
        assertEquals(118000, o.getJSONObject("video").getLong("duration_ms"));
        JSONObject a = o.getJSONArray("activity_map").getJSONObject(0);
        assertEquals("a1", a.getString("id"));
        assertEquals("brush", a.getString("type"));
        assertEquals(12000, a.getLong("start_ms"));
        assertEquals(15400, a.getLong("end_ms"));
        assertEquals(0.6, a.getJSONObject("region").getDouble("w"), 1e-9);
        assertEquals("event_log", a.getString("source"));
        JSONObject k = o.getJSONArray("facecam_track").getJSONObject(1);
        assertEquals(41200, k.getLong("t_ms"));
        assertEquals(0.13, k.getDouble("r"), 1e-9);
    }

    @Test
    public void saveThenLoadRoundTrips() throws Exception {
        File dir = new File(tmp.getRoot(), "aiedit/logs");
        File master = new File(tmp.getRoot(), "10-20-30-AM-2026-11-17-hello.mp4");
        assertTrue(EventLogStore.save(dir, master, sample()));
        assertTrue(new File(dir, "10-20-30-AM-2026-11-17-hello.json").isFile());
        EventLogData back = EventLogStore.load(dir, master);
        assertNotNull(back);
        assertEquals(sample().sessionId, back.sessionId);
        assertEquals(118000, back.durationMs);
        assertEquals(2, back.activity.size());
        assertEquals("slide_change", back.activity.get(1).type);
        assertEquals(2, back.facecam.size());
        assertEquals(0.7, back.facecam.get(1).cy, 1e-9);
    }

    @Test
    public void saveReplacesAnOlderLogAndLeavesNoTempFile() {
        File dir = new File(tmp.getRoot(), "logs");
        File master = new File(tmp.getRoot(), "a.mp4");
        assertTrue(EventLogStore.save(dir, master, sample()));
        assertTrue(EventLogStore.save(dir, master, sample()));
        assertEquals(1, dir.list().length);
    }

    @Test
    public void editsAndCropsFindTheMastersLog() {
        File dir = new File(tmp.getRoot(), "logs");
        File master = new File(tmp.getRoot(), "talk.mp4");
        assertTrue(EventLogStore.save(dir, master, sample()));
        for (String name : new String[]{"talk_9x16.mp4", "talk_edit.mp4", "talk_edit_2.mp4",
                "talk_edit_4x5.mp4", "talk_edit_3_1x1.mp4"}) {
            assertNotNull(name, EventLogStore.load(dir, new File(tmp.getRoot(), name)));
        }
        assertNull(EventLogStore.load(dir, new File(tmp.getRoot(), "other.mp4")));
    }

    @Test
    public void masterBaseNameKeepsNamesThatOnlyLookSimilar() {
        assertEquals("talk", EventLogStore.masterBaseName("/x/y/talk.mp4"));
        assertEquals("talk_9x16x", EventLogStore.masterBaseName("talk_9x16x.mp4"));
        assertEquals("my_editor", EventLogStore.masterBaseName("my_editor.mp4"));
        assertEquals("a-b", EventLogStore.masterBaseName("a-b_edit_12_1x1.mp4"));
        assertEquals("talk_16x9", EventLogStore.masterBaseName("talk_16x9.mp4"));
    }

    @Test
    public void aMissingOrBrokenFileLoadsAsNullAndDeleteRemoves() throws Exception {
        File dir = tmp.newFolder("logs");
        File master = new File(tmp.getRoot(), "b.mp4");
        assertNull(EventLogStore.load(dir, master));
        try (FileOutputStream out = new FileOutputStream(new File(dir, "b.json"))) {
            out.write("{not json".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(EventLogStore.load(dir, master));
        assertTrue(EventLogStore.save(dir, master, sample()));
        EventLogStore.delete(dir, master);
        assertFalse(new File(dir, "b.json").exists());
    }
}
