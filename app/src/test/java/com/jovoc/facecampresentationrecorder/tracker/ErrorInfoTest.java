package com.jovoc.facecampresentationrecorder.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class ErrorInfoTest {

    @Test
    public void wrappedExceptionHasFullTypeOneCauseAndTheCauseInTheStack() {
        ErrorInfo e = ErrorInfo.from(new IllegalStateException("outer", new IOException("disk")),
                false, "main", "click — record");
        assertEquals("java.lang.IllegalStateException", e.type);
        assertEquals("outer", e.message);
        assertEquals(1, e.causes.size());
        assertEquals("java.io.IOException", e.causes.get(0).type);
        assertEquals("disk", e.causes.get(0).message);
        assertTrue(e.stack.contains("Caused by: java.io.IOException"));
        assertEquals("main", e.thread);
        assertEquals("click — record", e.lastActivity);
    }

    @Test
    public void longMessageIsCutTo1000() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append('m');
        ErrorInfo e = ErrorInfo.from(new RuntimeException(sb.toString()), false, null, null);
        assertEquals(1000, e.message.length());
    }

    @Test
    public void selfReferencingCauseChainTerminates() {
        final RuntimeException[] holder = new RuntimeException[1];
        RuntimeException self = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return holder[0];
            }
        };
        holder[0] = self;
        ErrorInfo e = ErrorInfo.from(self, false, null, null);
        assertTrue(e.causes.size() <= 1);
        assertTrue(e.stack.length() <= 8000);
    }

    @Test
    public void nullThrowableGivesNull() {
        assertNull(ErrorInfo.from(null, true, "main", null));
    }

    @Test
    public void fatalFlagIsKept() throws Exception {
        ErrorInfo e = ErrorInfo.from(new RuntimeException("x"), true, "main", null);
        assertTrue(e.toJson().getBoolean("fatal"));
    }
}
