package com.jovoc.facecampresentationrecorder.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class DeviceIdTest {

    @Test
    public void deriveIsDeterministic() {
        byte[] raw = "drm-unique-id".getBytes(StandardCharsets.UTF_8);
        assertEquals(DeviceId.derive(raw), DeviceId.derive(raw.clone()));
    }

    @Test
    public void deriveHasVersionFiveAndRfcVariant() {
        String id = DeviceId.derive("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(36, id.length());
        assertEquals('5', id.charAt(14));
        assertTrue("89ab".indexOf(id.charAt(19)) >= 0);
        // Parses as a UUID with the same text.
        assertEquals(id, UUID.fromString(id).toString());
    }

    @Test
    public void differentRawBytesGiveDifferentIds() {
        assertNotEquals(DeviceId.derive(new byte[]{1, 2, 3}), DeviceId.derive(new byte[]{1, 2, 4}));
    }
}
