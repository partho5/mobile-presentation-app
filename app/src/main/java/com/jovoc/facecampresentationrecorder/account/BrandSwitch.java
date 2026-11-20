package com.jovoc.facecampresentationrecorder.account;

/**
 * What picking a brand in the picker means. Plain Java with no Android types, so the rules are
 * tested on the JVM; the screen is {@code ui/WorkspaceActivity}.
 */
public final class BrandSwitch {

    public enum Outcome {
        /** The brand picked is the one in use: just close the picker (a first pick is still stored). */
        SAME,
        /** A different brand: restart the screens on the new brand's plan. */
        CHANGED,
        /** A recording is running: nothing changes. */
        BLOCKED_RECORDING
    }

    private BrandSwitch() {
    }

    /**
     * @param storedId        the brand stored for this phone, or null when none is stored
     * @param pickedId        the brand the person picked
     * @param serverDefaultId the server's default brand, or null when unknown; it is the brand in
     *                        use while nothing is stored
     * @param recordingNow    whether a recording is running
     */
    public static Outcome decide(String storedId, String pickedId, String serverDefaultId, boolean recordingNow) {
        return decide(storedId, pickedId, serverDefaultId, recordingNow, false);
    }

    /**
     * As above. A brand whose access is lost is never "the one in use": picking it again is a change,
     * so the screens restart and the access is asked for again.
     *
     * @param accessLost whether the stored brand has refused this phone (403)
     */
    public static Outcome decide(String storedId, String pickedId, String serverDefaultId, boolean recordingNow,
                                 boolean accessLost) {
        if (recordingNow) return Outcome.BLOCKED_RECORDING;
        if (accessLost) return Outcome.CHANGED;
        String inUse = isEmpty(storedId) ? serverDefaultId : storedId;
        if (!isEmpty(inUse) && inUse.equals(pickedId)) return Outcome.SAME;
        return Outcome.CHANGED;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
