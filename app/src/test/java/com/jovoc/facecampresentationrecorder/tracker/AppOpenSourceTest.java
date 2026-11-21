package com.jovoc.facecampresentationrecorder.tracker;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppOpenSourceTest {

    private static final int HISTORY = 0x00100000;

    @Test
    public void notificationWinsOverEverything() {
        assertEquals("notification", AppOpenSource.of(true, "android.intent.action.MAIN", true, HISTORY));
        assertEquals("notification", AppOpenSource.of(true, null, false, 0));
    }

    @Test
    public void historyFlagMeansRecents() {
        assertEquals("recents", AppOpenSource.of(false, "android.intent.action.MAIN", true, HISTORY));
        assertEquals("recents", AppOpenSource.of(false, null, false, HISTORY));
    }

    @Test
    public void mainWithLauncherCategoryMeansLauncher() {
        assertEquals("launcher", AppOpenSource.of(false, "android.intent.action.MAIN", true, 0));
    }

    @Test
    public void anythingElseIsOther() {
        assertEquals("other", AppOpenSource.of(false, "android.intent.action.VIEW", false, 0));
        assertEquals("other", AppOpenSource.of(false, "android.intent.action.MAIN", false, 0));
        assertEquals("other", AppOpenSource.of(false, null, false, 0));
    }
}
