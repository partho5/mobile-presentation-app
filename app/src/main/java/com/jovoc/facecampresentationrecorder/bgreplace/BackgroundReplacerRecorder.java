package com.jovoc.facecampresentationrecorder.bgreplace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main facade orchestrating EGLCore, Camera2Controller, GLRenderer,
 * SegmentationProcessor, and MaskRefiner for real-time camera background replacement.
 */
public class BackgroundReplacerRecorder implements TextureView.SurfaceTextureListener {
    private static final String TAG = "BackgroundReplacerRecorder";

    private final Context context;
    private EGLCore eglCore;
    private Camera2Controller camera2Controller;
    private GLRenderer glRenderer;
    private SegmentationProcessor segmentationProcessor;

    private HandlerThread renderThread;
    private Handler renderHandler;

    private Surface previewSurface;
    private EGLSurface eglPreviewSurface;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private boolean isBgReplaceEnabled = true;

    private int width = 1280;
    private int height = 720;

    private String currentBgAsset = FaceBgReplaceTask.DEFAULT_BG_ASSET;

    public BackgroundReplacerRecorder(Context context) {
        this.context = context.getApplicationContext();
        camera2Controller = new Camera2Controller(context);
        glRenderer = new GLRenderer();
        segmentationProcessor = new SegmentationProcessor(context);
    }

    public void initialize(TextureView textureView) {
        if (textureView.isAvailable()) {
            onSurfaceTextureAvailable(textureView.getSurfaceTexture(), textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(this);
        }
    }

    public void setEnabled(boolean enabled) {
        this.isBgReplaceEnabled = enabled;
        if (glRenderer != null) {
            glRenderer.setEnableBgReplace(enabled);
        }
    }

    public boolean isEnabled() {
        return isBgReplaceEnabled;
    }

    public void setBackgroundAsset(String assetPath) {
        this.currentBgAsset = assetPath;
        if (renderHandler != null) {
            renderHandler.post(() -> {
                if (glRenderer != null) {
                    glRenderer.loadDefaultBgFromAssets(context, assetPath);
                }
            });
        }
    }

    public void setBackgroundBitmap(Bitmap bitmap) {
        if (renderHandler != null && bitmap != null) {
            renderHandler.post(() -> {
                if (glRenderer != null) {
                    glRenderer.setUserBgBitmap(bitmap);
                }
            });
        }
    }

    public void switchCamera() {
        if (renderHandler != null) {
            renderHandler.post(() -> {
                camera2Controller.closeCamera();
                int currentFacing = camera2Controller.getLensFacing();
                int newFacing = (currentFacing == CameraCharacteristics.LENS_FACING_FRONT) ?
                        CameraCharacteristics.LENS_FACING_BACK : CameraCharacteristics.LENS_FACING_FRONT;
                camera2Controller.setLensFacing(newFacing);

                SurfaceTexture st = glRenderer.getCameraSurfaceTexture();
                Surface cameraSurface = new Surface(st);
                camera2Controller.openCamera(cameraSurface, null);
            });
        }
    }

    public void start() {
        if (isRunning.get()) return;
        isRunning.set(true);
    }

    public void stop() {
        if (!isRunning.get()) return;
        isRunning.set(false);
    }

    public void release() {
        stop();
        if (renderHandler != null) {
            renderHandler.post(() -> {
                camera2Controller.closeCamera();
                if (segmentationProcessor != null) {
                    segmentationProcessor.release();
                }
                if (glRenderer != null) {
                    glRenderer.release();
                }
                if (eglCore != null) {
                    if (eglPreviewSurface != null) {
                        eglCore.releaseSurface(eglPreviewSurface);
                    }
                    eglCore.release();
                }
            });
        }
        stopRenderThread();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        this.width = width > 0 ? width : 1280;
        this.height = height > 0 ? height : 720;
        this.previewSurface = new Surface(surface);

        startRenderThread();
        renderHandler.post(this::initGLPipeline);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        this.width = width;
        this.height = height;
        if (renderHandler != null) {
            renderHandler.post(() -> {
                if (glRenderer != null) {
                    glRenderer.setupFBOs(width, height);
                }
            });
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private void initGLPipeline() {
        try {
            eglCore = new EGLCore();
            eglPreviewSurface = eglCore.createWindowSurface(previewSurface);
            eglCore.makeCurrent(eglPreviewSurface);

            glRenderer.initGL();
            glRenderer.setupFBOs(width, height);
            glRenderer.loadDefaultBgFromAssets(context, currentBgAsset);
            glRenderer.setEnableBgReplace(isBgReplaceEnabled);

            segmentationProcessor.init();
            segmentationProcessor.initMaskTextures();

            SurfaceTexture st = glRenderer.getCameraSurfaceTexture();
            // Camera2 only accepts sizes it advertises; the view size (often square or
            // portrait) is not one of them. Camera2Controller targets 1280x720.
            Size camSize = camera2Controller.getPreviewSize();
            st.setDefaultBufferSize(camSize.getWidth(), camSize.getHeight());
            st.setOnFrameAvailableListener(surfaceTexture -> {
                if (renderHandler != null) {
                    renderHandler.post(this::renderFrame);
                }
            });

            Surface cameraSurface = new Surface(st);
            camera2Controller.openCamera(cameraSurface, () -> {
                Log.d(TAG, "Camera opened successfully for real-time background replacement");
                start();
            });

        } catch (Exception e) {
            Log.e(TAG, "Error initializing GL Pipeline", e);
        }
    }

    private final java.nio.ByteBuffer lowResReadBuffer = java.nio.ByteBuffer.allocateDirect(256 * 256 * 4);

    private void renderFrame() {
        if (!isRunning.get() || eglCore == null || eglPreviewSurface == null) return;
        try {
            eglCore.makeCurrent(eglPreviewSurface);

            int coarseMaskTex = segmentationProcessor.getMaskTextureId();
            int prevMaskTex = segmentationProcessor.getPrevMaskTextureId();

            glRenderer.drawFrame(width, height, coarseMaskTex, prevMaskTex,
                    segmentationProcessor.hasMask());

            if (isBgReplaceEnabled) {
                segmentationProcessor.updateMaskTextureOnGLThread();
                glRenderer.readLowResPixels(lowResReadBuffer);
                segmentationProcessor.processLowResPixelsAsync(lowResReadBuffer);
            }

            eglCore.swapBuffers(eglPreviewSurface);

        } catch (Exception e) {
            Log.e(TAG, "Error rendering frame", e);
        }
    }

    private void startRenderThread() {
        if (renderThread == null) {
            renderThread = new HandlerThread("GLRenderThread");
            renderThread.start();
            renderHandler = new Handler(renderThread.getLooper());
        }
    }

    private void stopRenderThread() {
        if (renderThread != null) {
            renderThread.quitSafely();
            try {
                renderThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping render thread", e);
            }
            renderThread = null;
            renderHandler = null;
        }
    }
}
