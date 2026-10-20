package com.jovoc.facecampresentationrecorder.activitylog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CrashLoggerTest {

    private static Throwable withFrames(Throwable t, StackTraceElement... frames) {
        t.setStackTrace(frames);
        return t;
    }

    private static StackTraceElement frame(String cls, String method, int line) {
        return new StackTraceElement(cls, method, cls.substring(cls.lastIndexOf('.') + 1) + ".java", line);
    }

    @Test
    public void usesTheRootCauseTypeAndNeverTheMessage() {
        Throwable root = new IllegalStateException("secret user text.mp4");
        Throwable wrapped = new RuntimeException("wrapper", root);
        assertEquals("IllegalStateException", CrashLogger.typeOf(wrapped));
    }

    @Test
    public void pointsAtTheFirstFrameInsideTheApp() {
        Throwable t = withFrames(new NullPointerException(),
                frame("java.util.ArrayList", "get", 427),
                frame("com.jovoc.facecampresentationrecorder.MainActivity", "startRecordingFlow", 1311),
                frame("android.os.Handler", "dispatchMessage", 106));
        assertEquals("MainActivity.startRecordingFlow:1311", CrashLogger.whereOf(t));
    }

    @Test
    public void fallsBackToTheTopFrameOrUnknown() {
        Throwable outside = withFrames(new RuntimeException(), frame("android.os.Handler", "post", 9));
        assertEquals("Handler.post:9", CrashLogger.whereOf(outside));
        assertEquals("unknown", CrashLogger.whereOf(withFrames(new RuntimeException())));
    }

    @Test
    public void valuesStayWithinTheMetaLimit() {
        Throwable t = withFrames(new RuntimeException(),
                frame("com.jovoc.facecampresentationrecorder.ui.AVeryLongClassNameThatKeepsGoingAndGoingAndGoing",
                        "andAnEquallyLongMethodNameToo", 12345));
        assertTrue(CrashLogger.whereOf(t).length() <= 60);
    }
}
