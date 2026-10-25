package com.jovoc.facecampresentationrecorder.edit;

/**
 * Judges a freshly rendered file before it replaces anything: the right length and the audio
 * lined up with the video at the start and the end. Pure arithmetic over numbers the renderer
 * collected, so it can be tested without a device.
 */
public final class EditVerify {

    /** Container durations may include a last-frame tail and rounding. */
    static final long DURATION_TOLERANCE_MS = 300;
    static final long START_SYNC_TOLERANCE_MS = 250;
    /** Loose on purpose: the master itself may end with a slightly shorter audio track. */
    static final long END_SYNC_TOLERANCE_MS = 1000;

    private EditVerify() {
    }

    /**
     * @param expectedMs       length the edit should produce
     * @param videoMs          video duration read back from the output file
     * @param audioMs          audio duration read back from the output, or -1 if it has no audio
     * @param expectAudio      whether the master had audio that was copied
     * @param firstVideoUs     first video timestamp written (output timeline)
     * @param firstAudioUs     first audio timestamp written, or -1 if none
     * @param lastVideoUs      last video timestamp written
     * @param lastAudioUs      last audio timestamp written, or -1 if none
     * @return null when the output is fine, otherwise what is wrong
     */
    public static String check(long expectedMs, long videoMs, long audioMs, boolean expectAudio,
                               long firstVideoUs, long firstAudioUs, long lastVideoUs,
                               long lastAudioUs) {
        long tolerance = DURATION_TOLERANCE_MS + expectedMs / 100;
        if (videoMs <= 0) return "Output has no video";
        if (Math.abs(videoMs - expectedMs) > tolerance) {
            return "Output length " + videoMs + " ms, expected " + expectedMs + " ms";
        }
        if (firstVideoUs / 1000 > START_SYNC_TOLERANCE_MS) {
            return "Output video starts late (" + firstVideoUs / 1000 + " ms)";
        }
        if (!expectAudio) return null;
        if (audioMs < 0 || firstAudioUs < 0) return "Output lost its audio";
        if (Math.abs(audioMs - expectedMs) > tolerance) {
            return "Output audio length " + audioMs + " ms, expected " + expectedMs + " ms";
        }
        if (Math.abs(firstAudioUs - firstVideoUs) / 1000 > START_SYNC_TOLERANCE_MS) {
            return "Audio and video start out of sync";
        }
        if (Math.abs(lastAudioUs - lastVideoUs) / 1000 > END_SYNC_TOLERANCE_MS) {
            return "Audio and video end out of sync";
        }
        return null;
    }
}
