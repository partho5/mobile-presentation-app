package com.jovoc.facecampresentationrecorder.aiedit.engine;

import java.io.IOException;

/** A render that could not be made, with one of our own codes so the app can show its own words. */
public final class EngineException extends IOException {

    /** The free storage is too small for the result. */
    public static final String NO_SPACE = "NO_SPACE";
    /** The source cannot be opened or has no video. */
    public static final String SOURCE_UNREADABLE = "SOURCE_UNREADABLE";
    /** The plan leaves (almost) nothing of the source. */
    public static final String NOTHING_TO_RENDER = "NOTHING_TO_RENDER";
    /** Codecs, GL or the muxer failed, or the written file did not pass the checks. */
    public static final String RENDER_FAILED = "RENDER_FAILED";

    public final String code;

    public EngineException(String code, String message) {
        super(message);
        this.code = code;
    }

    public EngineException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
