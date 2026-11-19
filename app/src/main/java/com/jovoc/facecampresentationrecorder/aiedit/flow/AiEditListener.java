package com.jovoc.facecampresentationrecorder.aiedit.flow;

import java.io.File;

/**
 * Hears how an AI edit goes. {@link AiEditRunner} calls it on its worker thread; {@link AiEditService} hands it on
 * to the main thread before a screen sees it. A job is named by its recording's path and level.
 */
public interface AiEditListener {

    /** What the job is doing. */
    enum Stage {
        /** Sending the audio and proxy pieces and creating the job. */
        UPLOADING,
        /** The server has the job but has not started on it (status PENDING). */
        QUEUED,
        /** The server is turning the speech into text (status TRANSCRIBING). */
        TRANSCRIBING,
        /** The server is planning the edit (status PLANNING). */
        PLANNING,
        /** Rendering the outputs on the phone. */
        RENDERING
    }

    /**
     * @param ratio   the ratio being rendered ({@link Stage#RENDERING} only), else null
     * @param percent 0..100, or -1 when the stage has no measurable progress
     */
    void onProgress(String sourcePath, String level, Stage stage, String ratio, int percent);

    /** One output file is complete and registered in the recording's group; it can be shown now. */
    void onOutput(String sourcePath, String level, File output);

    /** Every output is done. */
    void onDone(String sourcePath, String level);

    /** The job stopped; {@code code} is an {@link AiEditException} code. A retryable one continues on "Try again". */
    void onFailed(String sourcePath, String level, String code, boolean retryable);
}
