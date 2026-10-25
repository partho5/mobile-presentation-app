package com.jovoc.facecampresentationrecorder.edit;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.jovoc.facecampresentationrecorder.bgreplace.EGLCore;
import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes the edited video: the kept stretches of the master, joined with no gaps.
 *
 * Video is decoded and encoded once (hardware H.264, decoder to an OES texture, drawn onto the
 * encoder's input surface, like {@code OfflineBgProcessor}) at about 1.5x the source bitrate, so
 * the generation loss is not visible. Audio is not re-encoded: with cuts only, the AAC samples
 * of the kept stretches are copied at their frame boundaries (~23 ms), no quality loss. When the
 * sound is changed (gain, mute, removal, added tracks) {@link AudioMixRenderer} decodes, mixes and
 * encodes the whole soundtrack to a temporary AAC file first and that is copied in instead. A
 * master without an audio track works: it is just silent, or gets only the added tracks. Added
 * tracks need {@link #withContext} when they are content URIs.
 * Times of added tracks are on the edited (result) timeline.
 *
 * Time base: ranges are in the master's own presentation timestamps and every output timestamp is
 * {@code offset of the range in the result + (source time - range start)}, for video and audio
 * alike, so the two stay in sync however many cuts there are.
 *
 * Background replace: it runs first and rewrites the whole master in place. It re-stamps every
 * frame with the source frame's own timestamp and copies the audio samples untouched, so the final
 * master has the recording's duration and timeline. Nothing here assumes that, though: the
 * duration is read from the file being edited and cuts are plain timestamps on it, so the
 * face-cam {@code BubbleTrack} timeline is never needed or remapped.
 *
 * Blocking: call from a worker thread. Setting the flag given to {@link #withCancelFlag} stops the
 * run at the next frame with a {@link CancellationException}. The output appears only when the
 * whole file is written and verified: it is built in a hidden ".part.mp4" beside it, then renamed.
 * A failed or cancelled run leaves no output and no part file.
 */
public class EditRenderer {

    public interface ProgressListener {
        /** percent is 0..100. */
        void onProgress(int percent);
    }

    /** The result would not fit in the free storage. */
    public static final class NotEnoughSpaceException extends IOException {
        public NotEnoughSpaceException(String message) {
            super(message);
        }
    }

    /** Even the lowest video quality would pass the upload size limit. */
    public static final class TooLongException extends IOException {
        public TooLongException(String message) {
            super(message);
        }
    }

    private static final String TAG = "EditRenderer";
    /** Suffix of the temporary mixed soundtrack that sits beside the ".part.mp4" file. */
    public static final String MIX_TEMP_SUFFIX = ".audio.m4a";
    /** Shortest result worth writing; the editor itself never goes below its segment minimum. */
    static final long MIN_RESULT_MS = 100;
    /** Names of the temp files of renders running right now, so a list scan does not sweep them. */
    private static final Set<String> LIVE_TEMPS = ConcurrentHashMap.newKeySet();

    /** True for the hidden temp files of a render that is running in this process. */
    public static boolean isLiveTemp(String fileName) {
        return fileName != null && LIVE_TEMPS.contains(fileName);
    }

    /** True for the mixed-soundtrack temp a crashed render may have left behind. */
    public static boolean isMixTemp(String fileName) {
        return fileName != null && fileName.startsWith(".")
                && fileName.endsWith(VideoCropHelper.PART_SUFFIX + MIX_TEMP_SUFFIX);
    }

    private static final long TIMEOUT_US = 10_000;
    private static final int DEFAULT_FRAME_RATE = 30;
    /** Decode this far past a range end: with B-frames, later samples can still be earlier in time. */
    private static final long REORDER_MARGIN_US = 300_000;
    private static final int AUDIO_BUFFER_BYTES = 256 * 1024;
    /** What the AAC track is assumed to cost when the file does not say. */
    private static final int AUDIO_FALLBACK_BPS = 192_000;
    /** Frames drained while waiting for the encoder to finish: ~5 s of no progress gives up. */
    private static final int DRAIN_GIVE_UP_TRIES = 500;

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "layout(location = 0) in vec4 aPosition;\n" +
            "layout(location = 1) in vec2 aTexCoord;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "out vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String OES_FRAG_SHADER =
            "#version 300 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision mediump float;\n" +
            "in vec2 vTexCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = texture(uTexture, vTexCoord);\n" +
            "}\n";

    // Quad vertices in triangle-strip order: bottom-left, bottom-right, top-left, top-right
    private static final float[] QUAD_POSITIONS = {-1, -1, 1, -1, -1, 1, 1, 1};
    private static final float[] TEX_FULL = {0, 0, 1, 0, 0, 1, 1, 1};

    /** A release step that may throw; see {@link #quietly}. */
    private interface Cleanup {
        void run() throws Exception;
    }

    private AtomicBoolean cancelFlag;
    private Context context;
    private ProgressListener listener;
    private int cropCopies;

    // GL / decoder output
    private EGLCore eglCore;
    private EGLSurface encoderSurface;
    private int oesTexture;
    private int oesProgram;
    private SurfaceTexture surfaceTexture;
    private Surface decoderSurface;
    private HandlerThread frameThread;
    private final Object frameLock = new Object();
    private boolean frameAvailable;
    private FloatBuffer posBuffer;
    private FloatBuffer texBuffer;
    private final float[] stMatrix = new float[16];

    // Muxing
    private MediaMuxer muxer;
    private MediaFormat audioFormat;
    private int muxVideoTrack = -1;
    private int muxAudioTrack = -1;
    private boolean muxStarted;
    private long firstVideoUs = -1;
    private long lastVideoUs = -1;
    private long firstAudioUs = -1;
    private long lastAudioUs = -1;

    private int outW;
    private int outH;
    private long totalOutUs;
    private int lastPercent = -1;

    /** Lets another thread stop {@link #render}: set the flag and the run unwinds cleanly. */
    public EditRenderer withCancelFlag(AtomicBoolean flag) {
        this.cancelFlag = flag;
        return this;
    }

    /** Needed to open added audio given as content URIs. */
    public EditRenderer withContext(Context context) {
        this.context = context;
        return this;
    }

    /** How many stream-copied crops will follow this render; their space is checked up front too. */
    public EditRenderer withCropCopies(int count) {
        this.cropCopies = Math.max(0, count);
        return this;
    }

    private boolean isCancelled() {
        return cancelFlag != null && cancelFlag.get();
    }

    private void throwIfCancelled() {
        if (isCancelled()) throw new CancellationException("Edit cancelled");
    }

    /** Runs one release step so a failure there cannot skip the steps after it. */
    private static void quietly(String what, Cleanup step) {
        try {
            step.run();
        } catch (Exception e) {
            Log.w(TAG, "Release failed: " + what, e);
        }
    }

    /**
     * Renders {@code project} to {@code output} (an ".mp4" path; replaced if it exists, never the
     * master itself). Throws {@link IOException} when the result cannot be made or fails the
     * checks, and {@link CancellationException} when cancelled; in both cases {@code output} is
     * left as it was.
     */
    public void render(EditProject project, File output, ProgressListener progress)
            throws IOException {
        File master = new File(project.getMasterPath());
        if (!master.isFile()) throw new IOException("Master not found: " + master);
        if (master.getAbsoluteFile().equals(output.getAbsoluteFile())) {
            throw new IOException("Output would overwrite the master");
        }
        this.listener = progress;
        lastPercent = -1;
        throwIfCancelled();

        // Probe the master
        MediaExtractor probe = new MediaExtractor();
        MediaFormat videoFormat;
        long videoDurationUs;
        long audioDurationUs = 0;
        int audioBps = 0;
        try {
            probe.setDataSource(master.getAbsolutePath());
            int v = findTrack(probe, "video/");
            if (v < 0) throw new IOException("No video track in " + master);
            videoFormat = probe.getTrackFormat(v);
            videoDurationUs = longOr(videoFormat, MediaFormat.KEY_DURATION, 0);
            int a = findTrack(probe, "audio/");
            if (a >= 0) {
                MediaFormat af = probe.getTrackFormat(a);
                audioDurationUs = longOr(af, MediaFormat.KEY_DURATION, 0);
                audioBps = (int) longOr(af, MediaFormat.KEY_BIT_RATE, AUDIO_FALLBACK_BPS);
            }
        } finally {
            probe.release();
        }
        long durationMs = Math.max(videoDurationUs, audioDurationUs) / 1000;
        if (durationMs <= 0) throw new IOException("Master has no duration");

        List<EditProject.Range> ranges = project.renderRanges(durationMs);
        if (ranges.isEmpty()) throw new IOException("Nothing to keep");
        long expectedMs = 0;
        for (EditProject.Range r : ranges) expectedMs += r.lengthMs();
        if (expectedMs < MIN_RESULT_MS) throw new IOException("Result too short");
        totalOutUs = expectedMs * 1000;

        // Bitrate: 1.5x the source, lowered so the result stays under the upload cap
        long sourceBps = longOr(videoFormat, MediaFormat.KEY_BIT_RATE, 0);
        if (sourceBps <= 0 && durationMs > 0) {
            // Not declared: estimate from the file, which also holds the audio
            sourceBps = Math.max(0, master.length() * 8 * 1000 / durationMs - audioBps);
        }
        boolean mixAudio = project.hasAudioChanges();
        boolean masterHasAudio = hasAudioTrack(master);
        // Removing the original with nothing added leaves no audio track; muting keeps a silent one
        boolean audioOut = mixAudio
                ? (masterHasAudio && !project.getOriginal().removed) || !project.getAdded().isEmpty()
                : masterHasAudio;
        int keptAudioBps = !audioOut ? 0 : mixAudio ? AudioMixRenderer.MAX_BPS : audioBps;
        int bitRate = BitratePlanner.videoBitrate(sourceBps, expectedMs, keptAudioBps);
        if (bitRate <= 0) {
            throw new TooLongException("Too long to fit the upload limit");
        }
        long neededBytes = BitratePlanner.requiredFreeBytes(bitRate, keptAudioBps, expectedMs,
                mixAudio && audioOut, cropCopies);
        File dir = output.getAbsoluteFile().getParentFile();
        if (dir == null || dir.getUsableSpace() < neededBytes) {
            throw new NotEnoughSpaceException("Not enough free space: need " + neededBytes + " bytes");
        }

        File part = new File(dir, VideoCropHelper.partFileNameFor(output.getAbsolutePath()));
        File mixed = new File(dir, part.getName() + MIX_TEMP_SUFFIX);
        LIVE_TEMPS.add(part.getName());
        LIVE_TEMPS.add(mixed.getName());
        part.delete();
        mixed.delete();
        boolean ok = false;
        try {
            if (mixAudio && audioOut) {
                new AudioMixRenderer(context, cancelFlag).render(project, master,
                        masterHasAudio && !project.getOriginal().removed, ranges, expectedMs, mixed);
            }
            writeFile(master, part, videoFormat, ranges, durationMs, bitRate, audioOut,
                    mixAudio && audioOut ? mixed : null, expectedMs);
            if (part.length() == 0) throw new IOException("Output is empty");
            verify(part, expectedMs, audioOut);
            if (!part.renameTo(output)) throw new IOException("Could not move " + part + " into place");
            ok = true;
        } finally {
            if (!ok) part.delete();
            mixed.delete();
            LIVE_TEMPS.remove(part.getName());
            LIVE_TEMPS.remove(mixed.getName());
        }
        report(100);
    }

    /** True when the master has an audio track (the one this run will copy). */
    private boolean hasAudioTrack(File master) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(master.getAbsolutePath());
            return findTrack(ex, "audio/") >= 0;
        } finally {
            ex.release();
        }
    }

    // ---------------------------------------------------------------- pipeline

    private void writeFile(File master, File part, MediaFormat videoFormat,
                           List<EditProject.Range> ranges, long durationMs, int bitRate,
                           boolean copyAudio, File mixedAudio, long expectedMs)
            throws IOException {
        int srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        int srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int rotation = videoFormat.containsKey(MediaFormat.KEY_ROTATION)
                ? videoFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
        // The decoder applies KEY_ROTATION itself when it renders to a Surface (it is part of the
        // SurfaceTexture transform), so frames arrive upright and only the size has to be swapped.
        outW = ((rotation % 180 == 0) ? srcW : srcH) & ~1;
        outH = ((rotation % 180 == 0) ? srcH : srcW) & ~1;
        int frameRate = videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                ? videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : DEFAULT_FRAME_RATE;
        if (frameRate <= 0) frameRate = DEFAULT_FRAME_RATE;
        Log.d(TAG, "Input " + srcW + "x" + srcH + " rot " + rotation + " -> " + outW + "x" + outH
                + ", " + bitRate + " bps, " + ranges.size() + " range(s)");

        MediaFormat encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
        encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

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
            firstVideoUs = lastVideoUs = firstAudioUs = lastAudioUs = -1;

            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(master.getAbsolutePath());
            int videoTrack = findTrack(videoExtractor, "video/");
            videoExtractor.selectTrack(videoTrack);

            AudioCopier audio = null;
            if (copyAudio) {
                audioExtractor = new MediaExtractor();
                // A mixed soundtrack is one range covering the whole result
                audioExtractor.setDataSource((mixedAudio != null ? mixedAudio : master).getAbsolutePath());
                int audioTrack = findTrack(audioExtractor, "audio/");
                audioExtractor.selectTrack(audioTrack);
                audioFormat = audioExtractor.getTrackFormat(audioTrack);
                audio = mixedAudio != null
                        ? new AudioCopier(audioExtractor, Collections.singletonList(
                                new EditProject.Range(0, expectedMs)), expectedMs)
                        : new AudioCopier(audioExtractor, ranges, durationMs);
            } else {
                audioFormat = null;
            }

            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            long offsetUs = 0;
            for (EditProject.Range r : ranges) {
                throwIfCancelled();
                long startUs = r.startMs * 1000;
                // The tail runs to the last frame whatever the rounded duration says
                long endUs = r.endMs >= durationMs ? Long.MAX_VALUE : r.endMs * 1000;
                decodeRange(videoExtractor, videoFormat, startUs, endUs, offsetUs, encoder,
                        encInfo, audio);
                offsetUs += r.lengthMs() * 1000;
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
            }
            if (videoExtractor != null) quietly("video extractor", videoExtractor::release);
            if (audioExtractor != null) quietly("audio extractor", audioExtractor::release);
            if (encoderInput != null) encoderInput.release();
            releaseGl();
        }
    }

    /**
     * Decodes {@code [startUs, endUs)} of the master from the sync frame before it, draws the
     * frames in range onto the encoder and stamps them at {@code offsetUs + (pts - startUs)}.
     */
    private void decodeRange(MediaExtractor extractor, MediaFormat format, long startUs,
                             long endUs, long offsetUs, MediaCodec encoder,
                             MediaCodec.BufferInfo encInfo, AudioCopier audio) throws IOException {
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        MediaCodec decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        try {
            decoder.configure(format, decoderSurface, null, 0);
            decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                throwIfCancelled();
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        long t = extractor.getSampleTime();
                        ByteBuffer buf = decoder.getInputBuffer(inIndex);
                        int size = -1;
                        if (t >= 0 && (endUs == Long.MAX_VALUE || t < endUs + REORDER_MARGIN_US)
                                && buf != null) {
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
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    long pts = info.presentationTimeUs;
                    // Frames before the range start were only decoded to build up to it
                    boolean inRange = info.size > 0 && pts >= startUs && pts < endUs;
                    decoder.releaseOutputBuffer(outIndex, inRange);
                    if (inRange) {
                        awaitFrame();
                        surfaceTexture.updateTexImage();
                        surfaceTexture.getTransformMatrix(stMatrix);
                        long outPts = offsetUs + (pts - startUs);
                        drawFrame(outPts);
                        drainEncoder(encoder, encInfo, false);
                        if (audio != null) audio.pump(outPts);
                        report((int) Math.min(99, outPts * 100 / Math.max(1, totalOutUs)));
                    }
                    if (eos) outputDone = true;
                }
            }
        } finally {
            quietly("decoder stop", decoder::stop);
            quietly("decoder release", decoder::release);
        }
    }

    private void report(int percent) {
        if (listener != null && percent != lastPercent) {
            lastPercent = percent;
            listener.onProgress(percent);
        }
    }

    // ---------------------------------------------------------------- audio

    /**
     * Copies the AAC samples that fall inside the kept ranges, re-stamped onto the result's
     * timeline. Driven from the video loop ({@link #pump}) so the two tracks interleave in the
     * file; nothing is consumed until the muxer has started.
     */
    private final class AudioCopier {
        private final MediaExtractor extractor;
        private final long[] startUs;
        private final long[] endUs;
        private final long[] offsetUs;
        private int index = -1;
        private boolean open;
        private ByteBuffer buffer;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        AudioCopier(MediaExtractor extractor, List<EditProject.Range> ranges, long durationMs) {
            this.extractor = extractor;
            int n = ranges.size();
            startUs = new long[n];
            endUs = new long[n];
            offsetUs = new long[n];
            long offset = 0;
            for (int i = 0; i < n; i++) {
                EditProject.Range r = ranges.get(i);
                startUs[i] = r.startMs * 1000;
                endUs[i] = r.endMs >= durationMs ? Long.MAX_VALUE : r.endMs * 1000;
                offsetUs[i] = offset;
                offset += r.lengthMs() * 1000;
            }
            int max = audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            buffer = ByteBuffer.allocate(Math.max(AUDIO_BUFFER_BYTES, max));
        }

        /** Writes every kept sample whose result time is at most {@code limitOutUs}. */
        void pump(long limitOutUs) {
            if (!muxStarted) return;
            while (true) {
                throwIfCancelled();
                if (!open) {
                    if (index + 1 >= startUs.length) return;
                    index++;
                    extractor.seekTo(startUs[index], MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    open = true;
                }
                long t = extractor.getSampleTime();
                if (t < 0 || t >= endUs[index]) {
                    open = false;
                    continue;
                }
                if (t < startUs[index]) {
                    extractor.advance();
                    continue;
                }
                long out = offsetUs[index] + (t - startUs[index]);
                if (out > limitOutUs) return;
                long size = extractor.getSampleSize();
                if (size > buffer.capacity()) buffer = ByteBuffer.allocate((int) size);
                int read = extractor.readSampleData(buffer, 0);
                if (read < 0) {
                    open = false;
                    continue;
                }
                info.set(0, read, out, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                muxer.writeSampleData(muxAudioTrack, buffer, info);
                if (firstAudioUs < 0) firstAudioUs = out;
                lastAudioUs = out;
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
                if (++idle > DRAIN_GIVE_UP_TRIES) {
                    throw new IOException("Encoder did not finish");
                }
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
                    if (firstVideoUs < 0) firstVideoUs = info.presentationTimeUs;
                    lastVideoUs = info.presentationTimeUs;
                }
                encoder.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    // ---------------------------------------------------------------- verification

    /** Reads the written file back and checks its length and A/V alignment. */
    private void verify(File file, long expectedMs, boolean expectAudio) throws IOException {
        long videoMs = -1;
        long audioMs = -1;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                long ms = longOr(f, MediaFormat.KEY_DURATION, 0) / 1000;
                if (mime != null && mime.startsWith("video/")) videoMs = ms;
                else if (mime != null && mime.startsWith("audio/")) audioMs = ms;
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("Output cannot be read back", e);
        } finally {
            ex.release();
        }
        String problem = EditVerify.check(expectedMs, videoMs, audioMs, expectAudio,
                firstVideoUs, firstAudioUs, lastVideoUs, lastAudioUs);
        if (problem != null) throw new IOException(problem);
    }

    // ---------------------------------------------------------------- decoding / GL

    private void awaitFrame() throws IOException {
        synchronized (frameLock) {
            long deadline = System.currentTimeMillis() + 2500;
            while (!frameAvailable) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) throw new IOException("Timed out waiting for decoded frame");
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
        eglCore = new EGLCore();
        encoderSurface = eglCore.createWindowSurface(encoderInput);
        eglCore.makeCurrent(encoderSurface);

        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        oesTexture = tex[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        frameAvailable = false;
        frameThread = new HandlerThread("EditFrames");
        frameThread.start();
        surfaceTexture = new SurfaceTexture(oesTexture);
        surfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (frameLock) {
                frameAvailable = true;
                frameLock.notifyAll();
            }
        }, new Handler(frameThread.getLooper()));
        decoderSurface = new Surface(surfaceTexture);

        oesProgram = createProgram(VERTEX_SHADER, OES_FRAG_SHADER);
        posBuffer = floatBuffer(QUAD_POSITIONS);
        texBuffer = floatBuffer(TEX_FULL);
    }

    /** Draws the latched decoder frame full-size onto the encoder surface at {@code outPtsUs}. */
    private void drawFrame(long outPtsUs) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, outW, outH);
        GLES30.glUseProgram(oesProgram);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(oesProgram, "uSTMatrix"),
                1, false, stMatrix, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(oesProgram, "uTexture"), 0);
        texBuffer.position(0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, posBuffer);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texBuffer);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(0);
        GLES30.glDisableVertexAttribArray(1);
        eglCore.setPresentationTime(encoderSurface, outPtsUs * 1000L);
        eglCore.swapBuffers(encoderSurface);
    }

    private void releaseGl() {
        if (decoderSurface != null) decoderSurface.release();
        if (surfaceTexture != null) surfaceTexture.release();
        if (frameThread != null) frameThread.quitSafely();
        if (eglCore != null) {
            GLES30.glDeleteProgram(oesProgram);
            GLES30.glDeleteTextures(1, new int[]{oesTexture}, 0);
            eglCore.releaseSurface(encoderSurface);
            eglCore.release();
        }
        decoderSurface = null;
        surfaceTexture = null;
        frameThread = null;
        eglCore = null;
        encoderSurface = null;
    }

    private static int findTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    /** A format value that may be an int or a long depending on the muxer that wrote it. */
    private static long longOr(MediaFormat format, String key, long fallback) {
        if (!format.containsKey(key)) return fallback;
        try {
            return format.getLong(key);
        } catch (ClassCastException e) {
            return format.getInteger(key);
        }
    }

    private static FloatBuffer floatBuffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vs);
        GLES30.glAttachShader(program, fs);
        GLES30.glLinkProgram(program);
        int[] status = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES30.GL_TRUE) {
            throw new IllegalStateException("Link failed: " + GLES30.glGetProgramInfoLog(program));
        }
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            throw new IllegalStateException("Compile failed: " + GLES30.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
