package com.jovoc.facecampresentationrecorder.edit;

/**
 * The sample maths behind the audio changes: gain, summing tracks with a clipping guard, channel
 * and sample-rate conversion, and where a track lands on the result's timeline. Pure Java over
 * interleaved 16-bit PCM, no Android classes, so it is unit-tested; {@link AudioMixRenderer}
 * only moves data between the codecs and these methods.
 *
 * Mixing happens in floats in the 16-bit range (a loud sum may pass 32767 there) and
 * {@link #toPcm16} brings it back with {@link #limit}, so two loud tracks never wrap around or
 * clip harshly.
 */
public final class AudioMixer {

    /** Samples below this share of full scale pass through {@link #limit} untouched. */
    static final float KNEE = 0.8f;
    private static final float FULL_SCALE = 32767f;

    private AudioMixer() {
    }

    /** The multiplier for a track: 0 when muted, otherwise its volume clamped to 0..2. */
    public static float gainFor(float volume, boolean muted) {
        if (muted) return 0f;
        return Math.max(0f, Math.min(2f, volume));
    }

    /** Frames covering {@code ms} at {@code sampleRate}, rounded down. */
    public static long framesForMs(long ms, int sampleRate) {
        return ms * sampleRate / 1000;
    }

    /**
     * Where a track that starts at {@code trackStartFrame} begins inside the chunk
     * {@code [chunkStartFrame, chunkStartFrame + chunkFrames)}: 0 when it already started, the
     * frame offset when it starts inside the chunk, -1 when it starts after the chunk. A start
     * before the timeline (negative) counts as already started.
     */
    public static int placementOffset(long chunkStartFrame, int chunkFrames, long trackStartFrame) {
        if (trackStartFrame <= chunkStartFrame) return 0;
        long into = trackStartFrame - chunkStartFrame;
        return into >= chunkFrames ? -1 : (int) into;
    }

    /**
     * Adds {@code frames} frames of {@code pcm} (starting at frame {@code pcmFrame}), scaled by
     * {@code gain}, into {@code acc} at frame {@code accFrame}. Both are interleaved with the same
     * channel count.
     */
    public static void accumulate(float[] acc, int accFrame, short[] pcm, int pcmFrame, int frames,
                                  int channels, float gain) {
        if (gain == 0f) return;
        int a = accFrame * channels;
        int p = pcmFrame * channels;
        int n = frames * channels;
        for (int i = 0; i < n; i++) acc[a + i] += pcm[p + i] * gain;
    }

    /**
     * Soft clipping guard: unchanged up to {@link #KNEE} of full scale, then bent smoothly toward
     * (never past) full scale. Monotonic and symmetric.
     */
    public static float limit(float sample) {
        float abs = Math.abs(sample);
        float knee = KNEE * FULL_SCALE;
        if (abs <= knee) return sample;
        float room = FULL_SCALE - knee;
        float bent = knee + room * (float) Math.tanh((abs - knee) / room);
        return sample < 0 ? -bent : bent;
    }

    /** Converts {@code count} accumulated samples to 16-bit with the clipping guard. */
    public static void toPcm16(float[] acc, int count, short[] out) {
        for (int i = 0; i < count; i++) {
            float v = Math.round(limit(acc[i]));
            out[i] = (short) Math.max(-32768f, Math.min(32767f, v));
        }
    }

    /**
     * Mono to stereo (copy) or stereo to mono (average) and, for other counts, the first
     * {@code outChannels} channels (extra ones repeat the last source channel).
     */
    public static short[] convertChannels(short[] in, int frames, int inChannels, int outChannels) {
        if (inChannels == outChannels) {
            short[] copy = new short[frames * outChannels];
            System.arraycopy(in, 0, copy, 0, copy.length);
            return copy;
        }
        short[] out = new short[frames * outChannels];
        for (int f = 0; f < frames; f++) {
            if (outChannels == 1) {
                int sum = 0;
                for (int c = 0; c < inChannels; c++) sum += in[f * inChannels + c];
                out[f] = (short) (sum / inChannels);
            } else {
                for (int c = 0; c < outChannels; c++) {
                    out[f * outChannels + c] = in[f * inChannels + Math.min(c, inChannels - 1)];
                }
            }
        }
        return out;
    }

    /**
     * Streaming linear-interpolation sample-rate converter: feed chunks in order, it carries the
     * last frame over so the chunk boundaries are seamless.
     */
    public static final class Resampler {
        private final double step;
        private final int channels;
        private final short[] prev;
        private double pos;

        public Resampler(int sourceRate, int targetRate, int channels) {
            this.step = (double) sourceRate / targetRate;
            this.channels = channels;
            this.prev = new short[channels];
        }

        /** An upper bound for the frames {@link #process} can produce from {@code inFrames}. */
        public int maxOutputFrames(int inFrames) {
            return (int) Math.ceil(inFrames / step) + 2;
        }

        /** Converts a chunk; returns the frames written to {@code out} (see {@link #maxOutputFrames}). */
        public int process(short[] in, int inFrames, short[] out) {
            if (inFrames <= 0) return 0;
            int n = 0;
            while (true) {
                int i0 = (int) Math.floor(pos);
                if (i0 + 1 > inFrames - 1) break;
                double frac = pos - i0;
                for (int c = 0; c < channels; c++) {
                    int a = i0 < 0 ? prev[c] : in[i0 * channels + c];
                    int b = in[(i0 + 1) * channels + c];
                    out[n * channels + c] = (short) Math.round(a + (b - a) * frac);
                }
                n++;
                pos += step;
            }
            System.arraycopy(in, (inFrames - 1) * channels, prev, 0, channels);
            pos -= inFrames;
            return n;
        }
    }
}
