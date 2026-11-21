package com.jovoc.facecampresentationrecorder.tracker;

/** Where an app open or foreground came from. Pure Java: no android.* classes. */
public final class AppOpenSource {

    static final String NOTIFICATION = "notification";
    static final String RECENTS = "recents";
    static final String LAUNCHER = "launcher";
    static final String OTHER = "other";

    /** Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, written as a literal so this class stays pure. */
    static final int FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY = 0x00100000;

    static final String ACTION_MAIN = "android.intent.action.MAIN";

    private AppOpenSource() { }

    /**
     * The source of one app open: a notification wins over everything, then the recents list (the
     * history flag), then the launcher icon (MAIN + LAUNCHER), otherwise other.
     */
    public static String of(boolean fromNotification, String action, boolean hasLauncherCategory, int flags) {
        if (fromNotification) return NOTIFICATION;
        if ((flags & FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return RECENTS;
        if (ACTION_MAIN.equals(action) && hasLauncherCategory) return LAUNCHER;
        return OTHER;
    }
}
