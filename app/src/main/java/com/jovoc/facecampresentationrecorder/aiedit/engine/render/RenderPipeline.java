package com.jovoc.facecampresentationrecorder.aiedit.engine.render;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.GLES11Ext;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.jovoc.facecampresentationrecorder.aiedit.engine.EngineException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.CaptionPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.effects.VideoEffect;
import com.jovoc.facecampresentationrecorder.aiedit.engine.effects.VideoTransform;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.CropRect;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.OutputTimeline;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes one output file: the source played through an {@link OutputTimeline} (cuts and speed), cropped and
 * scaled to the output size, with the Canvas layers on top, and its soundtrack from {@link AudioPath}.
 *
 * Video: MediaExtractor + hardware decode onto an OES texture, the {@link Compositor} draws it onto the H.264
 * encoder's input surface, MediaMuxer writes the file. Every decoded frame is placed by its own source
 * timestamp: a frame inside a cut is dropped, a frame in a piece at rate r lands at
 * {@code piece output start + (pts - piece source start) / r}. Faster pieces would bring more frames than the
 * frame rate, so frames are thinned to a grid of the output frame rate. Pieces closer than
 * {@link #DECODE_THROUGH_GAP_US} are decoded through (cheaper than a new decoder and a seek), others start a
 * fresh decoder at the sync frame before them.
 *
 * Audio is built first into a temporary AAC file and copied in, interleaved with the video.
 *
 * The file is written as a hidden ".part.mp4" beside the output, read back and checked (length of both tracks),
 * and only then renamed into place. A failed or cancelled run ({@link CancellationException}) leaves no output and
 * no temp file. The source is only read. Blocking: call from a worker thread.
 */
public final class RenderPipeline {

    /** Hears the percentage of the file written. */
    public interface Progress {
        void onProgress(int percent);
    }

    private static final String TAG = "RenderPipeline";
    private static final long TIMEOUT_US = 10_000;
    /** Decode this far past a span end: with B-frames, later samples can still be earlier in time. */
    private static final long REORDER_MARGIN_US = 300_000;
    /** A cut shorter than this is decoded through and its frames dropped instead of seeking past it. */
    private static final long DECODE_THROUGH_GAP_US = 1_500_000;
    /** No decoded frame for this long means the decoder is stuck. */
    private static final long STALL_MS = 10_000;
    private static final long FRAME_WAIT_MS = 2_500;
    private static final int DRAIN_GIVE_UP_TRIES = 500;
    private static final int AUDIO_BUFFER_BYTES = 256 * 1024;
    /** Shortest output worth writing. */
    private static final long MIN_OUTPUT_MS = 100;
    /** How far the written length may be from the timeline's (frame and AAC packet rounding). */
    private static final long LENGTH_TOLERANCE_MS = 250;
    private static final long SPACE_MARGIN_BYTES = 8L * 1024 * 1024;

    private final File source;
    private final MediaProbe probe;
    private final OutputTimeline timeline;
    private final CropRect crop;
    private final int outW;
    private final int outH;
    private int videoBitrate = 8_000_000;
    private int frameRate = 30;
    private int audioRate;
    private int audioChannels;
    private int audioBitrate;
    private CaptionPainter painter;
    private List<VideoEffect> effects = Collections.emptyList();
    private final VideoTransform transform = new VideoTransform();
    private final List<AudioPath.MixSource> sounds = new ArrayList<>();
    private AtomicBoolean cancel;
    private Progress progress;

    // Pieces of the timeline, in microseconds
    private long[] pieceStartUs;
    private long[] pieceEndUs;
    private double[] pieceRate;
    private double[] pieceOutStartUs;

    // GL / decoder output
    private GlContext gl;
    private Compositor compositor;
    private LayerCanvas layer;
    private int oesTexture;
    private SurfaceTexture surfaceTexture;
    private Surface decoderSurface;
    private HandlerThread frameThread;
    private final Object frameLock = new Object();
    private boolean frameAvailable;
    private final float[] stMatrix = new float[16];

    // Frame grid
    private long frameIntervalUs;
    private long nextSlotUs;
    private long lastFrameUs;

    // Muxing
    private MediaMuxer muxer;
    private MediaFormat audioFormat;
    private int muxVideoTrack = -1;
    private int muxAudioTrack = -1;
    private boolean muxStarted;
    private long totalOutUs;
    private int lastPercent = -1;

    /**
     * @param crop the part of the upright source to show (fills the output), or null for the whole frame
     * @param outW output width, even
     * @param outH output height, even
     */
    public RenderPipeline(File source, MediaProbe probe, OutputTimeline timeline, CropRect crop, int outW, int outH) {
        this.source = source;
        this.probe = probe;
        this.timeline = timeline;
        this.crop = crop;
        this.outW = outW & ~1;
        this.outH = outH & ~1;
    }

    /** H.264 bitrate and the highest frame rate written. */
    public RenderPipeline video(int bitrate, int maxFrameRate) {
        this.videoBitrate = bitrate;
        this.frameRate = Math.max(1, maxFrameRate);
        return this;
    }

    /** AAC sample rate, channels and bitrate; 0 takes them from the source. */
    public RenderPipeline audio(int sampleRate, int channels, int bitrate) {
        this.audioRate = sampleRate;
        this.audioChannels = channels;
        this.audioBitrate = bitrate;
        return this;
    }

    public RenderPipeline withPainter(CaptionPainter painter) {
        this.painter = painter;
        return this;
    }

    /** The camera effects applied to the picture, by output time; null or empty for none. */
    public RenderPipeline withEffects(List<VideoEffect> effects) {
        this.effects = effects != null ? effects : Collections.<VideoEffect>emptyList();
        return this;
    }

    /** Adds a source to the audio mix bus. */
    public RenderPipeline withSound(AudioPath.MixSource sound) {
        if (sound != null) sounds.add(sound);
        return this;
    }

    public RenderPipeline withCancelFlag(AtomicBoolean flag) {
        this.cancel = flag;
        return this;
    }

    public RenderPipeline withProgress(Progress progress) {
        this.progress = progress;
        return this;
    }

    private void throwIfCancelled() {
        if (cancel != null && cancel.get()) throw new CancellationException("Render cancelled");
    }

    /** Runs one release step so a failure there cannot skip the steps after it. */
    private static void quietly(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            Log.w(TAG, "Release failed: " + what, e);
        }
    }

    /**
     * Renders to {@code output} (an ".mp4"; replaced only when the new file is complete, never the source).
     * Throws {@link EngineException} when it cannot be made and {@link CancellationException} when cancelled.
     */
    public void render(File output) throws IOException {
        if (isSameFile(source, output)) {
            throw new EngineException(EngineException.RENDER_FAILED, "Output would overwrite the source");
        }
        lastPercent = -1;
        throwIfCancelled();
        long expectedMs = timeline.outputDurationMs();
        if (timeline.pieceCount() == 0 || expectedMs < MIN_OUTPUT_MS) {
            throw new EngineException(EngineException.NOTHING_TO_RENDER, "Output too short");
        }
        totalOutUs = expectedMs * 1000;
        loadPieces();

        AudioPath audio = new AudioPath(source, probe, timeline, cancel).format(audioRate, audioChannels, audioBitrate);
        for (AudioPath.MixSource s : sounds) audio.addSound(s);
        boolean audioOut = audio.producesAudio();

        File dir = output.getAbsoluteFile().getParentFile();
        long audioBytes = audioOut ? (long) audio.getBitrate() / 8 * expectedMs / 1000 : 0;
        long needed = (long) videoBitrate / 8 * expectedMs / 1000 + audioBytes * 2 + SPACE_MARGIN_BYTES;
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs()) || dir.getUsableSpace() < needed) {
            throw new EngineException(EngineException.NO_SPACE, "Not enough free space: need " + needed + " bytes");
        }

        File part = new File(dir, "." + output.getName() + ".part.mp4");
        File audioTemp = new File(dir, part.getName() + ".audio.m4a");
        part.delete();
        audioTemp.delete();
        boolean ok = false;
        try {
            if (audioOut) audio.render(audioTemp);
            writeFile(part, audioOut ? audioTemp : null);
            verify(part, expectedMs, audioOut);
            if (!part.renameTo(output)) throw new IOException("Could not move the result into place");
            ok = true;
        } catch (IOException | RuntimeException e) {
            if (e instanceof CancellationException || e instanceof EngineException) throw e;
            throw new EngineException(EngineException.RENDER_FAILED, "Render failed: " + e.getMessage(), e);
        } finally {
            if (!ok) part.delete();
            audioTemp.delete();
        }
        report(100);
    }

    private static boolean isSameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    /** Copies the timeline's pieces into microsecond arrays; the last piece runs to the source's last frame. */
    private void loadPieces() {
        int n = timeline.pieceCount();
        pieceStartUs = new long[n];
        pieceEndUs = new long[n];
        pieceRate = new double[n];
        pieceOutStartUs = new double[n];
        for (int i = 0; i < n; i++) {
            pieceStartUs[i] = timeline.pieceSourceStartMs(i) * 1000;
            pieceEndUs[i] = timeline.pieceSourceEndMs(i) * 1000;
            pieceRate[i] = timeline.pieceRate(i);
            pieceOutStartUs[i] = timeline.pieceOutputStartMs(i) * 1000;
        }
        // The rounded duration can end before the last frame's timestamp
        if (timeline.pieceSourceEndMs(n - 1) >= timeline.getSourceDurationMs()) pieceEndUs[n - 1] = Long.MAX_VALUE;
    }

    /** The output time of a source frame, or -1 when the frame is cut. */
    private long outputUsOf(long ptsUs) {
        int lo = 0;
        int hi = pieceStartUs.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (pieceStartUs[mid] <= ptsUs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found < 0 || ptsUs >= pieceEndUs[found]) return -1;
        return Math.round(pieceOutStartUs[found] + (ptsUs - pieceStartUs[found]) / pieceRate[found]);
    }

    /** Whether a frame at {@code outUs} goes in: the first one at or after each slot of the frame-rate grid. */
    private boolean acceptFrame(long outUs) {
        if (outUs <= lastFrameUs || outUs >= totalOutUs) return false;
        long tolerance = frameIntervalUs / 4;
        if (outUs < nextSlotUs - tolerance) return false;
        lastFrameUs = outUs;
        nextSlotUs = ((outUs + tolerance) / frameIntervalUs + 1) * frameIntervalUs;
        return true;
    }

    // ---------------------------------------------------------------- pipeline

    private void writeFile(File part, File audioFile) throws IOException {
        MediaFormat encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
        encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        frameIntervalUs = 1_000_000L / frameRate;
        nextSlotUs = 0;
        lastFrameUs = -1;
        Log.d(TAG, "Render " + probe.width + "x" + probe.height + " -> " + outW + "x" + outH + ", "
                + videoBitrate + " bps, " + frameRate + " fps, " + pieceStartUs.length + " piece(s)");

        MediaCodec encoder = null;
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        Surface encoderInput = null;
        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInput = encoder.createInputSurface();
            encoder.start();
            setUpGl(encoderInput);

            muxer = new MediaMuxer(part.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxStarted = false;
            muxVideoTrack = -1;
            muxAudioTrack = -1;

            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(source.getAbsolutePath());
            int videoTrack = MediaProbe.findTrack(videoExtractor, "video/");
            videoExtractor.selectTrack(videoTrack);
            MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrack);

            AudioCopier audio = null;
            audioFormat = null;
            if (audioFile != null) {
                audioExtractor = new MediaExtractor();
                audioExtractor.setDataSource(audioFile.getAbsolutePath());
                int audioTrack = MediaProbe.findTrack(audioExtractor, "audio/");
                if (audioTrack < 0) throw new IOException("Soundtrack has no audio");
                audioExtractor.selectTrack(audioTrack);
                audioFormat = audioExtractor.getTrackFormat(audioTrack);
                audio = new AudioCopier(audioExtractor);
            }

            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            int n = pieceStartUs.length;
            int first = 0;
            while (first < n) {
                throwIfCancelled();
                // One decode span: this piece and the following ones whose gap is short
                int last = first;
                while (last + 1 < n && pieceStartUs[last + 1] - pieceEndUs[last] < DECODE_THROUGH_GAP_US) last++;
                decodeSpan(videoExtractor, videoFormat, pieceStartUs[first], pieceEndUs[last], encoder, encInfo, audio);
                first = last + 1;
            }
            encoder.signalEndOfInputStream();
            drainEncoder(encoder, encInfo, true);
            if (!muxStarted) throw new IOException("No video frames rendered");
            if (audio != null) audio.pump(Long.MAX_VALUE);
            muxer.stop();
            muxStarted = false;
        } finally {
            final MediaCodec enc = encoder;
            if (enc != null) {
                quietly("encoder stop", enc::stop);
                quietly("encoder release", enc::release);
            }
            if (muxer != null) {
                final MediaMuxer mx = muxer;
                if (muxStarted) quietly("muxer stop", mx::stop);
                quietly("muxer release", mx::release);
                muxer = null;
                muxStarted = false;
            }
            if (videoExtractor != null) quietly("video extractor", videoExtractor::release);
            if (audioExtractor != null) quietly("audio extractor", audioExtractor::release);
            releaseGl();
            if (encoderInput != null) encoderInput.release();
        }
    }

    /**
     * Decodes source {@code [startUs, endUs)} from the sync frame before it with a fresh decoder, and draws the
     * frames the timeline keeps onto the encoder at their output times.
     */
    private void decodeSpan(MediaExtractor extractor, MediaFormat format, long startUs, long endUs,
                            MediaCodec encoder, MediaCodec.BufferInfo encInfo, AudioCopier audio)
            throws IOException {
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        MediaCodec decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        try {
            decoder.configure(format, decoderSurface, null, 0);
            decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long lastOutputMs = System.currentTimeMillis();
            while (!outputDone) {
                throwIfCancelled();
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        long t = extractor.getSampleTime();
                        ByteBuffer buf = decoder.getInputBuffer(inIndex);
                        int size = -1;
                        if (t >= 0 && (endUs == Long.MAX_VALUE || t < endUs + REORDER_MARGIN_US) && buf != null) {
                            size = extractor.readSampleData(buf, 0);
                        }
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, t, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIndex >= 0) {
                    lastOutputMs = System.currentTimeMillis();
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    long pts = info.presentationTimeUs;
                    // Frames before the span, inside a cut, or thinned out by the frame grid are only decoded
                    long outUs = info.size > 0 && pts >= startUs && pts < endUs ? outputUsOf(pts) : -1;
                    boolean draw = outUs >= 0 && acceptFrame(outUs);
                    decoder.releaseOutputBuffer(outIndex, draw);
                    if (draw) {
                        awaitFrame();
                        surfaceTexture.updateTexImage();
                        surfaceTexture.getTransformMatrix(stMatrix);
                        drawFrame(outUs);
                        drainEncoder(encoder, encInfo, false);
                        if (audio != null) audio.pump(outUs);
                        report((int) Math.min(99, outUs * 100 / Math.max(1, totalOutUs)));
                    }
                    if (eos) outputDone = true;
                } else if (System.currentTimeMillis() - lastOutputMs > STALL_MS) {
                    throw new IOException("Video decoder stalled");
                }
            }
        } finally {
            quietly("decoder stop", decoder::stop);
            quietly("decoder release", decoder::release);
        }
    }

    private void report(int percent) {
        if (progress != null && percent != lastPercent) {
            lastPercent = percent;
            progress.onProgress(percent);
        }
    }

    // ---------------------------------------------------------------- audio

    /**
     * Copies the AAC samples of the built soundtrack (already on the output timeline) into the muxer, driven
     * from the video loop so the two tracks interleave; nothing is written until the muxer has started.
     */
    private final class AudioCopier {
        private final MediaExtractor extractor;
        private ByteBuffer buffer;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        AudioCopier(MediaExtractor extractor) {
            this.extractor = extractor;
            int max = audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            buffer = ByteBuffer.allocate(Math.max(AUDIO_BUFFER_BYTES, max));
        }

        /** Writes every sample whose time is at most {@code limitUs}. */
        void pump(long limitUs) {
            if (!muxStarted) return;
            while (true) {
                throwIfCancelled();
                long t = extractor.getSampleTime();
                if (t < 0 || t > limitUs) return;
                long size = extractor.getSampleSize();
                if (size > buffer.capacity()) buffer = ByteBuffer.allocate((int) size);
                int read = extractor.readSampleData(buffer, 0);
                if (read < 0) return;
                info.set(0, read, t, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                muxer.writeSampleData(muxAudioTrack, buffer, info);
                extractor.advance();
            }
        }
    }

    // ---------------------------------------------------------------- muxing

    private void drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo info, boolean endOfStream)
            throws IOException {
        int idle = 0;
        while (true) {
            throwIfCancelled();
            int index = encoder.dequeueOutputBuffer(info, endOfStream ? TIMEOUT_US : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
                if (++idle > DRAIN_GIVE_UP_TRIES) throw new IOException("Encoder did not finish");
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The muxer can only start once every track is known
                muxVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                if (audioFormat != null) muxAudioTrack = muxer.addTrack(audioFormat);
                muxer.start();
                muxStarted = true;
            } else if (index >= 0) {
                idle = 0;
                ByteBuffer data = encoder.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && muxStarted && data != null) {
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    muxer.writeSampleData(muxVideoTrack, data, info);
                }
                encoder.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    // ---------------------------------------------------------------- verification

    /** Reads the written file back: both tracks must be about as long as the timeline. */
    private static void verify(File file, long expectedMs, boolean expectAudio) throws IOException {
        if (file.length() == 0) throw new IOException("Output is empty");
        long videoMs = -1;
        long audioMs = -1;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                long ms = MediaProbe.longOr(f, MediaFormat.KEY_DURATION, 0) / 1000;
                if (mime != null && mime.startsWith("video/")) videoMs = ms;
                else if (mime != null && mime.startsWith("audio/")) audioMs = ms;
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("Output cannot be read back", e);
        } finally {
            ex.release();
        }
        if (videoMs < 0) throw new IOException("Output has no video");
        if (Math.abs(videoMs - expectedMs) > LENGTH_TOLERANCE_MS) {
            throw new IOException("Video is " + videoMs + " ms, expected " + expectedMs);
        }
        if (expectAudio && (audioMs < 0 || Math.abs(audioMs - expectedMs) > LENGTH_TOLERANCE_MS)) {
            throw new IOException("Audio is " + audioMs + " ms, expected " + expectedMs);
        }
    }

    // ---------------------------------------------------------------- GL

    private void awaitFrame() throws IOException {
        synchronized (frameLock) {
            long deadline = System.currentTimeMillis() + FRAME_WAIT_MS;
            while (!frameAvailable) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) throw new IOException("Timed out waiting for a decoded frame");
                try {
                    frameLock.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
            }
            frameAvailable = false;
        }
    }

    private void setUpGl(Surface encoderInput) {
        gl = new GlContext(encoderInput);
        oesTexture = GlUtil.newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        frameAvailable = false;
        frameThread = new HandlerThread("AiEditFrames");
        frameThread.start();
        surfaceTexture = new SurfaceTexture(oesTexture);
        surfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (frameLock) {
                frameAvailable = true;
                frameLock.notifyAll();
            }
        }, new Handler(frameThread.getLooper()));
        decoderSurface = new Surface(surfaceTexture);
        compositor = new Compositor(outW, outH, crop, probe.width, probe.height);
        if (painter != null) layer = new LayerCanvas(outW, outH);
    }

    /** Draws the latched frame and the layers onto the encoder surface, stamped {@code outUs}. */
    private void drawFrame(long outUs) {
        long outMs = outUs / 1000;
        transform.reset();
        for (int i = 0; i < effects.size(); i++) effects.get(i).apply(outMs, transform);
        compositor.drawVideo(oesTexture, stMatrix, (float) transform.scale,
                (float) transform.centerX, (float) transform.centerY);
        if (layer != null && painter.draw(layer.begin(), outMs, outW, outH)) layer.drawOver();
        gl.setPresentationTime(outUs * 1000L);
        gl.swapBuffers();
    }

    private void releaseGl() {
        if (decoderSurface != null) decoderSurface.release();
        if (surfaceTexture != null) surfaceTexture.release();
        if (frameThread != null) frameThread.quitSafely();
        if (gl != null) {
            if (layer != null) layer.release();
            if (compositor != null) compositor.release();
            GlUtil.deleteTexture(oesTexture);
            gl.release();
        }
        decoderSurface = null;
        surfaceTexture = null;
        frameThread = null;
        layer = null;
        compositor = null;
        oesTexture = 0;
        gl = null;
    }
}
