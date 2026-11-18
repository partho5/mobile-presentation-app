package com.jovoc.facecampresentationrecorder.aiedit.flow;

import com.jovoc.facecampresentationrecorder.aiedit.engine.EngineException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanParseException;
import com.jovoc.facecampresentationrecorder.auth.ApiResponse;
import com.jovoc.facecampresentationrecorder.publish.PublishException;

/**
 * Why an AI edit step failed, as one of our own codes so the screen can show its own words (never the server's
 * text, never anything of the recording). {@link #retryable} says whether the same call may work later without
 * any change; a job that failed for a retryable reason is simply continued by "Try again".
 */
public final class AiEditException extends Exception {

    /** 402: the workspace cannot afford the edit; nothing was held. Show the top-up. */
    public static final String INSUFFICIENT_CREDITS = "INSUFFICIENT_CREDITS";
    /** 402: the free plan's AI limit is reached; nothing was held. */
    public static final String AI_CAP_REACHED = "AI_CAP_REACHED";
    /** 400: longer than the server's limit, or more proxy pieces than it takes. */
    public static final String VIDEO_TOO_LONG = "VIDEO_TOO_LONG";
    /** The recording has no audio track, so there is nothing to plan from. */
    public static final String NO_AUDIO = "NO_AUDIO";
    /** The server heard too little speech; the credits were given back. */
    public static final String NO_SPEECH = "NO_SPEECH";
    /** The server could not make a plan; the credits were given back. */
    public static final String FAILED = "FAILED";
    /** The plan did not arrive within the wait limit; the job may still finish on the server. */
    public static final String TIMEOUT = "TIMEOUT";
    /** 503: the server has no AI edit set up. */
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    /** The caption of the recording is gone from the server; the next try sends the audio instead. */
    public static final String CAPTION_NOT_FOUND = "CAPTION_NOT_FOUND";
    /** The server no longer has the job; the next try makes a new one. */
    public static final String EDIT_NOT_FOUND = "EDIT_NOT_FOUND";
    /** The plan is of a newer contract than this app renders. */
    public static final String UNSUPPORTED_PLAN = "UNSUPPORTED_PLAN";
    /** The plan could not be read. */
    public static final String BAD_PLAN = "BAD_PLAN";
    public static final String NETWORK = "NETWORK";
    public static final String SERVER = "SERVER";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String SIGNED_OUT = "SIGNED_OUT";
    /** Any other refusal of the server; a retry would be refused the same way. */
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    /** The recording file is missing or cannot be read. */
    public static final String SOURCE_UNREADABLE = EngineException.SOURCE_UNREADABLE;
    /** The free storage is too small for the result. */
    public static final String NO_SPACE = EngineException.NO_SPACE;
    /** The plan leaves (almost) nothing of the recording. */
    public static final String NOTHING_TO_RENDER = EngineException.NOTHING_TO_RENDER;
    /** Codecs, GL or the muxer failed on this phone. */
    public static final String RENDER_FAILED = EngineException.RENDER_FAILED;

    public final String code;
    public final boolean retryable;

    public AiEditException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public AiEditException(String code, boolean retryable, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.retryable = retryable;
    }

    /** True for the two 402 answers, where the screen offers the top-up. */
    public boolean isCreditsProblem() {
        return INSUFFICIENT_CREDITS.equals(code) || AI_CAP_REACHED.equals(code);
    }

    /** The failure of a non-2xx answer of the {@code /ai-edits} routes. */
    static AiEditException from(ApiResponse r) {
        String c = r.errorCode;
        if (r.status == 402) {
            return new AiEditException(AI_CAP_REACHED.equals(c) ? AI_CAP_REACHED : INSUFFICIENT_CREDITS, false);
        }
        if (r.status == 401) return new AiEditException(SIGNED_OUT, false);
        if (r.status == 429) return new AiEditException(RATE_LIMITED, true);
        if (r.status == 503) return new AiEditException(NOT_CONFIGURED, true);
        if (r.status >= 500) return new AiEditException(SERVER, true);
        if (r.status == 400 && VIDEO_TOO_LONG.equals(c)) return new AiEditException(VIDEO_TOO_LONG, false);
        if (r.status == 404 && CAPTION_NOT_FOUND.equals(c)) return new AiEditException(CAPTION_NOT_FOUND, true);
        if (r.status == 404 && EDIT_NOT_FOUND.equals(c)) return new AiEditException(EDIT_NOT_FOUND, true);
        // An upload the server does not have (404) or has not finished (409) is sent again.
        if ((r.status == 404 && c != null && c.endsWith("_NOT_FOUND"))
                || (r.status == 409 && "UPLOAD_NOT_COMPLETE".equals(c))) {
            return new AiEditException(REJECTED, true);
        }
        return new AiEditException(REJECTED, false);
    }

    /** The failure of an upload step ({@link PublishException} of the direct upload). */
    static AiEditException from(PublishException e) {
        switch (e.kind) {
            case CANCELLED:
                return new AiEditException(CANCELLED, true, e);
            case SIGNED_OUT:
                return new AiEditException(SIGNED_OUT, false, e);
            case INSUFFICIENT_CREDITS:
                return new AiEditException(INSUFFICIENT_CREDITS, false, e);
            case FILE:
                return new AiEditException(SOURCE_UNREADABLE, false, e);
            case NETWORK:
                return new AiEditException(NETWORK, true, e);
            default:
                return new AiEditException(e.isRetryable() ? SERVER : REJECTED, e.isRetryable(), e);
        }
    }

    /** The failure of a render. */
    static AiEditException from(EngineException e) {
        boolean retryable = EngineException.RENDER_FAILED.equals(e.code) || EngineException.NO_SPACE.equals(e.code);
        return new AiEditException(e.code, retryable, e);
    }

    /** The failure of reading a plan. */
    static AiEditException from(PlanParseException e) {
        boolean newer = PlanParseException.UNSUPPORTED_VERSION.equals(e.code);
        return new AiEditException(newer ? UNSUPPORTED_PLAN : BAD_PLAN, false, e);
    }
}
