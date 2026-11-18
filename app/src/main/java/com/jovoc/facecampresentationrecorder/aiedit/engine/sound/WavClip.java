package com.jovoc.facecampresentationrecorder.aiedit.engine.sound;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A short sound effect read from a WAV file into memory as floats: PCM 8, 16, 24 or 32 bit, or 32-bit float, one
 * or two channels (more than two keep the first two). Sound effects are short, so a file over {@link #MAX_BYTES}
 * is refused and a clip longer than {@link #MAX_MS} is cut. Pure; no Android classes.
 */
public final class WavClip {

    static final long MAX_BYTES = 6L * 1024 * 1024;
    static final int MAX_MS = 4_000;

    private static final int PCM = 1;
    private static final int FLOAT = 3;
    private static final int EXTENSIBLE = 0xFFFE;

    public final int rate;
    /** 1 or 2. */
    public final int channels;
    public final int frames;
    /** Interleaved, -1..1. */
    private final float[] samples;

    WavClip(int rate, int channels, float[] samples) {
        this.rate = rate;
        this.channels = channels;
        this.samples = samples;
        this.frames = samples.length / channels;
    }

    /** The clip in {@code file}, or null when it is missing, too big, not a WAV this reads, or empty. */
    public static WavClip read(File file) {
        if (file == null || !file.isFile() || file.length() < 44 || file.length() > MAX_BYTES) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) break;
                off += n;
            }
            return parse(bytes, off);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Parses the first {@code length} bytes as a WAV; null when they are not one this reads. */
    static WavClip parse(byte[] b, int length) {
        if (length < 44 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) return null;
        int format = 0;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        long dataOff = -1;
        long dataLen = 0;
        long pos = 12;
        while (pos + 8 <= length) {
            long size = le32(b, (int) pos + 4);
            long body = pos + 8;
            if (tag(b, (int) pos, "fmt ") && body + 16 <= length) {
                format = le16(b, (int) body);
                channels = le16(b, (int) body + 2);
                rate = (int) le32(b, (int) body + 4);
                bits = le16(b, (int) body + 14);
                if (format == EXTENSIBLE && size >= 26 && body + 26 <= length) format = le16(b, (int) body + 24);
            } else if (tag(b, (int) pos, "data")) {
                dataOff = body;
                dataLen = Math.min(size, length - body);
                break;
            }
            pos = body + size + (size & 1);
        }
        if (dataOff < 0 || channels < 1 || rate < 8000 || rate > 192_000) return null;
        boolean pcm = format == PCM && (bits == 8 || bits == 16 || bits == 24 || bits == 32);
        boolean flt = format == FLOAT && bits == 32;
        if (!pcm && !flt) return null;

        int bytesPer = bits / 8;
        int stride = channels * bytesPer;
        long wholeFrames = dataLen / stride;
        int keep = (int) Math.min(wholeFrames, (long) rate * MAX_MS / 1000);
        if (keep <= 0) return null;
        int outCh = Math.min(2, channels);
        float[] out = new float[keep * outCh];
        for (int f = 0; f < keep; f++) {
            for (int c = 0; c < outCh; c++) {
                int at = (int) dataOff + f * stride + c * bytesPer;
                out[f * outCh + c] = flt ? Float.intBitsToFloat((int) le32(b, at)) : pcmSample(b, at, bits);
            }
        }
        return new WavClip(rate, outCh, out);
    }

    private static float pcmSample(byte[] b, int at, int bits) {
        switch (bits) {
            case 8:
                return ((b[at] & 0xFF) - 128) / 128f;
            case 16:
                return (short) ((b[at] & 0xFF) | (b[at + 1] << 8)) / 32768f;
            case 24:
                return ((b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | (b[at + 2] << 16)) / 8388608f;
            default:
                return (int) le32(b, at) / 2147483648f;
        }
    }

    /**
     * Sample {@code frame} (clamped into the clip) of output channel {@code outCh} of {@code outChannels} (1 or 2):
     * mono plays on both channels, stereo is averaged for a mono output.
     */
    float sample(int frame, int outCh, int outChannels) {
        int f = Math.max(0, Math.min(frames - 1, frame));
        if (channels == 1) return samples[f];
        if (outChannels == 1) return (samples[2 * f] + samples[2 * f + 1]) * 0.5f;
        return samples[2 * f + Math.min(1, outCh)];
    }

    private static boolean tag(byte[] b, int at, String t) {
        if (at < 0 || at + 4 > b.length) return false;
        for (int i = 0; i < 4; i++) if (b[at + i] != (byte) t.charAt(i)) return false;
        return true;
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    /** Little-endian unsigned 32 bit as a long (the sign bit kept as the top bit of an int when cast back). */
    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8) | ((b[at + 2] & 0xFFL) << 16) | ((b[at + 3] & 0xFFL) << 24);
    }
}
