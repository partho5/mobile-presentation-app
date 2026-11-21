package com.jovoc.facecampresentationrecorder.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TrackerPayloadTest {

    private static final String GOOD_1 = "{\"id\":\"a\",\"seq\":1,\"name\":\"click\"}";
    private static final String GOOD_2 = "{\"id\":\"b\",\"seq\":2,\"name\":\"toggle\"}";
    private static final String BROKEN = "{\"id\":\"c\",";

    private static final TrackerPayload.AppInfo APP = new TrackerPayload.AppInfo("1.0", 4, "com.example.app");
    private static final TrackerPayload.DeviceInfo DEVICE =
            new TrackerPayload.DeviceInfo("Android 14", 34, "Google", "Pixel 7", "en-US");

    @Test
    public void skipsBrokenRowsAndKeepsTheEnvelope() throws Exception {
        String body = TrackerPayload.build("batch-1", "dev-1", 1_763_712_000_000L, APP, DEVICE,
                Arrays.asList(GOOD_1, BROKEN, GOOD_2));
        assertTrue(body != null);

        JSONObject o = new JSONObject(body);
        JSONArray activities = o.getJSONArray("activities");
        assertEquals(2, activities.length());
        assertEquals("a", activities.getJSONObject(0).getString("id"));
        assertEquals("b", activities.getJSONObject(1).getString("id"));

        assertEquals("batch-1", o.getString("batchId"));
        assertEquals("dev-1", o.getString("deviceId"));
        assertEquals(1_763_712_000_000L, o.getLong("sentAt"));
        assertEquals("1.0", o.getJSONObject("app").getString("version"));
        assertEquals(4, o.getJSONObject("app").getInt("build"));
        assertEquals("com.example.app", o.getJSONObject("app").getString("package"));
        assertEquals("Android 14", o.getJSONObject("device").getString("os"));
        assertEquals(34, o.getJSONObject("device").getInt("sdk"));
        assertEquals("en-US", o.getJSONObject("device").getString("locale"));
    }

    @Test
    public void allRowsBrokenGivesNull() {
        assertNull(TrackerPayload.build("b", "d", 1L, APP, DEVICE, Arrays.asList(BROKEN, "not json")));
    }

    @Test
    public void emptyListGivesNull() {
        assertNull(TrackerPayload.build("b", "d", 1L, APP, DEVICE, Collections.<String>emptyList()));
    }

    @Test
    public void nullListGivesNull() {
        assertNull(TrackerPayload.build("b", "d", 1L, APP, DEVICE, null));
    }
}
