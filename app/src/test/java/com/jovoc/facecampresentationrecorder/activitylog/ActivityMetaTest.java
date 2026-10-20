package com.jovoc.facecampresentationrecorder.activitylog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class ActivityMetaTest {

    @Test
    public void actionNamesAreLowercaseWordsUpTo48Chars() {
        assertTrue(ActivityMeta.isValidAction("record_stop"));
        assertTrue(ActivityMeta.isValidAction("a1"));
        assertFalse(ActivityMeta.isValidAction(null));
        assertFalse(ActivityMeta.isValidAction(""));
        assertFalse(ActivityMeta.isValidAction("Record"));
        assertFalse(ActivityMeta.isValidAction("record stop"));
        assertFalse(ActivityMeta.isValidAction(new String(new char[49]).replace('\0', 'a')));
    }

    @Test
    public void keepsScalarDetails() throws Exception {
        String json = ActivityMeta.toJson(new Object[]{"duration_ms", 92000L, "source", "button", "on", true});
        JSONObject o = new JSONObject(json);
        assertEquals(92000L, o.getLong("duration_ms"));
        assertEquals("button", o.getString("source"));
        assertTrue(o.getBoolean("on"));
        assertEquals(3, o.length());
    }

    @Test
    public void noDetailsGivesNull() {
        assertNull(ActivityMeta.toJson(new Object[0]));
        assertNull(ActivityMeta.toJson(null));
        assertNull(ActivityMeta.toJson(new Object[]{"key_without_value"}));
    }

    @Test
    public void dropsWhatTheBackendWouldRefuse() throws Exception {
        String json = ActivityMeta.toJson(new Object[]{
                "BadKey", 1,
                "nested", new int[]{1},
                "nan", Double.NaN,
                "inf", Double.POSITIVE_INFINITY,
                "long", new String(new char[65]).replace('\0', 'x'),
                "ok", 1});
        JSONObject o = new JSONObject(json);
        assertEquals(1, o.length());
        assertEquals(1, o.getInt("ok"));
    }

    @Test
    public void neverKeepsKeysThatSoundLikeCredentialsOrAddresses() throws Exception {
        String json = ActivityMeta.toJson(new Object[]{
                "user_email", "a@b.c", "access_token", "x", "Password", "p", "my_secret", "s",
                "authorization", "Bearer x", "ok", 2});
        JSONObject o = new JSONObject(json);
        assertEquals(1, o.length());
        assertTrue(o.has("ok"));
    }

    @Test
    public void keepsAtMostTenKeys() throws Exception {
        Object[] pairs = new Object[30];
        for (int i = 0; i < 15; i++) {
            pairs[i * 2] = "k" + i;
            pairs[i * 2 + 1] = i;
        }
        // 30 slots hold 15 pairs; the loop above filled all of them.
        assertEquals(ActivityMeta.MAX_KEYS, new JSONObject(ActivityMeta.toJson(pairs)).length());
    }

    @Test
    public void dropsDetailsOver512Bytes() {
        Object[] pairs = new Object[20];
        String sixty = new String(new char[60]).replace('\0', 'y');
        for (int i = 0; i < 10; i++) {
            pairs[i * 2] = "k" + i;
            pairs[i * 2 + 1] = sixty;
        }
        assertNull(ActivityMeta.toJson(pairs));
    }
}
