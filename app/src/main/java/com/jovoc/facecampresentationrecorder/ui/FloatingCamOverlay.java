package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import com.jovoc.facecampresentationrecorder.R;

/**
 * Circular face cam bubble drawn over other apps while a recording is running.
 * Owned by ScreenRecordService so it outlives the activity. Has its own lifecycle,
 * so CameraX opens the camera while the activity is stopped and releases it on hide().
 */
public class FloatingCamOverlay implements LifecycleOwner {

    private static final String TAG = "FloatingCamOverlay";

    public interface Listener {
        void onStopClicked();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Listener listener;
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);

    private LinearLayout root;
    private WindowManager.LayoutParams layoutParams;
    private PreviewView previewView;
    private int lensFacing;

    public FloatingCamOverlay(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    public boolean isShowing() {
        return root != null;
    }

    public void show(int x, int y, int sizePx, int lensFacing) {
        if (root != null) return;
        this.lensFacing = lensFacing;
        float density = context.getResources().getDisplayMetrics().density;

        previewView = new PreviewView(context);
        // TextureView-backed so the circular outline clip applies
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        FrameLayout circle = new FrameLayout(context);
        circle.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        circle.setBackgroundColor(Color.BLACK);
        circle.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        circle.setClipToOutline(true);

        // White ring matching the in-app camera border, drawn above the preview
        View ring = new View(context);
        GradientDrawable ringDrawable = new GradientDrawable();
        ringDrawable.setShape(GradientDrawable.OVAL);
        ringDrawable.setColor(Color.TRANSPARENT);
        ringDrawable.setStroke((int) (2 * density), Color.WHITE);
        ring.setBackground(ringDrawable);
        circle.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int stopSize = (int) (36 * density);
        ImageView stop = new ImageView(context);
        stop.setImageResource(R.drawable.ic_stop);
        stop.setBackgroundResource(R.drawable.bg_circle_stop_minimal);
        stop.setPadding((int) (8 * density), (int) (8 * density), (int) (8 * density), (int) (8 * density));
        stop.setAlpha(0.6f);
        stop.setContentDescription("Stop recording");
        stop.setOnClickListener(v -> {
            if (listener != null) listener.onStopClicked();
        });

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(circle, new LinearLayout.LayoutParams(sizePx, sizePx));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(stopSize, stopSize);
        stopParams.gravity = Gravity.CENTER_HORIZONTAL;
        stopParams.topMargin = (int) (6 * density);
        root.addView(stop, stopParams);

        layoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = x;
        layoutParams.y = y;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        circle.setOnTouchListener(new DragListener());

        try {
            windowManager.addView(root, layoutParams);
        } catch (Exception e) {
            Log.e(TAG, "Could not add overlay window", e);
            root = null;
            return;
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        bindCamera();
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            if (root == null) return;
            try {
                ProcessCameraProvider provider = future.get();
                CameraSelector wanted = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
                CameraSelector other = new CameraSelector.Builder().requireLensFacing(
                        lensFacing == CameraSelector.LENS_FACING_BACK
                                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK).build();
                CameraSelector selector = provider.hasCamera(wanted) ? wanted : other;

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                provider.bindToLifecycle(this, selector, preview);
            } catch (Exception e) {
                Log.e(TAG, "Error binding overlay camera", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    public void hide() {
        if (root == null) return;
        // DESTROYED makes CameraX unbind the overlay's use cases
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        try {
            windowManager.removeView(root);
        } catch (Exception ignored) {}
        root = null;
        previewView = null;
    }

    private class DragListener implements View.OnTouchListener {
        private float downRawX, downRawY;
        private int startX, startY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (root == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = layoutParams.x;
                    startY = layoutParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    layoutParams.x = startX + Math.round(event.getRawX() - downRawX);
                    layoutParams.y = startY + Math.round(event.getRawY() - downRawY);
                    try {
                        windowManager.updateViewLayout(root, layoutParams);
                    } catch (Exception ignored) {}
                    return true;
                default:
                    return true;
            }
        }
    }
}
