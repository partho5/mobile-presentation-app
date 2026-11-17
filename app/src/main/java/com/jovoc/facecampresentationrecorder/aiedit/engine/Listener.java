package com.jovoc.facecampresentationrecorder.aiedit.engine;

/** Hears how a render goes. Called on the render thread; post to the main thread before touching UI. */
public interface Listener {

    /** {@code percent} is 0..100 for this job's file. */
    void onProgress(RenderJob job, int percent);

    /** The job's output file is complete and verified; it can be shown now while the next ratio renders. */
    default void onFinished(RenderJob job) {
    }
}
