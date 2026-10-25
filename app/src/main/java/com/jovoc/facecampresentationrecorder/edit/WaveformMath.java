package com.jovoc.facecampresentationrecorder.edit;

/**
 * Turns loudness peaks (one value 0..1 per equal slice of a file) into the columns the audio lane
 * draws. Free of Android classes so it is unit-testable.
 */
public final class WaveformMath {

    private WaveformMath() {}

    /**
     * Columns across the edited result for the recording's own sound: each column looks up the
     * master moment it plays, so cut parts leave no gap. Empty when there are no peaks.
     */
    public static float[] remapToOutput(float[] peaks, EditProject project, long masterDurationMs,
                                        int columns) {
        float[] out = new float[Math.max(0, columns)];
        long outDur = project.outputDurationMs(masterDurationMs);
        if (peaks == null || peaks.length == 0 || masterDurationMs <= 0 || outDur <= 0) return out;
        for (int c = 0; c < out.length; c++) {
            long outMs = (long) ((c + 0.5) * outDur / out.length);
            long src = project.outputToSource(outMs, masterDurationMs);
            out[c] = peaks[bucket(src, masterDurationMs, peaks.length)];
        }
        return out;
    }

    /**
     * Columns for the used part {@code [inMs, inMs + lengthMs)} of a file {@code sourceDurationMs}
     * long; each column takes the loudest slice it covers.
     */
    public static float[] slice(float[] peaks, long sourceDurationMs, long inMs, long lengthMs,
                                int columns) {
        float[] out = new float[Math.max(0, columns)];
        if (peaks == null || peaks.length == 0 || sourceDurationMs <= 0 || lengthMs <= 0) return out;
        for (int c = 0; c < out.length; c++) {
            long from = inMs + c * lengthMs / out.length;
            long to = inMs + (c + 1) * lengthMs / out.length;
            int b0 = bucket(from, sourceDurationMs, peaks.length);
            int b1 = Math.max(b0, bucket(to, sourceDurationMs, peaks.length));
            float max = 0;
            for (int b = b0; b <= b1; b++) max = Math.max(max, peaks[b]);
            out[c] = max;
        }
        return out;
    }

    private static int bucket(long ms, long durationMs, int count) {
        long b = Math.max(0, ms) * count / durationMs;
        return (int) Math.min(count - 1, b);
    }
}
