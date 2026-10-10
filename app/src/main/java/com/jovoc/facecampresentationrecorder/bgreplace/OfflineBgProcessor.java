package com.jovoc.facecampresentationrecorder.bgreplace;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.jovoc.facecampresentationrecorder.BuildConfig;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Objects;

/**
 * Replaces the background of a recorded video after recording, where there is no per-frame
 * time budget and future frames are available.
 *
 * Works on a region of each frame: the whole frame for a face clip, or the face cam circle
 * inside a screen recording. One decode pass keeps a short window of frames: each frame's
 * region is segmented, and a frame is composited only once the frames after it are known,
 * so its mask can be averaged over past and future frames (no jitter, no lag). The mask is
 * upsampled with a joint bilateral filter guided by the full-resolution frame colour. Audio
 * is copied unchanged. Memory use does not grow with video length.
 *
 * Blocking: call from a worker thread.
 */
public class OfflineBgProcessor {

    public interface ProgressListener {
        /** percent is 0..100. */
        void onProgress(int percent);
    }

    /** Part of a frame whose background is replaced, in output pixels (top-left origin). */
    public static final class Region {
        final int left;
        final int top;
        final int width;
        final int height;
        /** Only the inscribed circle is replaced, minus {@code ringPx} at its edge. */
        final boolean circle;
        final int ringPx;

        public Region(int left, int top, int width, int height, boolean circle, int ringPx) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.circle = circle;
            this.ringPx = ringPx;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Region)) return false;
            Region r = (Region) o;
            return left == r.left && top == r.top && width == r.width && height == r.height
                    && circle == r.circle && ringPx == r.ringPx;
        }

        @Override
        public int hashCode() {
            return Objects.hash(left, top, width, height, circle, ringPx);
        }
    }

    public interface RegionProvider {
        /** Region for the frame at {@code ptsUs}, or null to leave that frame untouched. */
        Region regionAt(long ptsUs, int frameWidth, int frameHeight);
    }

    private static final String TAG = "OfflineBgProcessor";
    private static final String MODEL_FILE = "selfie_multiclass_256x256.tflite";
    private static final int MASK_SIZE = 256;
    // Output logits per class: 0 background, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 other
    private static final int NUM_CLASSES = 6;

    // Temporal smoothing window: +-3 frames, Gaussian sigma 1.5 frames (~50 ms at 30 fps)
    private static final int TEMPORAL_RADIUS = 3;
    private static final float TEMPORAL_SIGMA = 1.5f;
    private static final int WINDOW = TEMPORAL_RADIUS * 2 + 1;
    // Frames kept on the GPU: the one being composited plus the TEMPORAL_RADIUS after it
    private static final int FRAME_RING = TEMPORAL_RADIUS + 1;

    // The model runs on every Nth frame while the circle stays put; the frames in between get
    // the average of their neighbours' masks (offline, so both neighbours are known)
    private static final int INFERENCE_STRIDE = 2;

    private static final int OUTPUT_FRAME_RATE = 30;
    private static final long TIMEOUT_US = 10_000;

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

    private static final String COPY_FRAG_SHADER =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "in vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = texture(uTexture, vTexCoord);\n" +
            "}\n";

    // Joint bilateral upsampling of the coarse mask: neighbours whose camera colour is close
    // to this pixel's colour count more, so the alpha edge snaps to the real edge in the frame.
    // Outside the region (or outside the circle) the frame passes through unchanged.
    private static final String COMPOSITE_FRAG_SHADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec2 vTexCoord;\n" +
            "uniform sampler2D uCamera;\n" +
            "uniform sampler2D uMask;\n" +
            "uniform sampler2D uBackground;\n" +
            "uniform int uEnabled;\n" +
            "uniform vec4 uRegion;\n" +       // (u0, v0, u1, v1) of the region in frame uv
            "uniform int uCircle;\n" +
            "uniform float uInnerRadius;\n" + // in region-normalised units
            "uniform float uRegionPx;\n" +
            "uniform vec4 uBgTransform;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec3 c0 = texture(uCamera, vTexCoord).rgb;\n" +
            "    fragColor = vec4(c0, 1.0);\n" +
            "    if (uEnabled == 0) return;\n" +
            "    vec2 span = uRegion.zw - uRegion.xy;\n" +
            "    vec2 r = (vTexCoord - uRegion.xy) / span;\n" +
            "    if (r.x < 0.0 || r.y < 0.0 || r.x > 1.0 || r.y > 1.0) return;\n" +
            "    float edge = 1.0;\n" +
            "    if (uCircle == 1) {\n" +
            "        float d = length(r - 0.5);\n" +
            "        if (d > uInnerRadius) return;\n" +
            "        edge = clamp((uInnerRadius - d) * uRegionPx, 0.0, 1.0);\n" +
            "    }\n" +
            "    vec2 maskStep = vec2(0.5 / 256.0);\n" +
            "    float sum = 0.0;\n" +
            "    float wsum = 0.0;\n" +
            "    for (int y = -3; y <= 3; y++) {\n" +
            "        for (int x = -3; x <= 3; x++) {\n" +
            "            vec2 o = vec2(float(x), float(y)) * maskStep;\n" +
            "            vec3 d = texture(uCamera, vTexCoord + o * span).rgb - c0;\n" +
            "            float w = exp(-float(x * x + y * y) / 8.0) * exp(-dot(d, d) / 0.02);\n" +
            "            sum += texture(uMask, r + o).r * w;\n" +
            "            wsum += w;\n" +
            "        }\n" +
            "    }\n" +
            "    float alpha = smoothstep(0.35, 0.75, sum / max(wsum, 1e-4));\n" +
            "    alpha = mix(1.0, alpha, edge);\n" +
            "    vec2 bgUV = vec2(uBgTransform.z + r.x * uBgTransform.x,\n" +
            "                     uBgTransform.w + (1.0 - r.y) * uBgTransform.y);\n" +
            "    vec3 bg = texture(uBackground, bgUV).rgb;\n" +
            "    fragColor = vec4(mix(bg, c0, alpha), 1.0);\n" +
            "}\n";

    private static final float[] IDENTITY = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1,
    };

    // Quad vertices in triangle-strip order: bottom-left, bottom-right, top-left, top-right
    private static final float[] QUAD_POSITIONS = {-1, -1, 1, -1, -1, 1, 1, 1};
    private static final float[] TEX_FULL = {0, 0, 1, 0, 0, 1, 1, 1};

    private final Context context;

    private EGLCore eglCore;
    private EGLSurface pbufferSurface;
    private int oesTexture;
    private SurfaceTexture surfaceTexture;
    private Surface decoderSurface;
    private HandlerThread frameThread;
    private final Object frameLock = new Object();
    private boolean frameAvailable;

    private int oesProgram;
    private int copyProgram;
    private int compositeProgram;
    private FloatBuffer posBuffer;
    private FloatBuffer fullTexBuffer;
    private FloatBuffer cropTexBuffer;
    private final float[] stMatrix = new float[16];

    public OfflineBgProcessor(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Replaces the background of the whole frame (a face clip). */
    public void processFullFrame(File input, File output, Bitmap background,
                                 ProgressListener listener) throws IOException {
        process(input, output, background, 8_000_000,
                (ptsUs, w, h) -> new Region(0, 0, w, h, false, 0), listener);
    }

    /** Replaces the background inside the face cam circle of a screen recording. */
    public void processBubble(File input, File output, Bitmap background,
                              List<BubbleTrack.Entry> track, ProgressListener listener)
            throws IOException {
        // Slightly above the 8 Mbps screen recording so the single re-encode loses nothing visible
        process(input, output, background, 12_000_000, (ptsUs, w, h) -> {
            BubbleTrack.Entry e = BubbleTrack.at(track, ptsUs / 1000);
            if (e == null || !e.visible || e.size <= 0) return null;
            return new Region(e.left, e.top, e.size, e.size, true, e.ringPx);
        }, listener);
    }

    public void process(File input, File output, Bitmap background, int bitRate,
                        RegionProvider regions, ProgressListener listener) throws IOException {
        MediaExtractor probe = new MediaExtractor();
        probe.setDataSource(input.getAbsolutePath());
        int videoTrack = findTrack(probe, "video/");
        if (videoTrack < 0) {
            probe.release();
            throw new IOException("No video track in " + input);
        }
        MediaFormat videoFormat = probe.getTrackFormat(videoTrack);
        probe.release();

        int srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        int srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int rotation = videoFormat.containsKey(MediaFormat.KEY_ROTATION)
                ? videoFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
        // A decoder rendering to a Surface applies KEY_ROTATION itself (it is part of the
        // SurfaceTexture transform matrix), so frames arrive upright; only the output size
        // has to be swapped. Rotating again here cancels it out and squeezes the frame.
        int outW = (rotation % 180 == 0) ? srcW : srcH;
        int outH = (rotation % 180 == 0) ? srcH : srcW;
        Log.d(TAG, "Input " + srcW + "x" + srcH + " rot " + rotation + " -> " + outW + "x" + outH);

        try {
            setUpGl();
            run(input, output, videoTrack, background, bitRate, regions, outW, outH, listener);
        } finally {
            releaseGl();
        }
    }

    // ---------------------------------------------------------------- pipeline

    /** Per-frame state kept in the sliding window. */
    private static final class Slot {
        long ptsUs;
        Region region;
        boolean hasMask;
        /** Inference was skipped; the mask is interpolated once the next frame is segmented. */
        boolean skipped;
        final byte[] mask = new byte[MASK_SIZE * MASK_SIZE];
    }

    private void run(File input, File output, int videoTrack, Bitmap background, int bitRate,
                     RegionProvider regions, int outW, int outH, ProgressListener listener)
            throws IOException {
        Stats stats = new Stats();
        long initStart = SystemClock.elapsedRealtime();
        Segmenter segmenter = new Segmenter(context, loadModel());
        stats.initMs = SystemClock.elapsedRealtime() - initStart;

        MediaFormat encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
        encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FRAME_RATE);
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encoderInput = encoder.createInputSurface();
        encoder.start();
        EGLSurface encoderSurface = eglCore.createWindowSurface(encoderInput);
        eglCore.makeCurrent(encoderSurface);

        MediaMuxer muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaExtractor audioExtractor = new MediaExtractor();
        audioExtractor.setDataSource(input.getAbsolutePath());
        int audioTrack = findTrack(audioExtractor, "audio/");
        MuxerState mux = new MuxerState(muxer);
        if (audioTrack >= 0) {
            audioExtractor.selectTrack(audioTrack);
            mux.audioFormat = audioExtractor.getTrackFormat(audioTrack);
        }

        // GPU resources
        int[] frameFbos = new int[FRAME_RING];
        int[] frameTexs = new int[FRAME_RING];
        for (int i = 0; i < FRAME_RING; i++) {
            int[] fbo = new int[1];
            int[] tex = new int[1];
            createTargetFbo(outW, outH, fbo, tex);
            frameFbos[i] = fbo[0];
            frameTexs[i] = tex[0];
        }
        int[] cropFbo = new int[1];
        int[] cropTex = new int[1];
        createTargetFbo(MASK_SIZE, MASK_SIZE, cropFbo, cropTex);
        int maskTex = createTexture2D();
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, MASK_SIZE, MASK_SIZE, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null);
        int bgTex = createTexture2D();
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, background, 0);
        int bgW = background.getWidth();
        int bgH = background.getHeight();

        Slot[] window = new Slot[WINDOW];
        for (int i = 0; i < WINDOW; i++) window[i] = new Slot();
        float[] weights = new float[WINDOW];
        for (int k = -TEMPORAL_RADIUS; k <= TEMPORAL_RADIUS; k++) {
            weights[k + TEMPORAL_RADIUS] =
                    (float) Math.exp(-(k * k) / (2f * TEMPORAL_SIGMA * TEMPORAL_SIGMA));
        }
        float[] acc = new float[MASK_SIZE * MASK_SIZE];
        ByteBuffer maskBuffer = ByteBuffer.allocateDirect(MASK_SIZE * MASK_SIZE);
        ByteBuffer pixels = ByteBuffer.allocateDirect(MASK_SIZE * MASK_SIZE * 4)
                .order(ByteOrder.nativeOrder());
        // Last segmented crop and its mask: a byte-identical crop (nothing moved) reuses the mask
        ByteBuffer lastPixels = ByteBuffer.allocateDirect(MASK_SIZE * MASK_SIZE * 4)
                .order(ByteOrder.nativeOrder());
        byte[] lastMask = new byte[MASK_SIZE * MASK_SIZE];
        Region[] lastRegion = {null};
        MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
        int[] frameCount = {0};

        // Composites frame k using the masks of frames k-R..k+R (those that exist)
        CompositeStep emit = (k, lastIndex) -> {
            long compositeStart = System.nanoTime();
            Slot slot = window[k % WINDOW];
            boolean enabled = slot.region != null && slot.hasMask;
            if (enabled) {
                java.util.Arrays.fill(acc, 0f);
                float wsum = 0f;
                for (int j = Math.max(0, k - TEMPORAL_RADIUS);
                     j <= Math.min(lastIndex, k + TEMPORAL_RADIUS); j++) {
                    Slot other = window[j % WINDOW];
                    // Only average masks of the same region: a moved circle would misalign
                    if (!other.hasMask || !slot.region.equals(other.region)) continue;
                    float w = weights[j - k + TEMPORAL_RADIUS];
                    byte[] m = other.mask;
                    for (int p = 0; p < acc.length; p++) acc[p] += (m[p] & 0xFF) * w;
                    wsum += w;
                }
                maskBuffer.rewind();
                for (float v : acc) maskBuffer.put((byte) Math.round(v / wsum));
                maskBuffer.rewind();
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex);
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, MASK_SIZE, MASK_SIZE,
                        GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskBuffer);
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, outW, outH);
            int p = compositeProgram;
            GLES30.glUseProgram(p);
            bindSampler(p, "uCamera", 0, frameTexs[k % FRAME_RING]);
            bindSampler(p, "uMask", 1, maskTex);
            bindSampler(p, "uBackground", 2, bgTex);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uEnabled"), enabled ? 1 : 0);
            if (enabled) {
                Region r = slot.region;
                float[] uv = regionUv(r, outW, outH);
                GLES30.glUniform4f(GLES30.glGetUniformLocation(p, "uRegion"), uv[0], uv[1], uv[2], uv[3]);
                GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uCircle"), r.circle ? 1 : 0);
                // Stop one pixel inside the ring so its anti-aliased inner edge stays intact
                float inner = 0.5f - (r.ringPx + 1f) / Math.max(1, r.width);
                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uInnerRadius"), inner);
                GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uRegionPx"), r.width);
                GLES30.glUniform4fv(GLES30.glGetUniformLocation(p, "uBgTransform"), 1,
                        centerCrop(bgW, bgH, r.width, r.height), 0);
            }
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p, "uSTMatrix"), 1, false, IDENTITY, 0);
            drawQuad(fullTexBuffer);

            eglCore.setPresentationTime(encoderSurface, slot.ptsUs * 1000L);
            eglCore.swapBuffers(encoderSurface);
            drainEncoder(encoder, encInfo, mux, false);
            stats.compositeNs += System.nanoTime() - compositeStart;
        };

        try {
            long durationUs = decodeFrames(input, videoTrack, stats, (i, ptsUs, totalUs) -> {
                long ringStart = System.nanoTime();
                // Keep the upright full-res frame: it is composited TEMPORAL_RADIUS frames later
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameFbos[i % FRAME_RING]);
                GLES30.glViewport(0, 0, outW, outH);
                GLES30.glUseProgram(oesProgram);
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(oesProgram, "uSTMatrix"),
                        1, false, stMatrix, 0);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
                GLES30.glUniform1i(GLES30.glGetUniformLocation(oesProgram, "uTexture"), 0);
                drawQuad(fullTexBuffer);
                stats.ringNs += System.nanoTime() - ringStart;

                Slot slot = window[i % WINDOW];
                slot.ptsUs = ptsUs;
                slot.region = regions.regionAt(ptsUs, outW, outH);
                slot.hasMask = false;
                slot.skipped = false;
                if (slot.region != null) {
                    Slot before = i > 0 ? window[(i - 1) % WINDOW] : null;
                    // Skip only while the circle stays put, so the neighbours' masks line up
                    boolean skip = INFERENCE_STRIDE > 1 && i % INFERENCE_STRIDE != 0
                            && before != null && before.hasMask && slot.region.equals(before.region);
                    if (skip) {
                        slot.skipped = true;
                        stats.skippedFrames++;
                    } else {
                        long cropStart = System.nanoTime();
                        // Crop the region to the model input size
                        float[] uv = regionUv(slot.region, outW, outH);
                        float[] crop = {uv[0], uv[1], uv[2], uv[1], uv[0], uv[3], uv[2], uv[3]};
                        for (int c = 0; c < crop.length; c++) cropTexBuffer.put(c, crop[c]);
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cropFbo[0]);
                        GLES30.glViewport(0, 0, MASK_SIZE, MASK_SIZE);
                        GLES30.glUseProgram(copyProgram);
                        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(copyProgram, "uSTMatrix"),
                                1, false, IDENTITY, 0);
                        bindSampler(copyProgram, "uTexture", 0, frameTexs[i % FRAME_RING]);
                        drawQuad(cropTexBuffer);
                        pixels.rewind();
                        GLES30.glReadPixels(0, 0, MASK_SIZE, MASK_SIZE,
                                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels);
                        stats.cropNs += System.nanoTime() - cropStart;

                        pixels.rewind();
                        lastPixels.rewind();
                        if (slot.region.equals(lastRegion[0]) && pixels.equals(lastPixels)) {
                            System.arraycopy(lastMask, 0, slot.mask, 0, lastMask.length);
                            stats.duplicateFrames++;
                        } else {
                            segmenter.segment(pixels, slot.mask);
                            System.arraycopy(slot.mask, 0, lastMask, 0, lastMask.length);
                            lastRegion[0] = slot.region;
                            // The crop just segmented becomes the reference for the next one
                            pixels.rewind();
                            lastPixels.rewind();
                            lastPixels.put(pixels);
                        }
                        slot.hasMask = true;
                        if (before != null && before.skipped) {
                            fillSkipped(before, i >= 2 ? window[(i - 2) % WINDOW] : null, slot);
                        }
                    }
                }
                if (BuildConfig.DEBUG && i % 30 == 0) logFrame(i, slot);

                if (i >= TEMPORAL_RADIUS) emit.composite(i - TEMPORAL_RADIUS, i);
                frameCount[0] = i + 1;
                if (listener != null && totalUs > 0) {
                    listener.onProgress((int) Math.min(99, ptsUs * 100 / totalUs));
                }
            });

            if (frameCount[0] == 0) throw new IOException("No video frames decoded");
            // Flush the last frames, whose future window is cut short by the end of the video
            int last = frameCount[0] - 1;
            Slot lastSlot = window[last % WINDOW];
            if (lastSlot.skipped && !lastSlot.hasMask) {
                fillSkipped(lastSlot, last >= 1 ? window[(last - 1) % WINDOW] : null, null);
            }
            for (int k = Math.max(0, last - TEMPORAL_RADIUS + 1); k <= last; k++) {
                emit.composite(k, last);
            }
            Log.d(TAG, "Processed " + frameCount[0] + " frames, duration " + durationUs / 1000 + " ms");
            stats.log(frameCount[0], segmenter);

            encoder.signalEndOfInputStream();
            drainEncoder(encoder, encInfo, mux, true);

            if (audioTrack >= 0 && mux.started) {
                copyAudio(audioExtractor, mux);
            }
            if (listener != null) listener.onProgress(100);
        } finally {
            segmenter.close();
            GLES30.glDeleteFramebuffers(FRAME_RING, frameFbos, 0);
            GLES30.glDeleteTextures(FRAME_RING, frameTexs, 0);
            GLES30.glDeleteFramebuffers(1, cropFbo, 0);
            GLES30.glDeleteTextures(3, new int[]{cropTex[0], maskTex, bgTex}, 0);
            eglCore.makeCurrent(pbufferSurface);
            eglCore.releaseSurface(encoderSurface);
            encoder.stop();
            encoder.release();
            encoderInput.release();
            audioExtractor.release();
            if (mux.started) muxer.stop();
            muxer.release();
        }
    }

    private static void logFrame(int i, Slot slot) {
        if (slot.region == null) {
            Log.d(TAG, "frame " + i + " pts " + slot.ptsUs + " no region");
            return;
        }
        long sum = 0;
        int person = 0;
        for (byte b : slot.mask) {
            sum += b & 0xFF;
            if ((b & 0xFF) > 128) person++;
        }
        Region r = slot.region;
        Log.d(TAG, "frame " + i + " pts " + slot.ptsUs + " region " + r.left + "," + r.top + " "
                + r.width + "x" + r.height + " ring " + r.ringPx + " mask mean " + sum / slot.mask.length
                + " person% " + person * 100 / slot.mask.length);
    }

    private interface CompositeStep {
        void composite(int frameIndex, int lastKnownIndex);
    }

    /** Region bounds as (u0, v0, u1, v1) in GL texture space (v up). */
    private static float[] regionUv(Region r, int frameW, int frameH) {
        return new float[]{
                (float) r.left / frameW,
                1f - (float) (r.top + r.height) / frameH,
                (float) (r.left + r.width) / frameW,
                1f - (float) r.top / frameH,
        };
    }

    // ---------------------------------------------------------------- segmentation

    /** Where the time goes, summed over the whole run; logged once at the end. */
    private static final class Stats {
        long initMs;
        long decodeNs;
        long ringNs;
        long cropNs;
        long compositeNs;
        int skippedFrames;
        int duplicateFrames;
        final long startMs = SystemClock.elapsedRealtime();

        void log(int frames, Segmenter seg) {
            int n = Math.max(1, frames);
            Log.d(TAG, "Timing: total " + (SystemClock.elapsedRealtime() - startMs) + " ms for "
                    + frames + " frames; gpu/model init " + initMs + " ms; per frame: decode "
                    + decodeNs / n / 1000 + " us, ring copy " + ringNs / n / 1000
                    + " us, crop+readPixels " + cropNs / n / 1000 + " us, input convert "
                    + seg.convertNs / n / 1000 + " us, inference " + seg.inferNs / n / 1000
                    + " us, post " + seg.postNs / n / 1000 + " us, composite+encode "
                    + compositeNs / n / 1000 + " us; interpolated " + skippedFrames
                    + ", duplicate " + duplicateFrames);
        }
    }

    /**
     * Fills the mask of a frame whose inference was skipped from the frames around it
     * ({@code before}/{@code after} may be null), using only masks of the same region.
     */
    private static void fillSkipped(Slot mid, Slot before, Slot after) {
        boolean b = before != null && before.hasMask && mid.region.equals(before.region);
        boolean a = after != null && after.hasMask && mid.region.equals(after.region);
        if (a && b) {
            for (int p = 0; p < mid.mask.length; p++) {
                mid.mask[p] = (byte) (((before.mask[p] & 0xFF) + (after.mask[p] & 0xFF) + 1) >> 1);
            }
        } else if (a) {
            System.arraycopy(after.mask, 0, mid.mask, 0, mid.mask.length);
        } else if (b) {
            System.arraycopy(before.mask, 0, mid.mask, 0, mid.mask.length);
        }
        mid.hasMask = a || b;
    }

    // ---------------------------------------------------------------- segmentation

    /** Runs the model on a 256x256 RGBA crop and writes the person probability per pixel. */
    private static final class Segmenter {
        // (v - 127.5) / 127.5 for every 8-bit value
        private static final float[] NORMALISE = new float[256];
        // exp(x) for x in [-12, 12) in steps of 1/32; Math.exp per pixel was the slowest CPU step
        private static final int EXP_STEPS = 32;
        private static final float EXP_MIN = -12f;
        private static final float[] EXP_TABLE = new float[24 * EXP_STEPS + 1];
        static {
            for (int v = 0; v < 256; v++) NORMALISE[v] = (v - 127.5f) / 127.5f;
            for (int i = 0; i < EXP_TABLE.length; i++) {
                EXP_TABLE[i] = (float) Math.exp(EXP_MIN + (i + 0.5f) / EXP_STEPS);
            }
        }

        private final Interpreter interpreter;
        private GpuDelegate gpuDelegate;
        private final ByteBuffer input = ByteBuffer.allocateDirect(MASK_SIZE * MASK_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder());
        private final FloatBuffer inputFloats = input.asFloatBuffer();
        // Direct buffer output: TFLite copies into a Java float[][][][] element by element,
        // which costs more than the inference itself
        private final ByteBuffer output = ByteBuffer.allocateDirect(MASK_SIZE * MASK_SIZE * NUM_CLASSES * 4)
                .order(ByteOrder.nativeOrder());
        private final FloatBuffer logits = output.asFloatBuffer();
        // Bulk copies in and out of the direct buffers; per-element buffer calls are slow
        private final byte[] rgbaBytes = new byte[MASK_SIZE * MASK_SIZE * 4];
        private final float[] inputArray = new float[MASK_SIZE * MASK_SIZE * 3];
        private final float[] logitArray = new float[MASK_SIZE * MASK_SIZE * NUM_CLASSES];

        long convertNs;
        long inferNs;
        long postNs;

        Segmenter(Context context, MappedByteBuffer model) {
            Interpreter created;
            try {
                // FP16 roughly halves GPU inference time; the mask is quantised to 8 bits anyway
                GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
                gpuOptions.setPrecisionLossAllowed(true);
                gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
                // Compiling the OpenCL kernels takes ~18 s on every start; cache them on disk
                File cacheDir = kernelCacheDir(context);
                boolean cacheable = cacheDir.isDirectory() || cacheDir.mkdirs();
                if (cacheable) {
                    gpuOptions.setSerializationParams(cacheDir.getAbsolutePath(),
                            kernelCacheToken(model.capacity()));
                }
                gpuDelegate = new GpuDelegate(gpuOptions);
                Interpreter.Options options = new Interpreter.Options();
                options.addDelegate(gpuDelegate);
                created = new Interpreter(model, options);
                // Kernels are on disk now: the next run skips the long warm-up
                if (cacheable) {
                    try {
                        kernelCacheMarker(context, model.capacity()).createNewFile();
                    } catch (IOException e) {
                        Log.w(TAG, "Could not mark the kernel cache as ready", e);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "GpuDelegate failed, using CPU", t);
                if (gpuDelegate != null) {
                    gpuDelegate.close();
                    gpuDelegate = null;
                }
                Interpreter.Options options = new Interpreter.Options();
                options.setNumThreads(4);
                created = new Interpreter(model, options);
            }
            interpreter = created;
        }

        /** {@code rgba} comes from glReadPixels (bottom-up); {@code mask} is written bottom-up too. */
        void segment(ByteBuffer rgba, byte[] mask) {
            long t0 = System.nanoTime();
            rgba.rewind();
            rgba.get(rgbaBytes);
            // The model expects top-down rows
            int o = 0;
            for (int y = MASK_SIZE - 1; y >= 0; y--) {
                int i = y * MASK_SIZE * 4;
                for (int x = 0; x < MASK_SIZE; x++, i += 4) {
                    inputArray[o++] = NORMALISE[rgbaBytes[i] & 0xFF];
                    inputArray[o++] = NORMALISE[rgbaBytes[i + 1] & 0xFF];
                    inputArray[o++] = NORMALISE[rgbaBytes[i + 2] & 0xFF];
                }
            }
            inputFloats.rewind();
            inputFloats.put(inputArray);
            input.rewind();
            output.rewind();
            long t1 = System.nanoTime();
            interpreter.run(input, output);
            long t2 = System.nanoTime();

            logits.rewind();
            logits.get(logitArray);
            // Back to bottom-up so the mask lines up with GL texture coordinates
            for (int y = 0; y < MASK_SIZE; y++) {
                int dstRow = (MASK_SIZE - 1 - y) * MASK_SIZE;
                int b = y * MASK_SIZE * NUM_CLASSES;
                for (int x = 0; x < MASK_SIZE; x++, b += NUM_CLASSES) {
                    mask[dstRow + x] = personByte(logitArray, b);
                }
            }
            convertNs += t1 - t0;
            inferNs += t2 - t1;
            postNs += System.nanoTime() - t2;
        }

        /**
         * Person = 1 - softmax(background) = S / (1 + S) with S = sum over classes 1..5 of
         * exp(l_c - l_0). Pixels far from the decision boundary skip the exponentials.
         */
        private static byte personByte(float[] l, int b) {
            float l0 = l[b];
            float max = Math.max(Math.max(l[b + 1], l[b + 2]),
                    Math.max(Math.max(l[b + 3], l[b + 4]), l[b + 5]));
            float d = max - l0;
            if (d > 8f) return (byte) 255;
            if (d < -8f) return 0;
            float s = 0f;
            for (int c = 1; c < NUM_CLASSES; c++) {
                float x = l[b + c] - l0;
                if (x > EXP_MIN) s += EXP_TABLE[(int) ((x - EXP_MIN) * EXP_STEPS)];
            }
            return (byte) (s / (1f + s) * 255f + 0.5f);
        }

        void close() {
            interpreter.close();
            if (gpuDelegate != null) gpuDelegate.close();
        }
    }

    // ---------------------------------------------------------------- muxing

    private static class MuxerState {
        final MediaMuxer muxer;
        MediaFormat audioFormat;
        int videoTrack = -1;
        int audioTrack = -1;
        boolean started;

        MuxerState(MediaMuxer muxer) {
            this.muxer = muxer;
        }
    }

    private static void drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo info,
                                     MuxerState mux, boolean endOfStream) {
        while (true) {
            int index = encoder.dequeueOutputBuffer(info, endOfStream ? TIMEOUT_US : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The muxer can only start once every track is known
                mux.videoTrack = mux.muxer.addTrack(encoder.getOutputFormat());
                if (mux.audioFormat != null) mux.audioTrack = mux.muxer.addTrack(mux.audioFormat);
                mux.muxer.start();
                mux.started = true;
            } else if (index >= 0) {
                ByteBuffer data = encoder.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && mux.started && data != null) {
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    mux.muxer.writeSampleData(mux.videoTrack, data, info);
                }
                encoder.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    private static void copyAudio(MediaExtractor extractor, MuxerState mux) {
        ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            info.set(0, size, extractor.getSampleTime(),
                    (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            mux.muxer.writeSampleData(mux.audioTrack, buffer, info);
            extractor.advance();
        }
    }

    // ---------------------------------------------------------------- decoding

    private interface FrameCallback {
        /** Called on the GL thread with the new frame latched into the OES texture. */
        void onFrame(int frameIndex, long ptsUs, long durationUs);
    }

    /** Decodes every frame into the OES texture; returns the track duration. */
    private long decodeFrames(File input, int videoTrack, Stats stats, FrameCallback callback)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(input.getAbsolutePath());
        extractor.selectTrack(videoTrack);
        MediaFormat format = extractor.getTrackFormat(videoTrack);
        long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                ? format.getLong(MediaFormat.KEY_DURATION) : 0;

        MediaCodec decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        decoder.configure(format, decoderSurface, null, 0);
        decoder.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        int frameIndex = 0;
        try {
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inIndex);
                        int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIndex >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean render = info.size > 0;
                    decoder.releaseOutputBuffer(outIndex, render);
                    if (render) {
                        long waitStart = System.nanoTime();
                        awaitFrame();
                        surfaceTexture.updateTexImage();
                        surfaceTexture.getTransformMatrix(stMatrix);
                        stats.decodeNs += System.nanoTime() - waitStart;
                        callback.onFrame(frameIndex++, info.presentationTimeUs, durationUs);
                    }
                    if (eos) outputDone = true;
                }
            }
        } finally {
            decoder.stop();
            decoder.release();
            extractor.release();
        }
        return durationUs;
    }

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

    // ---------------------------------------------------------------- GL plumbing

    private void setUpGl() {
        eglCore = new EGLCore();
        pbufferSurface = eglCore.createOffscreenSurface(1, 1);
        eglCore.makeCurrent(pbufferSurface);

        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        oesTexture = tex[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        frameThread = new HandlerThread("OfflineBgFrames");
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
        copyProgram = createProgram(VERTEX_SHADER, COPY_FRAG_SHADER);
        compositeProgram = createProgram(VERTEX_SHADER, COMPOSITE_FRAG_SHADER);
        posBuffer = floatBuffer(QUAD_POSITIONS);
        fullTexBuffer = floatBuffer(TEX_FULL);
        cropTexBuffer = floatBuffer(TEX_FULL);
    }

    private void drawQuad(FloatBuffer texCoords) {
        texCoords.position(0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, posBuffer);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texCoords);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(0);
        GLES30.glDisableVertexAttribArray(1);
    }

    private static void bindSampler(int program, String name, int unit, int texture) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit);
    }

    /** Scale/offset (sx, sy, ox, oy) that center-crops the background to the target aspect. */
    private static float[] centerCrop(int bgW, int bgH, int outW, int outH) {
        float bgAspect = (float) bgW / bgH;
        float outAspect = (float) outW / outH;
        float sx = 1f;
        float sy = 1f;
        if (bgAspect > outAspect) sx = outAspect / bgAspect;
        else sy = bgAspect / outAspect;
        return new float[]{sx, sy, (1f - sx) / 2f, (1f - sy) / 2f};
    }

    private static void createTargetFbo(int w, int h, int[] fbo, int[] tex) {
        tex[0] = createTexture2D();
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glGenFramebuffers(1, fbo, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, tex[0], 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    /** Generates and binds a linear, clamped 2D texture. */
    private static int createTexture2D() {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    private void releaseGl() {
        if (decoderSurface != null) decoderSurface.release();
        if (surfaceTexture != null) surfaceTexture.release();
        if (frameThread != null) frameThread.quitSafely();
        if (eglCore != null) {
            GLES30.glDeleteProgram(oesProgram);
            GLES30.glDeleteProgram(copyProgram);
            GLES30.glDeleteProgram(compositeProgram);
            GLES30.glDeleteTextures(1, new int[]{oesTexture}, 0);
            eglCore.releaseSurface(pbufferSurface);
            eglCore.release();
        }
        decoderSurface = null;
        surfaceTexture = null;
        frameThread = null;
        eglCore = null;
    }

    private static int findTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    /**
     * True when the compiled GPU kernels are not cached yet, so the first frame is preceded by a
     * ~25 s warm-up. The marker sits beside the cache and goes away with it (cache clear, new
     * app version or model).
     */
    public static boolean isWarmUpNeeded(Context context) {
        try (AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE)) {
            return !kernelCacheMarker(context, fd.getDeclaredLength()).exists();
        } catch (IOException e) {
            return true;
        }
    }

    private static File kernelCacheDir(Context context) {
        return new File(context.getCacheDir(), "gpu_kernels");
    }

    private static String kernelCacheToken(long modelBytes) {
        return MODEL_FILE + "_fp16_v" + BuildConfig.VERSION_CODE + "_" + modelBytes;
    }

    private static File kernelCacheMarker(Context context, long modelBytes) {
        return new File(kernelCacheDir(context), kernelCacheToken(modelBytes) + ".ready");
    }

    private MappedByteBuffer loadModel() throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE);
        try (FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel channel = in.getChannel();
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
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
