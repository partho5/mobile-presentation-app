package com.jovoc.facecampresentationrecorder.aiedit.engine.plan;

/**
 * The plan cannot be used. {@link #code} is our own code for the phone's error words; the message never
 * carries transcript, plan or brand text.
 */
public final class PlanParseException extends Exception {
    /** The plan's {@code contract_version} is not the one this build knows. */
    public static final String UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION";
    /** An item or the captions name an effect that is not in the registry. */
    public static final String UNKNOWN_EFFECT = "UNKNOWN_EFFECT";
    /** Anything else wrong with the shape or the values. */
    public static final String BAD_PLAN = "BAD_PLAN";

    public final String code;

    public PlanParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PlanParseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
