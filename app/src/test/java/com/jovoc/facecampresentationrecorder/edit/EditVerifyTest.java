package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class EditVerifyTest {

    @Test
    public void goodOutputPasses() {
        assertNull(EditVerify.check(60_000, 60_040, 60_020, true, 0, 10_000, 59_960_000, 59_950_000));
    }

    @Test
    public void silentSourceNeedsNoAudio() {
        assertNull(EditVerify.check(60_000, 60_040, -1, false, 0, -1, 59_960_000, -1));
    }

    @Test
    public void wrongLengthFails() {
        assertNotNull(EditVerify.check(60_000, 55_000, 55_000, true, 0, 0, 55_000_000, 55_000_000));
    }

    @Test
    public void missingVideoFails() {
        assertNotNull(EditVerify.check(60_000, -1, -1, false, 0, -1, 0, -1));
    }

    @Test
    public void lostAudioFails() {
        assertNotNull(EditVerify.check(60_000, 60_000, -1, true, 0, -1, 59_960_000, -1));
    }

    @Test
    public void startOutOfSyncFails() {
        assertNotNull(EditVerify.check(60_000, 60_000, 60_000, true, 0, 600_000, 59_960_000, 59_960_000));
    }

    @Test
    public void endOutOfSyncFails() {
        assertNotNull(EditVerify.check(60_000, 60_000, 60_000, true, 0, 0, 59_960_000, 58_000_000));
    }

    @Test
    public void lateVideoStartFails() {
        assertNotNull(EditVerify.check(60_000, 60_000, -1, false, 900_000, -1, 59_960_000, -1));
    }
}
