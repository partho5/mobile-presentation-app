package com.jovoc.facecampresentationrecorder.edit;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the edited soundtrack when the audio is not a plain copy: the master's own audio (kept
 * stretches only, with its gain) summed with the added tracks (trimmed, offset on the result's
 * timeline, with their own gain), encoded as AAC-LC into a small audio-only file that
 * {@link EditRenderer} then copies into the final video.
 *
 * The result is exactly as long as the edited video: an added track that runs past the end is
 * clipped there, a track (or the original) that ends early is followed by silence. Mixing is done
 * by {@link AudioMixer} in chunks, so memory stays flat for a long recording. Output format: the
 * master's sample rate when it has audio (else the first added track's), mono if everything is
 * mono, otherwise stereo; 128 kbps mono, 192 kbps stereo.
 *
 * Blocking: call from a worker thread. {@link CancellationException} when the flag is set.
 */
final class AudioMixRenderer {

    private static final String TAG = "AudioMixRenderer";
    private static final long TIMEOUT_US = 10_000;
    private static final int CHUNK_FRAMES = 2048;
    private static final int DEFAULT_RATE = 44_100;
    /** A codec that hands back nothing for this long is stuck: fail instead of hanging. */
    private static final long STALL_MS = 10_000;
    /** Longest lead-in silence added when a track's audio begins after its span start. */
    private static final long MAX_LEAD_US = 5_000_000L;
    static final int MONO_BPS = 128_000;
    static final int STEREO_BPS = 192_000;
    /** Upper bound of what the mixed track costs, for the output size estimate. */
    static final int MAX_BPS = STEREO_BPS;

    /** About 11 days: far past any file, small enough that microsecond maths cannot overflow. */
    private static final long OPEN_END_MS = 1_000_000_000L;

    private final Context context;
    private final AtomicBoolean cancelFlag;

    AudioMixRenderer(Context context, AtomicBoolean cancelFlag) {
        this.context = context == null ? null : context.getApplicationContext();
        this.cancelFlag = cancelFlag;
    }

    private void throwIfCancelled() {
        if (cancelFlag != null && cancelFlag.get()) throw new CancellationException("Edit cancelled");
    }

    /**
     * @param ranges   kept stretches of the master (what the video keeps), in order
     * @param expected length of the edited video in ms: the length of the soundtrack
     * @param useMasterAudio whether the master's audio takes part (it has audio, is not removed)
     */
    void render(EditProject project, File master, boolean useMasterAudio,
                List<EditProject.Range> ranges, long expectedMs, File output) throws IOException {
        List<PcmSource> sources = new ArrayList<>();
        float[] gains;
        MediaMuxer muxer = null;
        MediaCodec encoder = null;
        boolean muxStarted = false;
        try {
            // Sources first: they decide the output format
            PcmSource original = null;
            EditProject.AudioSettings settings = project.getOriginal();
            float originalGain = AudioMixer.gainFor(settings.volume, settings.muted);
            if (useMasterAudio && originalGain > 0f) {
                List<long[]> spans = new ArrayList<>();
                for (EditProject.Range r : ranges) spans.add(new long[]{r.startMs * 1000, r.endMs * 1000});
                original = new PcmSource(context, master.getAbsolutePath(), spans);
                sources.add(original);
            }
            List<EditProject.AddedAudio> added = project.getAdded();
            List<PcmSource> addedSources = new ArrayList<>();
            for (EditProject.AddedAudio a : added) {
                List<long[]> spans = new ArrayList<>();
                long in = Math.max(0, a.sourceInMs);
                // A missing out point means "to the end of the file"
                long out = a.sourceOutMs > in ? a.sourceOutMs : OPEN_END_MS;
                spans.add(new long[]{in * 1000, out * 1000});
                PcmSource s = new PcmSource(context, a.uri, spans);
                addedSources.add(s);
                sources.add(s);
            }

            int rate = 0;
            int channels = 1;
            if (useMasterAudio) {
                rate = probeRate(master.getAbsolutePath());
            }
            for (PcmSource s : sources) {
                if (rate <= 0) rate = s.sourceRate;
                channels = Math.max(channels, s.sourceChannels);
            }
            if (rate <= 0) rate = DEFAULT_RATE;
            // Bluetooth and phone-call recordings can be 8/16 kHz or oddly rated: stay on a rate AAC accepts
            rate = AudioMixer.aacRateFor(rate);
            channels = Math.max(1, Math.min(2, channels));
            for (PcmSource s : sources) s.start(rate, channels);

            gains = new float[addedSources.size()];
            for (int i = 0; i < gains.length; i++) {
                gains[i] = AudioMixer.gainFor(added.get(i).volume, false);
            }

            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                    rate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, channels == 1 ? MONO_BPS : STEREO_BPS);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * channels * 2);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            output.delete();
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxTrack = -1;

            long totalFrames = AudioMixer.framesForMs(expectedMs, rate);
            long[] startFrames = new long[added.size()];
            for (int i = 0; i < startFrames.length; i++) {
                startFrames[i] = AudioMixer.framesForMs(added.get(i).startOnTimelineMs, rate);
            }

            float[] acc = new float[CHUNK_FRAMES * channels];
            short[] scratch = new short[CHUNK_FRAMES * channels];
            short[] pcm = new short[CHUNK_FRAMES * channels];
            ByteBuffer bytes = ByteBuffer.allocate(CHUNK_FRAMES * channels * 2)
                    .order(ByteOrder.LITTLE_ENDIAN);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long done = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            long lastProgressMs = System.currentTimeMillis();
            ByteBuffer pending = null; // PCM of the current chunk not yet handed to the encoder
            long pendingPtsUs = 0;
            while (!outputDone) {
                throwIfCancelled();
                if (System.currentTimeMillis() - lastProgressMs > STALL_MS) {
                    throw new IOException("Audio encoder stalled");
                }

                // Feed
                if (!inputDone) {
                    if (pending == null && done < totalFrames) {
                        int n = (int) Math.min(CHUNK_FRAMES, totalFrames - done);
                        java.util.Arrays.fill(acc, 0, n * channels, 0f);
                        if (original != null) {
                            int got = original.read(scratch, 0, n);
                            AudioMixer.accumulate(acc, 0, scratch, 0, got, channels, originalGain);
                        }
                        for (int i = 0; i < addedSources.size(); i++) {
                            int off = AudioMixer.placementOffset(done, n, startFrames[i]);
                            if (off < 0 || gains[i] == 0f) continue;
                            int got = addedSources.get(i).read(scratch, 0, n - off);
                            AudioMixer.accumulate(acc, off, scratch, 0, got, channels, gains[i]);
                        }
                        AudioMixer.toPcm16(acc, n * channels, pcm);
                        bytes.clear();
                        bytes.asShortBuffer().put(pcm, 0, n * channels);
                        bytes.limit(n * channels * 2);
                        pending = bytes;
                        pendingPtsUs = done * 1_000_000L / rate;
                        done += n;
                    }
                    int in = encoder.dequeueInputBuffer(0);
                    if (in >= 0) {
                        ByteBuffer buf = encoder.getInputBuffer(in);
                        if (pending != null && buf != null) {
                            int len = Math.min(pending.remaining(), buf.capacity());
                            int oldLimit = pending.limit();
                            pending.limit(pending.position() + len);
                            buf.clear();
                            buf.put(pending);
                            pending.limit(oldLimit);
                            long ptsUs = pendingPtsUs;
                            // Advance the stamp past the bytes just queued (2 bytes per sample)
                            pendingPtsUs += (long) (len / 2 / channels) * 1_000_000L / rate;
                            boolean last = !pending.hasRemaining() && done >= totalFrames;
                            encoder.queueInputBuffer(in, 0, len, ptsUs,
                                    last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                            if (last) inputDone = true;
                            if (!pending.hasRemaining()) pending = null;
                        } else if (pending == null && done >= totalFrames) {
                            encoder.queueInputBuffer(in, 0, 0, pendingPtsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                // Drain
                int out = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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
            if (!muxStarted) throw new IOException("No audio rendered");
            muxer.stop();
            muxStarted = false;
        } catch (RuntimeException e) {
            if (e instanceof CancellationException) throw e;
            throw new IOException("Audio mixing failed: " + e.getMessage(), e);
        } finally {
            for (PcmSource s : sources) s.release();
            if (encoder != null) {
                final MediaCodec enc = encoder;
                quietly("audio encoder stop", enc::stop);
                quietly("audio encoder release", enc::release);
            }
            if (muxer != null) {
                final MediaMuxer mx = muxer;
                if (muxStarted) quietly("audio muxer stop", mx::stop);
                quietly("audio muxer release", mx::release);
            }
        }
    }

    private static void quietly(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            Log.w(TAG, "Release failed: " + what, e);
        }
    }

    private static int probeRate(String path) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(path);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")
                        && f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    return f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
            }
            return 0;
        } finally {
            ex.release();
        }
    }

    /**
     * One audio file decoded to 16-bit PCM at the output rate and channel count, covering a list
     * of spans of the file (microseconds, played one after another). {@link #read} hands out
     * exactly the frames asked for, fewer only once every span is used up.
     */
    private static final class PcmSource {
        final int sourceRate;
        final int sourceChannels;

        private final MediaExtractor extractor = new MediaExtractor();
        private final MediaFormat trackFormat;
        private final int track;
        private final List<long[]> spans;
        private MediaCodec decoder;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        private int outRate;
        private int outChannels;
        private int decRate;
        private int decChannels;
        private AudioMixer.Resampler resampler;

        private int spanIndex = -1;
        private boolean inputDone;
        private boolean decoderDone;
        private boolean finished;
        private boolean spanHasAudio;
        private long lastProgressMs;

        private short[] queue = new short[0];
        private int queueStart;
        private int queueEnd;

        PcmSource(Context context, String location, List<long[]> spans) throws IOException {
            this.spans = spans;
            try {
                if (location.startsWith("content:") || location.startsWith("file:")
                        || location.startsWith("http")) {
                    if (context == null) throw new IOException("No context to open " + location);
                    extractor.setDataSource(context, Uri.parse(location), null);
                } else {
                    extractor.setDataSource(location);
                }
            } catch (IOException | RuntimeException e) {
                extractor.release();
                throw new IOException("Cannot open audio " + location, e);
            }
            int found = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                extractor.release();
                throw new IOException("No audio track in " + location);
            }
            track = found;
            trackFormat = extractor.getTrackFormat(found);
            extractor.selectTrack(found);
            int r = trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : DEFAULT_RATE;
            int c = trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            sourceRate = r > 0 ? r : DEFAULT_RATE;
            sourceChannels = c > 0 ? c : 1;
        }

        void start(int rate, int channels) throws IOException {
            outRate = rate;
            outChannels = channels;
            decRate = sourceRate;
            decChannels = sourceChannels;
            resampler = new AudioMixer.Resampler(decRate, outRate, outChannels);
            decoder = MediaCodec.createDecoderByType(trackFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(trackFormat, null, null, 0);
            decoder.start();
            nextSpan();
        }

        void release() {
            quietly("pcm decoder stop", () -> {
                if (decoder != null) decoder.stop();
            });
            quietly("pcm decoder release", () -> {
                if (decoder != null) decoder.release();
            });
            quietly("pcm extractor", extractor::release);
        }

        /** Reads up to {@code frames} output frames into {@code out}; returns how many. */
        int read(short[] out, int outFrame, int frames) throws IOException {
            int want = frames * outChannels;
            lastProgressMs = System.currentTimeMillis();
            while (queueEnd - queueStart < want && !finished) {
                fill();
                if (System.currentTimeMillis() - lastProgressMs > STALL_MS) {
                    throw new IOException("Audio decoder stalled");
                }
            }
            int have = Math.min(want, queueEnd - queueStart);
            System.arraycopy(queue, queueStart, out, outFrame * outChannels, have);
            queueStart += have;
            return have / outChannels;
        }

        /** Moves to the next span; false when none is left. */
        private boolean nextSpan() {
            spanIndex++;
            if (spanIndex >= spans.size()) {
                finished = true;
                return false;
            }
            extractor.seekTo(spans.get(spanIndex)[0], MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            if (decoder != null && spanIndex > 0) decoder.flush();
            inputDone = false;
            decoderDone = false;
            spanHasAudio = false;
            lastProgressMs = System.currentTimeMillis();
            return true;
        }

        /** Decodes a little more into the queue (or advances to the next span). */
        private void fill() throws IOException {
            if (decoderDone) {
                nextSpan();
                return;
            }
            long[] span = spans.get(spanIndex);
            if (!inputDone) {
                int in = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (in >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(in);
                    long t = extractor.getSampleTime();
                    int size = -1;
                    if (t >= 0 && t < span[1] && buf != null) size = extractor.readSampleData(buf, 0);
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
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || out >= 0) {
                lastProgressMs = System.currentTimeMillis();
            }
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = decoder.getOutputFormat();
                int rate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : decRate;
                int ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : decChannels;
                if (rate <= 0) rate = decRate;
                if (ch <= 0) ch = decChannels;
                if (rate != decRate || ch != decChannels) {
                    decRate = rate;
                    decChannels = ch;
                    resampler = new AudioMixer.Resampler(decRate, outRate, outChannels);
                }
            } else if (out >= 0) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0) {
                    ByteBuffer data = decoder.getOutputBuffer(out);
                    if (data != null) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        append(data, info.presentationTimeUs, span);
                    }
                }
                decoder.releaseOutputBuffer(out, false);
                if (eos) decoderDone = true;
            }
        }

        /** Trims a decoded buffer to the span, converts it and queues it. */
        private void append(ByteBuffer data, long ptsUs, long[] span) {
            int frames = data.remaining() / 2 / decChannels;
            short[] pcm = new short[frames * decChannels];
            data.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
            // Whole frames before the span start were only decoded to get there; drop them
            long skip = Math.max(0, (span[0] - ptsUs) * decRate / 1_000_000L);
            long endFrame = (span[1] - ptsUs) * decRate / 1_000_000L; // exclusive, in this buffer
            if (endFrame <= 0 || skip >= frames) {
                if (endFrame <= 0) decoderDone = true;
                return;
            }
            if (!spanHasAudio) {
                spanHasAudio = true;
                // Audio that starts after the span start (late first sample): keep it in sync
                long lead = Math.min(MAX_LEAD_US, ptsUs - span[0]);
                if (lead > 0) {
                    int gap = (int) (lead * outRate / 1_000_000L);
                    if (gap > 0) push(new short[gap * outChannels], gap * outChannels);
                }
            }
            int from = (int) skip;
            int to = (int) Math.min(frames, endFrame);
            if (endFrame <= frames) decoderDone = true;
            int count = to - from;
            if (count <= 0) return;
            short[] part = new short[count * decChannels];
            System.arraycopy(pcm, from * decChannels, part, 0, part.length);
            short[] shaped = AudioMixer.convertChannels(part, count, decChannels, outChannels);
            short[] resampled = new short[resampler.maxOutputFrames(count) * outChannels];
            int n = resampler.process(shaped, count, resampled);
            push(resampled, n * outChannels);
        }

        private void push(short[] samples, int count) {
            int live = queueEnd - queueStart;
            if (queueStart > 0 && queueEnd + count > queue.length) {
                System.arraycopy(queue, queueStart, queue, 0, live);
                queueStart = 0;
                queueEnd = live;
            }
            if (queueEnd + count > queue.length) {
                short[] bigger = new short[Math.max(queue.length * 2, queueEnd + count)];
                System.arraycopy(queue, queueStart, bigger, 0, live);
                queue = bigger;
                queueStart = 0;
                queueEnd = live;
            }
            System.arraycopy(samples, 0, queue, queueEnd, count);
            queueEnd += count;
        }
    }
}
