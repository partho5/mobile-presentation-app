package com.jovoc.facecampresentationrecorder.edit;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cheap loudness profile of an audio file for the editor's audio lane: decodes it once and keeps
 * the loudest sample of each of N equal slices. Slow-ish for very long files, so call it off the
 * main thread; it checks the cancel flag between buffers.
 */
public final class AudioPeaks {

    public static final int BUCKETS = 240;

    private static final long TIMEOUT_US = 10_000;
    private static final long STALL_MS = 15_000;
    /** Every n-th sample is looked at: plenty for a picture, and much less work. */
    private static final int SAMPLE_STEP = 4;

    private AudioPeaks() {}

    /**
     * Returns {@code buckets} values in 0..1 (loudest slice = 1, shaped so quiet speech still
     * shows), over {@code durationMs} of audio. {@code location} is a file path or a content/file
     * URI. All zeros for a silent file.
     */
    public static float[] compute(Context context, String location, long durationMs, int buckets,
                                  AtomicBoolean cancel) throws IOException {
        if (durationMs <= 0 || buckets <= 0) throw new IOException("No duration");
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            if (location.startsWith("content:") || location.startsWith("file:")) {
                extractor.setDataSource(context.getApplicationContext(), Uri.parse(location), null);
            } else {
                extractor.setDataSource(location);
            }
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount() && track < 0; i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                }
            }
            if (track < 0) throw new IOException("No audio track");
            extractor.selectTrack(track);
            int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();

            float[] peaks = new float[buckets];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long lastProgress = System.currentTimeMillis();
            while (!outputDone) {
                if (cancel != null && cancel.get()) throw new CancellationException();
                if (!inputDone) {
                    int in = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(in);
                        int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = decoder.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (out >= 0) {
                    lastProgress = System.currentTimeMillis();
                    ByteBuffer ob = decoder.getOutputBuffer(out);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset).limit(info.offset + info.size);
                        ShortBuffer pcm = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int max = 0;
                        for (int i = 0; i < pcm.remaining(); i += SAMPLE_STEP) {
                            int v = Math.abs((int) pcm.get(pcm.position() + i));
                            if (v > max) max = v;
                        }
                        long frames = info.size / 2 / Math.max(1, channels);
                        long startMs = info.presentationTimeUs / 1000;
                        long endMs = startMs + frames * 1000 / Math.max(1, rate);
                        int b0 = bucketOf(startMs, durationMs, buckets);
                        int b1 = Math.max(b0, bucketOf(endMs, durationMs, buckets));
                        float level = max / 32768f;
                        for (int b = b0; b <= b1; b++) peaks[b] = Math.max(peaks[b], level);
                    }
                    decoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (System.currentTimeMillis() - lastProgress > STALL_MS) {
                    throw new IOException("Audio decoder stalled");
                }
            }
            return shape(peaks);
        } catch (RuntimeException e) {
            if (e instanceof CancellationException) throw e;
            throw new IOException("Cannot read audio loudness", e);
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (RuntimeException ignored) {}
                try { decoder.release(); } catch (RuntimeException ignored) {}
            }
            extractor.release();
        }
    }

    private static int bucketOf(long ms, long durationMs, int buckets) {
        return (int) Math.max(0, Math.min(buckets - 1, ms * buckets / durationMs));
    }

    /** Scales so the loudest slice is 1 and takes a square root, which lifts quiet parts. */
    static float[] shape(float[] raw) {
        float max = 0;
        for (float v : raw) max = Math.max(max, v);
        if (max <= 0) return raw;
        float[] out = new float[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = (float) Math.sqrt(raw[i] / max);
        return out;
    }
}
