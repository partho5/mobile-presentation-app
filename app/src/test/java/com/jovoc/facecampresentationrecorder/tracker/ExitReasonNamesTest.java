package com.jovoc.facecampresentationrecorder.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExitReasonNamesTest {

    @Test
    public void knownReasonsHaveTheirNames() {
        assertEquals("ANR", ExitReasonNames.reasonName(6));
        assertEquals("CRASH_NATIVE", ExitReasonNames.reasonName(5));
        assertEquals("USER_REQUESTED", ExitReasonNames.reasonName(10));
    }

    @Test
    public void unknownReasonIsUnknown() {
        assertEquals("UNKNOWN", ExitReasonNames.reasonName(99));
        assertEquals("UNKNOWN", ExitReasonNames.reasonName(0));
    }

    @Test
    public void onlyCrashLikeReasonsAreErrors() {
        assertTrue(ExitReasonNames.isError("ANR"));
        assertTrue(ExitReasonNames.isError("CRASH_NATIVE"));
        assertTrue(ExitReasonNames.isError("INITIALIZATION_FAILURE"));
        assertFalse(ExitReasonNames.isError("CRASH"));
        assertFalse(ExitReasonNames.isError("USER_REQUESTED"));
    }
}
