package com.jovoc.facecampresentationrecorder.activitylog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceIdTest {

    private static final String UUID_SHAPE =
            "[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @Test
    public void sameInputGivesTheSameUuid() {
        byte[] raw = {1, 2, 3, 4, 5, 6, 7, 8};
        assertEquals(DeviceId.derive(raw), DeviceId.derive(raw.clone()));
    }

    @Test
    public void differentInputsGiveDifferentUuids() {
        assertNotEquals(DeviceId.derive(new byte[]{1}), DeviceId.derive(new byte[]{2}));
    }

    @Test
    public void resultIsAVersion5LayoutRfc4122Uuid() {
        assertTrue(DeviceId.derive(new byte[]{9, 9, 9}).matches(UUID_SHAPE));
        assertTrue(DeviceId.derive(new byte[32]).matches(UUID_SHAPE));
    }

    @Test
    public void rawIdIsNotVisibleInTheResult() {
        byte[] raw = {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x01};
        assertTrue(!DeviceId.derive(raw).replace("-", "").contains("abcdef01"));
    }
}
