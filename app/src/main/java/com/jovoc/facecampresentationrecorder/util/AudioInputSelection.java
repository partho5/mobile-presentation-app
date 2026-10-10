package com.jovoc.facecampresentationrecorder.util;

/**
 * Which microphone the next recording uses, as pure logic over device ids. An external mic wins
 * by default; the user can pick the phone mic (or a specific external one) explicitly, and a
 * newly connected external mic resets that choice so it is used again.
 */
public final class AudioInputSelection {

    /** {@link #resolve} result: use the phone's built-in mic. */
    public static final int PHONE_MIC = -1;
    private static final int NO_CHOICE = -2;

    private int choice = NO_CHOICE;

    /** The user picked the phone mic; stays until an external mic is connected. */
    public synchronized void selectPhoneMic() {
        choice = PHONE_MIC;
    }

    public synchronized void selectExternal(int deviceId) {
        choice = deviceId;
    }

    /** A new external mic appeared: back to "use the external mic". */
    public synchronized void onExternalConnected() {
        choice = NO_CHOICE;
    }

    /**
     * @param externalIds ids of the external mics currently connected, preferred first
     * @return the id of the external mic to record with, or {@link #PHONE_MIC}
     */
    public synchronized int resolve(int[] externalIds) {
        if (externalIds.length == 0 || choice == PHONE_MIC) return PHONE_MIC;
        for (int id : externalIds) {
            if (id == choice) return id;
        }
        // Nothing chosen, or the chosen mic is gone: the first external one
        return externalIds[0];
    }
}
