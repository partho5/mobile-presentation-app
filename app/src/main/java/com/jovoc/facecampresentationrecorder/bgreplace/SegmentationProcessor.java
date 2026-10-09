package com.jovoc.facecampresentationrecorder.bgreplace;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous TFLite Selfie Segmentation Processor using GpuDelegate.
 * Non-blocking: receives 256x256 GPU buffer, runs inference on background ML thread,
 * and updates OpenGL mask texture reference without GC allocs or blocking GL rendering.
 */
public class SegmentationProcessor {
    private static final String TAG = "SegmentationProcessor";
    private static final String MODEL_FILE = "selfie_multiclass_256x256.tflite";
    // Output logits per class: 0 background, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 other
    private static final int NUM_CLASSES = 6;

    private static final int MODEL_WIDTH = 256;
    private static final int MODEL_HEIGHT = 256;

    private final Context context;
    private volatile Interpreter interpreter;
    private GpuDelegate gpuDelegate;

    private HandlerThread mlThread;
    private Handler mlHandler;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    // Set by the ML thread when maskBytes holds a fresh result not yet uploaded
    private volatile boolean newMaskReady = false;
    // True once at least one real mask has been uploaded; until then the
    // renderer shows the plain camera instead of an empty (all-background) mask
    private volatile boolean hasMask = false;

    private final ByteBuffer mlInputByteBuffer;
    private final ByteBuffer inputBuffer;
    private final float[][][][] outputArray;

    private int maskTextureId = 0;
    private int prevMaskTextureId = 0;
    private final byte[] maskBytes = new byte[MODEL_WIDTH * MODEL_HEIGHT];
    private final ByteBuffer maskByteBuffer = ByteBuffer.allocateDirect(MODEL_WIDTH * MODEL_HEIGHT);

    public SegmentationProcessor(Context context) {
        this.context = context.getApplicationContext();
        mlInputByteBuffer = ByteBuffer.allocateDirect(MODEL_WIDTH * MODEL_HEIGHT * 4);
        mlInputByteBuffer.order(ByteOrder.nativeOrder());

        inputBuffer = ByteBuffer.allocateDirect(MODEL_WIDTH * MODEL_HEIGHT * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());

        outputArray = new float[1][MODEL_HEIGHT][MODEL_WIDTH][NUM_CLASSES];
        maskByteBuffer.order(ByteOrder.nativeOrder());
    }

    public void init() {
        startMLThread();
        mlHandler.post(() -> {
            try {
                MappedByteBuffer modelBuffer = loadModelFile(context, MODEL_FILE);

                // Delegate failures surface from the Interpreter constructor, not from
                // new GpuDelegate(), so the whole creation has to be inside the try.
                try {
                    gpuDelegate = new GpuDelegate();
                    Interpreter.Options options = new Interpreter.Options();
                    options.addDelegate(gpuDelegate);
                    interpreter = new Interpreter(modelBuffer, options);
                    Log.d(TAG, "TFLite Interpreter created with GpuDelegate for " + MODEL_FILE);
                } catch (Throwable t) {
                    Log.w(TAG, "GpuDelegate failed, falling back to CPU", t);
                    if (gpuDelegate != null) {
                        gpuDelegate.close();
                        gpuDelegate = null;
                    }
                    // If CPU creation fails too, interpreter stays null and the renderer
                    // shows the plain camera.
                    Interpreter.Options options = new Interpreter.Options();
                    options.setNumThreads(4);
                    interpreter = new Interpreter(modelBuffer, options);
                    Log.d(TAG, "TFLite Interpreter created on CPU for " + MODEL_FILE);
                }
            } catch (Throwable e) {
                Log.e(TAG, "Error initializing TFLite SegmentationProcessor", e);
            }
        });
    }

    public void initMaskTextures() {
        int[] textures = new int[2];
        GLES30.glGenTextures(2, textures, 0);
        maskTextureId = textures[0];
        prevMaskTextureId = textures[1];

        for (int tex : textures) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, MODEL_WIDTH, MODEL_HEIGHT, 0,
                    GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
    }

    public int getMaskTextureId() {
        return maskTextureId;
    }

    public int getPrevMaskTextureId() {
        return prevMaskTextureId;
    }

    public void processLowResPixelsAsync(ByteBuffer lowResBuffer) {
        if (interpreter == null || lowResBuffer == null) return;

        if (isProcessing.compareAndSet(false, true)) {
            lowResBuffer.rewind();
            mlInputByteBuffer.rewind();
            mlInputByteBuffer.put(lowResBuffer);
            lowResBuffer.rewind();

            mlHandler.post(() -> {
                try {
                    mlInputByteBuffer.rewind();
                    inputBuffer.rewind();

                    int numPixels = MODEL_WIDTH * MODEL_HEIGHT;
                    for (int i = 0; i < numPixels; i++) {
                        int r = mlInputByteBuffer.get() & 0xFF;
                        int g = mlInputByteBuffer.get() & 0xFF;
                        int b = mlInputByteBuffer.get() & 0xFF;
                        mlInputByteBuffer.get(); // skip A

                        // Model metadata: mean 127.5, std 127.5 -> [-1, 1]
                        inputBuffer.putFloat((r - 127.5f) / 127.5f);
                        inputBuffer.putFloat((g - 127.5f) / 127.5f);
                        inputBuffer.putFloat((b - 127.5f) / 127.5f);
                    }

                    interpreter.run(inputBuffer, outputArray);

                    synchronized (maskBytes) {
                        for (int y = 0; y < MODEL_HEIGHT; y++) {
                            for (int x = 0; x < MODEL_WIDTH; x++) {
                                // Person = 1 - softmax(background)
                                float[] logits = outputArray[0][y][x];
                                float max = logits[0];
                                for (int c = 1; c < NUM_CLASSES; c++) max = Math.max(max, logits[c]);
                                float sum = 0f;
                                for (int c = 0; c < NUM_CLASSES; c++) sum += (float) Math.exp(logits[c] - max);
                                float val = 1f - (float) Math.exp(logits[0] - max) / sum;
                                int byteVal = (int) (Math.max(0.0f, Math.min(1.0f, val)) * 255.0f);
                                maskBytes[y * MODEL_WIDTH + x] = (byte) byteVal;
                            }
                        }
                        newMaskReady = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Inference error", e);
                } finally {
                    isProcessing.set(false);
                }
            });
        }
    }

    public boolean hasMask() {
        return hasMask;
    }

    public void updateMaskTextureOnGLThread() {
        // Only swap when inference produced a new mask; swapping every frame made
        // current/previous alternate between the same two stale masks.
        if (maskTextureId == 0 || !newMaskReady) return;

        synchronized (maskBytes) {
            maskByteBuffer.rewind();
            maskByteBuffer.put(maskBytes);
            maskByteBuffer.position(0);
            newMaskReady = false;
        }

        int temp = prevMaskTextureId;
        prevMaskTextureId = maskTextureId;
        maskTextureId = temp;

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, MODEL_WIDTH, MODEL_HEIGHT,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskByteBuffer);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        hasMask = true;
    }

    private void startMLThread() {
        if (mlThread == null) {
            mlThread = new HandlerThread("MLInferenceThread");
            mlThread.start();
            mlHandler = new Handler(mlThread.getLooper());
        }
    }

    private void stopMLThread() {
        if (mlThread != null) {
            mlThread.quitSafely();
            try {
                mlThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping ML thread", e);
            }
            mlThread = null;
            mlHandler = null;
        }
    }

    public void release() {
        stopMLThread();
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        if (maskTextureId != 0) {
            GLES30.glDeleteTextures(2, new int[]{maskTextureId, prevMaskTextureId}, 0);
            maskTextureId = 0;
            prevMaskTextureId = 0;
        }
    }

    private MappedByteBuffer loadModelFile(Context context, String modelName) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }
}
