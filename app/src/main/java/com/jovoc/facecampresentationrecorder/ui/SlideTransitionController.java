package com.jovoc.facecampresentationrecorder.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Fade-through slide transition: a black scrim fades in over the slide stage while the stage drifts a
 * few dp in the travel direction, the content is swapped while fully covered, and the scrim fades out
 * once the new content reports it is drawable. The scrim (not the slides' alpha) does the fading, so
 * it works identically for images, text, SurfaceView videos and WebViews.
 *
 * Only this class animates the scrim and the stage's translationX; input is never blocked, a new
 * request simply retargets whatever is in flight.
 */
public class SlideTransitionController {

    /** Swaps the stage content now; must call onReady once the new content is drawable (main thread). */
    public interface Binder {
        void bind(Runnable onReady);
    }

    private enum State { IDLE, OUT, WAITING, IN }

    private static final long OUT_MS = 180L;
    private static final long IN_MS = 320L;
    private static final long READY_TIMEOUT_MS = 700L;
    private static final float DRIFT_DP = 20f;

    private final View stage;
    private final View scrim;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float drift;
    private final Interpolator outInterpolator = new PathInterpolator(0.4f, 0f, 1f, 1f);
    private final Interpolator inInterpolator = new PathInterpolator(0f, 0f, 0.2f, 1f);

    private State state = State.IDLE;
    private Binder pendingBinder;
    private int pendingDir;
    // Incremented on every show(): a "ready" signal from an older bind must not release the scrim.
    private int token;
    // Incremented whenever an animation is (re)started or cancelled, so a stale end action is ignored.
    private int animGen;
    private Ready readyRunnable;

    public SlideTransitionController(View stage, View scrim) {
        this.stage = stage;
        this.scrim = scrim;
        this.drift = DRIFT_DP * stage.getResources().getDisplayMetrics().density;
    }

    /**
     * @param direction +1 next, -1 previous, 0 none (fade only)
     * @param animate   false rebinds instantly when idle (same slide re-rendered); ignored while a
     *                  transition is running, so the content still goes through the scrim
     */
    public void show(int direction, boolean animate, Binder binder) {
        if (!animate && state == State.IDLE) {
            binder.bind(() -> { });
            return;
        }
        pendingBinder = binder;
        pendingDir = direction;
        token++;
        switch (state) {
            case IDLE:
            case IN:
                startOut();
                break;
            case WAITING:
                swap(); // scrim is already opaque: bind the newest content straight away
                break;
            case OUT:
            default:
                break; // the end of OUT picks up the newest binder
        }
    }

    /** Stops everything and leaves the stage fully visible (onDestroy). */
    public void cancel() {
        token++;
        animGen++;
        handler.removeCallbacksAndMessages(null);
        readyRunnable = null;
        pendingBinder = null;
        stage.animate().cancel();
        scrim.animate().cancel();
        stage.setTranslationX(0f);
        scrim.setAlpha(0f);
        scrim.setVisibility(View.GONE);
        state = State.IDLE;
    }

    private void startOut() {
        final int gen = ++animGen;
        stage.animate().cancel();
        scrim.animate().cancel();
        scrim.setVisibility(View.VISIBLE);
        float alpha = scrim.getAlpha();
        long duration = Math.max(0L, Math.round(OUT_MS * (1f - alpha)));
        state = State.OUT;
        stage.animate()
                .translationX(-pendingDir * drift)
                .setDuration(duration)
                .setInterpolator(outInterpolator)
                .start();
        scrim.animate()
                .alpha(1f)
                .setDuration(duration)
                .setInterpolator(outInterpolator)
                .withEndAction(() -> {
                    if (gen == animGen && state == State.OUT) swap();
                })
                .start();
    }

    private void swap() {
        // WAITING first: ready may fire synchronously inside bind (cache hit, text).
        state = State.WAITING;
        stage.setTranslationX(0f);
        if (readyRunnable != null) handler.removeCallbacks(readyRunnable);
        Ready ready = new Ready(token);
        readyRunnable = ready;
        pendingBinder.bind(ready);
        if (!ready.done) handler.postDelayed(ready, READY_TIMEOUT_MS);
    }

    private void startIn() {
        final int gen = ++animGen;
        state = State.IN;
        stage.animate().cancel();
        scrim.animate().cancel();
        stage.setTranslationX(pendingDir * drift);
        stage.animate()
                .translationX(0f)
                .setDuration(IN_MS)
                .setInterpolator(inInterpolator)
                .start();
        scrim.animate()
                .alpha(0f)
                .setDuration(IN_MS)
                .setInterpolator(inInterpolator)
                .withEndAction(() -> {
                    if (gen != animGen || state != State.IN) return;
                    scrim.setVisibility(View.GONE);
                    stage.setTranslationX(0f);
                    state = State.IDLE;
                })
                .start();
    }

    /** Run-once "content is drawable" signal, bound to the show() it belongs to. */
    private final class Ready implements Runnable {
        private final int ownToken;
        boolean done;

        Ready(int ownToken) {
            this.ownToken = ownToken;
        }

        @Override
        public void run() {
            if (done) return;
            done = true;
            handler.removeCallbacks(this);
            if (ownToken != token || state != State.WAITING) return;
            startIn();
        }
    }
}
