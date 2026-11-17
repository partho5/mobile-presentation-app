package com.jovoc.facecampresentationrecorder.aiedit.engine.render;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.OutputTimeline;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the output soundtrack as a small AAC file that {@link RenderPipeline} then copies into the video.
 *
 * The source audio is decoded to PCM piece by piece of the timeline: a cut drops its stretch, and where two
 * pieces meet at a cut the first one's sound runs ~5 ms past its end and fades out under the next one fading in,
 * so there is no click and no change of length. A speed segment is resampled (pitch goes with the speed, like
 * a fast-forward). Then the mix bus adds the {@link MixSource}s (sound effects, later cards), and AAC-LC encodes.
 *
 * The result is exactly as long as the output timeline: every piece is placed at its own output frame, so
 * audio and video stay in sync however many cuts there are; missing audio becomes silence. Memory stays flat.
 * Blocking: call from a worker thread; {@link CancellationException} when the flag is set.
 */
public final class AudioPath {

    /** Something mixed on top of the voice, on the output timeline. */
    public interface MixSource {
        /**
         * Adds this source's samples for output frames {@code [firstFrame, firstFrame + frames)} into
         * {@code acc} (interleaved floats, -1..1, {@code channels} per frame). Must not allocate per call.
         */
        void mixInto(float[] acc, long firstFrame, int frames, int rate, int channels);
    }

    private static final String TAG = "AudioPath";
    private static final long TIMEOUT_US = 10_000;
    private static final int CHUNK_FRAMES = 2048;
    /** A codec that hands back nothing for this long is stuck: fail instead of hanging. */
    private static final long STALL_MS = 10_000;
    private static final int CROSSFADE_MS = 5;
    /** Longest lead-in silence added when a piece's audio begins after its start. */
    private static final long MAX_LEAD_US = 5_000_000L;
    private static final int DEFAULT_RATE = 44_100;
    private static final int[] AAC_RATES = {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000};
    private static final int MONO_BPS = 96_000;
    private static final int STEREO_BPS = 128_000;

    private final File source;
    private final MediaProbe probe;
    private final OutputTimeline timeline;
    private final AtomicBoolean cancel;
    private final List<MixSource> sounds = new ArrayList<>();
    private int rate;
    private int channels;
    private int bitrate;

    /** The source and its probe; the timeline says which stretches play and how fast. */
    public AudioPath(File source, MediaProbe probe, OutputTimeline timeline, AtomicBoolean cancel) {
        this.source = source;
        this.probe = probe;
        this.timeline = timeline;
        this.cancel = cancel;
        format(0, 0, 0);
    }

    /** Output sample rate, channels (1 or 2) and bitrate; 0 takes them from the source. */
    public AudioPath format(int sampleRate, int channelCount, int bitsPerSecond) {
        int r = sampleRate > 0 ? sampleRate : probe.audioRate > 0 ? probe.audioRate : DEFAULT_RATE;
        rate = aacRate(r);
        int c = channelCount > 0 ? channelCount : probe.audioChannels;
        channels = Math.max(1, Math.min(2, c));
        bitrate = bitsPerSecond > 0 ? bitsPerSecond : channels == 1 ? MONO_BPS : STEREO_BPS;
        return this;
    }

    /** Adds a source to the mix bus. */
    public AudioPath addSound(MixSource sound) {
        if (sound != null) sounds.add(sound);
        return this;
    }

    /** False when there is nothing to hear: no source audio and no sounds (the output then has no audio track). */
    public boolean producesAudio() {
        return probe.hasAudio || !sounds.isEmpty();
    }

    public int getBitrate() {
        return bitrate;
    }

    /** A rate the AAC encoder takes: the given one when standard, else 48 kHz above it, 44.1 kHz below. */
    static int aacRate(int r) {
        for (int ok : AAC_RATES) if (ok == r) return r;
        return r > 48_000 ? 48_000 : DEFAULT_RATE;
    }

    private static long framesAt(double ms, int rate) {
        return Math.round(ms * rate / 1000.0);
    }

    private void throwIfCancelled() {
        if (cancel != null && cancel.get()) throw new CancellationException("Render cancelled");
    }

    /** Writes the soundtrack to {@code out} (an ".m4a"; replaced). */
    public void render(File out) throws IOException {
        PcmReader reader = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        Sink sink = null;
        try {
            if (probe.hasAudio) reader = new PcmReader(source, channels);
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * channels * 2);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            out.delete();
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            sink = new Sink(encoder, muxer);
            writePieces(reader, sink);
            sink.finish();
            muxer.stop();
            sink.muxStarted = false;
        } catch (RuntimeException e) {
            if (e instanceof CancellationException) throw e;
            throw new IOException("Audio render failed: " + e.getMessage(), e);
        } finally {
            if (reader != null) reader.release();
            if (encoder != null) {
                final MediaCodec enc = encoder;
                quietly("audio encoder stop", enc::stop);
                quietly("audio encoder release", enc::release);
            }
            if (muxer != null) {
                final MediaMuxer mx = muxer;
                if (sink != null && sink.muxStarted) quietly("audio muxer stop", mx::stop);
                quietly("audio muxer release", mx::release);
            }
        }
    }

    /** Every piece at its own output frame, crossfaded across cuts, padded with silence to the exact length. */
    private void writePieces(PcmReader reader, Sink sink) throws IOException {
        int ch = channels;
        long total = framesAt(timeline.exactOutputDurationMs(), rate);
        int xf = Math.max(1, rate * CROSSFADE_MS / 1000);
        float[] block = new float[CHUNK_FRAMES * ch];
        float[] tail = new float[xf * ch];
        float[] nextTail = new float[xf * ch];
        int tailLen = 0;
        Resampler resampler = reader != null ? new Resampler(ch, rate) : null;
        long written = 0;
        int n = timeline.pieceCount();
        for (int i = 0; i < n; i++) {
            throwIfCancelled();
            long end = i + 1 < n ? framesAt(timeline.pieceOutputStartMs(i + 1), rate) : total;
            long need = Math.max(0, Math.min(total, end) - written);
            boolean cutAfter = i + 1 < n && timeline.pieceSourceEndMs(i) != timeline.pieceSourceStartMs(i + 1);
            int extra = cutAfter ? xf : 0;
            double speed = timeline.pieceRate(i);
            if (reader != null) {
                long startUs = timeline.pieceSourceStartMs(i) * 1000;
                long endUs = timeline.pieceSourceEndMs(i) * 1000;
                if (cutAfter) endUs += (long) Math.ceil(CROSSFADE_MS * 1000 * speed) + 1000;
                // The tail runs to the last sample whatever the rounded duration says
                if (i == n - 1 && timeline.pieceSourceEndMs(i) >= timeline.getSourceDurationMs()) {
                    endUs = Long.MAX_VALUE;
                }
                reader.open(startUs, endUs);
                resampler.reset(reader, speed);
            }
            long target = need + extra;
            long produced = 0;
            while (produced < target) {
                throwIfCancelled();
                int want = (int) Math.min(CHUNK_FRAMES, target - produced);
                int got = resampler != null ? resampler.read(block, want) : 0;
                // Audio that runs out early becomes silence, so the next piece still lands on its frame
                if (got < want) Arrays.fill(block, got * ch, want * ch, 0f);
                for (int k = 0; k < want && produced + k < tailLen; k++) {
                    int p = (int) (produced + k);
                    float in = (p + 0.5f) / tailLen;
                    for (int c = 0; c < ch; c++) {
                        block[k * ch + c] = block[k * ch + c] * in + tail[p * ch + c] * (1f - in);
                    }
                }
                int keep = (int) Math.max(0, Math.min(want, need - produced));
                if (keep > 0) sink.write(block, keep);
                for (int k = keep; k < want; k++) {
                    int t = (int) (produced + k - need);
                    if (t >= 0 && t < xf) System.arraycopy(block, k * ch, nextTail, t * ch, ch);
                }
                produced += want;
            }
            float[] swap = tail;
            tail = nextTail;
            nextTail = swap;
            tailLen = extra;
            written += need;
        }
        Arrays.fill(block, 0f);
        while (written < total) {
            throwIfCancelled();
            int want = (int) Math.min(CHUNK_FRAMES, total - written);
            sink.write(block, want);
            written += want;
        }
    }

    private static void quietly(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            Log.w(TAG, "Release failed: " + what, e);
        }
    }

    // ---------------------------------------------------------------- encode

    /** Collects output frames in chunks, adds the mix bus, and feeds the AAC encoder and the muxer. */
    private final class Sink {
        private final MediaCodec encoder;
        private final MediaMuxer muxer;
        private final float[] chunk = new float[CHUNK_FRAMES * channels];
        private final short[] pcm = new short[CHUNK_FRAMES * channels];
        private final ByteBuffer bytes = ByteBuffer.allocateDirect(CHUNK_FRAMES * channels * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private int fill;
        private long chunkStartFrame;
        private long framesQueued;
        private int muxTrack = -1;
        boolean muxStarted;
        private boolean outputDone;
        private long lastProgressMs = System.currentTimeMillis();

        Sink(MediaCodec encoder, MediaMuxer muxer) {
            this.encoder = encoder;
            this.muxer = muxer;
        }

        /** Takes {@code frames} interleaved frames from {@code src}. */
        void write(float[] src, int frames) throws IOException {
            int from = 0;
            while (from < frames) {
                int n = Math.min(frames - from, CHUNK_FRAMES - fill);
                System.arraycopy(src, from * channels, chunk, fill * channels, n * channels);
                fill += n;
                from += n;
                if (fill == CHUNK_FRAMES) flushChunk();
            }
        }

        /** Encodes what is left, then ends the stream and waits for the last packet. */
        void finish() throws IOException {
            if (fill > 0) flushChunk();
            while (true) {
                throwIfCancelled();
                checkStall();
                int in = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (in >= 0) {
                    encoder.queueInputBuffer(in, 0, 0, framesQueued * 1_000_000L / rate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }
                drain(false);
            }
            while (!outputDone) drain(true);
            if (!muxStarted) throw new IOException("No audio rendered");
        }

        private void flushChunk() throws IOException {
            for (MixSource s : sounds) s.mixInto(chunk, chunkStartFrame, fill, rate, channels);
            int samples = fill * channels;
            for (int i = 0; i < samples; i++) {
                float v = chunk[i];
                if (v > 1f) v = 1f;
                else if (v < -1f) v = -1f;
                pcm[i] = (short) Math.round(v * 32767f);
            }
            bytes.clear();
            bytes.asShortBuffer().put(pcm, 0, samples);
            bytes.limit(samples * 2);
            int frameBytes = channels * 2;
            while (bytes.hasRemaining()) {
                throwIfCancelled();
                checkStall();
                int in = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (in >= 0) {
                    ByteBuffer buf = encoder.getInputBuffer(in);
                    if (buf == null) throw new IOException("No encoder input buffer");
                    buf.clear();
                    int len = Math.min(bytes.remaining(), buf.remaining());
                    len -= len % frameBytes;
                    int oldLimit = bytes.limit();
                    bytes.limit(bytes.position() + len);
                    buf.put(bytes);
                    bytes.limit(oldLimit);
                    encoder.queueInputBuffer(in, 0, len, framesQueued * 1_000_000L / rate, 0);
                    framesQueued += len / frameBytes;
                }
                drain(false);
            }
            chunkStartFrame += fill;
            fill = 0;
        }

        private void drain(boolean endOfStream) throws IOException {
            while (!outputDone) {
                int out = encoder.dequeueOutputBuffer(info, endOfStream ? TIMEOUT_US : 0);
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (endOfStream) checkStall();
                    return;
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                    lastProgressMs = System.currentTimeMillis();
                } else if (out >= 0) {
                    lastProgressMs = System.currentTimeMillis();
                    ByteBuffer data = encoder.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxStarted && data != null) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        muxer.writeSampleData(muxTrack, data, info);
                    }
                    encoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
        }

        private void checkStall() throws IOException {
            if (System.currentTimeMillis() - lastProgressMs > STALL_MS) {
                throw new IOException("Audio encoder stalled");
            }
        }
    }

    // ---------------------------------------------------------------- resample

    /**
     * Linear-interpolating resampler pulling from a {@link PcmReader}: one output frame takes
     * {@code readerRate * speed / outRate} input frames, which covers both the rate change and the speed.
     */
    private static final class Resampler {
        private final int ch;
        private final int outRate;
        private final float[] in;
        private PcmReader reader;
        private double speed;
        private double pos;
        private int inLen;
        private boolean dry;

        Resampler(int channels, int outRate) {
            this.ch = channels;
            this.outRate = outRate;
            in = new float[CHUNK_FRAMES * 2 * channels];
        }

        void reset(PcmReader reader, double speed) {
            this.reader = reader;
            this.speed = speed;
            pos = 0;
            inLen = 0;
            dry = false;
        }

        /** Fills up to {@code frames} output frames; fewer once the reader's span is used up. */
        int read(float[] out, int frames) throws IOException {
            int k = 0;
            while (k < frames) {
                int i0 = (int) pos;
                if (i0 + 1 >= inLen) {
                    if (dry || !refill()) break;
                    continue;
                }
                float f = (float) (pos - i0);
                int a = i0 * ch;
                int b = a + ch;
                int o = k * ch;
                for (int c = 0; c < ch; c++) out[o + c] = in[a + c] + (in[b + c] - in[a + c]) * f;
                k++;
                // The reader's rate is only sure once the decoder has said it (HE-AAC doubles it)
                pos += (double) reader.rate() * speed / outRate;
            }
            return k;
        }

        /** Drops the frames already passed and reads more; false when the span has no more. */
        private boolean refill() throws IOException {
            int drop = Math.min((int) pos, inLen);
            int keep = inLen - drop;
            if (keep > 0 && drop > 0) System.arraycopy(in, drop * ch, in, 0, keep * ch);
            inLen = keep;
            pos -= drop;
            int got = reader.read(in, inLen, in.length / ch - inLen);
            if (got <= 0) {
                dry = true;
                return false;
            }
            inLen += got;
            return true;
        }
    }

    // ---------------------------------------------------------------- decode

    /**
     * The source's audio decoded to float PCM in the output channel count, at the decoder's own rate, for one
     * span of source time at a time ({@link #open}).
     */
    private static final class PcmReader {
        private final MediaExtractor extractor = new MediaExtractor();
        private final MediaCodec decoder;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private final int outChannels;
        private int decRate;
        private int decChannels;
        private long startUs;
        private long endUs;
        private boolean opened;
        private boolean inputDone;
        private boolean spanDone;
        private boolean spanHasAudio;
        private short[] raw = new short[0];
        private float[] queue = new float[CHUNK_FRAMES * 4];
        private int queueStart;
        private int queueEnd;

        PcmReader(File source, int outChannels) throws IOException {
            this.outChannels = outChannels;
            MediaCodec codec = null;
            try {
                extractor.setDataSource(source.getAbsolutePath());
                int track = MediaProbe.findTrack(extractor, "audio/");
                if (track < 0) throw new IOException("No audio track");
                extractor.selectTrack(track);
                MediaFormat format = extractor.getTrackFormat(track);
                decRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : DEFAULT_RATE;
                decChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                if (decRate <= 0) decRate = DEFAULT_RATE;
                if (decChannels <= 0) decChannels = 1;
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                codec.configure(format, null, null, 0);
                codec.start();
            } catch (IOException | RuntimeException e) {
                if (codec != null) codec.release();
                extractor.release();
                throw new IOException("Cannot decode the source audio", e);
            }
            decoder = codec;
        }

        int rate() {
            return decRate;
        }

        /** Starts reading source time {@code [startUs, endUs)}. */
        void open(long startUs, long endUs) {
            this.startUs = startUs;
            this.endUs = endUs;
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            if (opened) decoder.flush();
            opened = true;
            inputDone = false;
            spanDone = false;
            spanHasAudio = false;
            queueStart = queueEnd = 0;
        }

        /** Reads up to {@code frames} frames into {@code dst} at frame {@code offFrames}; 0 once the span is done. */
        int read(float[] dst, int offFrames, int frames) throws IOException {
            long lastProgressMs = System.currentTimeMillis();
            int want = frames * outChannels;
            while (queueEnd - queueStart < want && !spanDone) {
                if (fill()) lastProgressMs = System.currentTimeMillis();
                else if (System.currentTimeMillis() - lastProgressMs > STALL_MS) {
                    throw new IOException("Audio decoder stalled");
                }
            }
            int have = Math.min(want, queueEnd - queueStart);
            System.arraycopy(queue, queueStart, dst, offFrames * outChannels, have);
            queueStart += have;
            return have / outChannels;
        }

        /** Feeds and drains the decoder once; true when it handed something back. */
        private boolean fill() {
            if (!inputDone) {
                int in = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (in >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(in);
                    long t = extractor.getSampleTime();
                    int size = -1;
                    if (t >= 0 && t < endUs && buf != null) size = extractor.readSampleData(buf, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(in, 0, size, t, 0);
                        extractor.advance();
                    }
                }
            }
            int out = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = decoder.getOutputFormat();
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE) && f.getInteger(MediaFormat.KEY_SAMPLE_RATE) > 0) {
                    decRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) && f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) > 0) {
                    decChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
                return true;
            }
            if (out < 0) return false;
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (info.size > 0 && !spanDone) {
                ByteBuffer data = decoder.getOutputBuffer(out);
                if (data != null) {
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    append(data, info.presentationTimeUs);
                }
            }
            decoder.releaseOutputBuffer(out, false);
            if (eos) spanDone = true;
            return true;
        }

        /** Trims a decoded buffer to the span, converts its channels and queues it. */
        private void append(ByteBuffer data, long ptsUs) {
            int frames = data.remaining() / 2 / decChannels;
            if (raw.length < frames * decChannels) raw = new short[frames * decChannels];
            data.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(raw, 0, frames * decChannels);
            // Whole frames before the span start were only decoded to get there
            long skip = Math.max(0, (startUs - ptsUs) * decRate / 1_000_000L);
            long endFrame = endUs == Long.MAX_VALUE ? Long.MAX_VALUE
                    : (endUs - ptsUs) * decRate / 1_000_000L;
            if (endFrame <= 0 || skip >= frames) {
                if (endFrame <= 0) spanDone = true;
                return;
            }
            if (!spanHasAudio) {
                spanHasAudio = true;
                // Audio that starts after the span start (a late first sample): keep it in place
                long lead = Math.min(MAX_LEAD_US, ptsUs - startUs);
                if (lead > 0) {
                    int gap = (int) (lead * decRate / 1_000_000L);
                    ensureRoom(gap * outChannels);
                    Arrays.fill(queue, queueEnd, queueEnd + gap * outChannels, 0f);
                    queueEnd += gap * outChannels;
                }
            }
            int from = (int) skip;
            int to = (int) Math.min(frames, endFrame);
            if (endFrame <= frames) spanDone = true;
            int count = to - from;
            if (count <= 0) return;
            ensureRoom(count * outChannels);
            float scale = 1f / 32768f;
            for (int f = from; f < to; f++) {
                int s = f * decChannels;
                if (outChannels == 1) {
                    float sum = 0f;
                    for (int c = 0; c < decChannels; c++) sum += raw[s + c];
                    queue[queueEnd++] = sum / decChannels * scale;
                } else {
                    float l = raw[s] * scale;
                    float r = decChannels > 1 ? raw[s + 1] * scale : l;
                    queue[queueEnd++] = l;
                    queue[queueEnd++] = r;
                }
            }
        }

        /** Makes room for {@code count} more samples at the queue's end, compacting before growing. */
        private void ensureRoom(int count) {
            int live = queueEnd - queueStart;
            if (queueStart > 0 && queueEnd + count > queue.length) {
                System.arraycopy(queue, queueStart, queue, 0, live);
                queueStart = 0;
                queueEnd = live;
            }
            if (queueEnd + count > queue.length) {
                queue = Arrays.copyOf(queue, Math.max(queue.length * 2, queueEnd + count));
            }
        }

        void release() {
            quietly("pcm decoder stop", decoder::stop);
            quietly("pcm decoder release", decoder::release);
            quietly("pcm extractor", extractor::release);
        }
    }
}
