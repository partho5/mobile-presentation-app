package com.jovoc.facecampresentationrecorder.bgreplace;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.Collections;

/**
 * Controller using direct Camera2 API (CameraDevice, CameraCaptureSession, TEMPLATE_RECORD)
 * to stream frames into an OpenGL OES SurfaceTexture.
 */
public class Camera2Controller {
    private static final String TAG = "Camera2Controller";

    private final Context context;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private int lensFacing = CameraCharacteristics.LENS_FACING_FRONT;
    private Size previewSize = new Size(1280, 720);

    public Camera2Controller(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setLensFacing(int lensFacing) {
        this.lensFacing = lensFacing;
    }

    public int getLensFacing() {
        return lensFacing;
    }

    public Size getPreviewSize() {
        return previewSize;
    }

    public void startCameraThread() {
        if (cameraThread == null) {
            cameraThread = new HandlerThread("Camera2HandlerThread");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    public void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted stopping camera thread", e);
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    @SuppressLint("MissingPermission")
    public void openCamera(Surface outputSurface, Runnable onOpenedCallback) {
        startCameraThread();
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String selectedCameraId = null;
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == lensFacing) {
                    selectedCameraId = cameraId;
                    break;
                }
            }

            if (selectedCameraId == null && manager.getCameraIdList().length > 0) {
                selectedCameraId = manager.getCameraIdList()[0];
            }

            if (selectedCameraId == null) {
                Log.e(TAG, "No camera found");
                return;
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(selectedCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] choices = map.getOutputSizes(SurfaceTexture.class);
                if (choices != null && choices.length > 0) {
                    // Pick 720p or fallback to closest to 1280x720
                    for (Size size : choices) {
                        if (size.getWidth() == 1280 && size.getHeight() == 720) {
                            previewSize = size;
                            break;
                        }
                    }
                }
            }

            final String finalCameraId = selectedCameraId;
            manager.openCamera(finalCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession(outputSurface, onOpenedCallback);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    Log.e(TAG, "Camera device error: " + error);
                }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening Camera2", e);
        }
    }

    private void createCaptureSession(Surface outputSurface, Runnable onOpenedCallback) {
        if (cameraDevice == null || outputSurface == null) return;
        try {
            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(outputSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            cameraDevice.createCaptureSession(Collections.singletonList(outputSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) return;
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(builder.build(), null, cameraHandler);
                        if (onOpenedCallback != null) {
                            onOpenedCallback.run();
                        }
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Error starting repeating request", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Failed to configure camera capture session");
                }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating capture session", e);
        }
    }

    public void closeCamera() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                captureSession.close();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping session", e);
            }
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        stopCameraThread();
    }
}
