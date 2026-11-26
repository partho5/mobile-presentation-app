package com.jovoc.facecampresentationrecorder.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InstallHistoryTest {

    private static final int NO_RECORD = InstallHistory.NO_CHANGE;

    @Test
    public void freshInstall_hasNoPreviousVersion() {
        assertEquals(InstallHistory.NONE, InstallHistory.transition(NO_RECORD, 5, false, false));
    }

    @Test
    public void updatedWithoutRecord_cameFromV1() {
        assertEquals(InstallHistory.V1_VERSION_CODE, InstallHistory.transition(NO_RECORD, 5, true, false));
    }

    @Test
    public void oldSettingsWithoutRecord_cameFromV1() {
        assertEquals(InstallHistory.V1_VERSION_CODE, InstallHistory.transition(NO_RECORD, 5, false, true));
    }

    @Test
    public void laterUpdate_reportsRecordedVersion() {
        assertEquals(5, InstallHistory.transition(5, 6, true, true));
    }

    @Test
    public void sameVersion_noChange() {
        assertEquals(InstallHistory.NO_CHANGE, InstallHistory.transition(5, 5, true, true));
    }
}
