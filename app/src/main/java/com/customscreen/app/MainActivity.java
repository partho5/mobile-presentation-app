package com.customscreen.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * Main (and only) Activity.
 *
 * Screen: plain white, no UI elements.
 * Gesture: double-tap anywhere toggles immersive full-screen mode.
 *
 * Full-screen behaviour (Android 12+, WindowInsetsController):
 *   - HIDE: status bar + navigation bar disappear; swipe from edge shows them transiently.
 *   - SHOW: bars reappear in normal, persistent mode.
 */
public class MainActivity extends Activity {

    private GestureDetector gestureDetector;
    private boolean isFullScreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {

            /** Required: returning true lets the detector track the full gesture sequence. */
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            /** Called when a confirmed double-tap is detected. */
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                toggleFullScreen();
                return true;
            }
        });
    }

    /** Intercept at window level so the whole blank screen is always responsive. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    private void toggleFullScreen() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) return;

        if (isFullScreen) {
            // Restore normal mode - system bars come back and stay visible.
            controller.show(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
        } else {
            // Enter immersive mode - bars hidden; swipe edge to peek transiently.
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        isFullScreen = !isFullScreen;
    }
}