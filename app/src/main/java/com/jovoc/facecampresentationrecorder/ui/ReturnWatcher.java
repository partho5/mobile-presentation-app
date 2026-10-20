package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * Tells a sheet when its screen is back in front (after the browser or the sign-in screen) and
 * when the screen goes away, so the sheet never outlives its window. Main thread only.
 */
final class ReturnWatcher implements LifecycleEventObserver {

    private final Lifecycle lifecycle;
    private final Runnable onResumed;
    private final Runnable onDestroyed;
    private boolean attached;

    /** Nothing is watched until {@link #attach}. Hosts that are not lifecycle owners are simply not watched. */
    ReturnWatcher(Activity host, Runnable onResumed, Runnable onDestroyed) {
        this.lifecycle = host instanceof LifecycleOwner ? ((LifecycleOwner) host).getLifecycle() : null;
        this.onResumed = onResumed;
        this.onDestroyed = onDestroyed;
    }

    /** Starts watching. A screen that is already in front reports a resume at once. */
    void attach() {
        if (lifecycle == null || attached) return;
        attached = true;
        lifecycle.addObserver(this);
    }

    void detach() {
        if (lifecycle == null || !attached) return;
        attached = false;
        lifecycle.removeObserver(this);
    }

    /** True while the screen is in front of the user (no browser or other screen over it). */
    boolean isResumed() {
        return lifecycle == null || lifecycle.getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
    }

    @Override
    public void onStateChanged(@NonNull LifecycleOwner source, @NonNull Lifecycle.Event event) {
        if (event == Lifecycle.Event.ON_RESUME) {
            onResumed.run();
        } else if (event == Lifecycle.Event.ON_DESTROY) {
            detach();
            onDestroyed.run();
        }
    }
}
