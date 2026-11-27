package com.jovoc.facecampresentationrecorder.auth;

import java.util.function.LongSupplier;

/**
 * Pure state of the email-code sign-in screen: which step it is on, the email being verified, the
 * resend cooldown and the lockouts set by 429 answers. No Android types, so it is unit-tested; the
 * activity only draws what this says. Times come from a monotonic clock (elapsedRealtime) and are
 * saved as "milliseconds left" so a rotation keeps the countdowns.
 */
public final class SignInFlow {

    public enum Step { EMAIL, CODE }

    /** What went wrong, in terms the screen maps to a message. */
    public enum Problem {
        NONE,
        BAD_EMAIL,
        BAD_CODE,
        /** Wrong, expired or used: the backend does not say which. */
        WRONG_CODE,
        RATE_LIMITED,
        OFFLINE,
        MAIL_FAILED,
        SERVER
    }

    /** Where the user goes once the session exists. */
    public enum Destination {
        /** The screen that asked for sign-in wants to continue (e.g. into Publish). */
        NEXT,
        /** Nothing queued: just return to whatever was open before. */
        PREVIOUS_SCREEN
    }

    public static final long RESEND_COOLDOWN_MS = 30_000L;
    public static final int DEFAULT_LOCK_SECONDS = 60;
    /** How long an emailed code works (the code step's copy says 10 minutes). */
    public static final long CODE_VALID_MS = 10 * 60_000L;

    private final LongSupplier clock;
    private Step step = Step.EMAIL;
    private String email = "";
    private long sendUntil;
    private long verifyUntil;
    private long resendUntil;
    private long codeSentAt;
    private int lastRetryAfterSeconds;

    public SignInFlow(LongSupplier clock) {
        this.clock = clock;
    }

    // ---- reading state ----

    public Step step() {
        return step;
    }

    public String email() {
        return email;
    }

    public boolean canSend() {
        return clock.getAsLong() >= sendUntil;
    }

    public boolean canVerify() {
        return clock.getAsLong() >= verifyUntil;
    }

    public boolean canResend() {
        return clock.getAsLong() >= resendUntil;
    }

    /** Whole seconds until Resend is allowed (0 when it is). */
    public int resendSecondsLeft() {
        return (int) ((Math.max(0, resendUntil - clock.getAsLong()) + 999) / 1000);
    }

    /** Seconds the server asked the user to wait on the last RATE_LIMITED problem, or -1. */
    public int lastRetryAfterSeconds() {
        return lastRetryAfterSeconds;
    }

    /** True while any button is waiting on a deadline, so the screen keeps its ticker running. */
    public boolean hasPendingDeadline() {
        long now = clock.getAsLong();
        return now < sendUntil || now < verifyUntil || now < resendUntil;
    }

    // ---- steps ----

    /** Returns the normalized address to send to, or null when it is not a valid email. */
    public String emailToSend(String rawInput, boolean resend) {
        String target = resend ? email : AuthRepository.normalizeEmail(rawInput);
        return AuthRepository.isValidEmail(target) ? target : null;
    }

    public void onCodeSent(String sentTo) {
        email = sentTo;
        step = Step.CODE;
        codeSentAt = clock.getAsLong();
        resendUntil = codeSentAt + RESEND_COOLDOWN_MS;
    }

    /**
     * Back to the email step when the last code has expired, so a flow kept across screens (the in-place
     * sign-in sheet) never asks for a dead code. Returns true when it moved.
     */
    public boolean dropStaleCode() {
        if (step != Step.CODE || clock.getAsLong() - codeSentAt < CODE_VALID_MS) return false;
        step = Step.EMAIL;
        return true;
    }

    public void onChangeEmail() {
        step = Step.EMAIL;
    }

    public Problem onSendFailed(AuthException e, boolean resend) {
        Problem p = map(e, false);
        long lock = lockFor(e);
        if (lock > 0) {
            if (resend) resendUntil = lock;
            else sendUntil = lock;
        }
        return p;
    }

    public Problem onVerifyFailed(AuthException e) {
        Problem p = map(e, true);
        long lock = lockFor(e);
        if (lock > 0) verifyUntil = lock;
        return p;
    }

    /** Same mapping the screen shows for every call; {@code onCodeStep} only matters for BAD_INPUT. */
    public static Problem map(AuthException e, boolean onCodeStep) {
        switch (e.kind) {
            case NETWORK:
                return Problem.OFFLINE;
            case BAD_INPUT:
                return onCodeStep ? Problem.BAD_CODE : Problem.BAD_EMAIL;
            case INVALID_CODE:
                return Problem.WRONG_CODE;
            case RATE_LIMITED:
                return Problem.RATE_LIMITED;
            case MAIL_FAILED:
                return Problem.MAIL_FAILED;
            default:
                return Problem.SERVER;
        }
    }

    public static Destination afterSignIn(boolean hasQueuedNext) {
        return hasQueuedNext ? Destination.NEXT : Destination.PREVIOUS_SCREEN;
    }

    private long lockFor(AuthException e) {
        lastRetryAfterSeconds = -1;
        if (e.kind != AuthException.Kind.RATE_LIMITED) return 0;
        lastRetryAfterSeconds = e.retryAfterSeconds;
        int secs = e.retryAfterSeconds > 0 ? e.retryAfterSeconds : DEFAULT_LOCK_SECONDS;
        return clock.getAsLong() + secs * 1000L;
    }

    // ---- rotation ----

    /** [sendLeft, verifyLeft, resendLeft] in milliseconds. */
    public long[] remainingMillis() {
        long now = clock.getAsLong();
        return new long[]{
                Math.max(0, sendUntil - now), Math.max(0, verifyUntil - now), Math.max(0, resendUntil - now)};
    }

    public void restore(Step savedStep, String savedEmail, long[] left) {
        long now = clock.getAsLong();
        step = savedStep == null ? Step.EMAIL : savedStep;
        email = savedEmail == null ? "" : savedEmail;
        if (step == Step.CODE && !AuthRepository.isValidEmail(email)) step = Step.EMAIL;
        if (step == Step.CODE) codeSentAt = now; // not saved; counting from the restore keeps the step usable
        if (left != null && left.length == 3) {
            sendUntil = now + Math.max(0, left[0]);
            verifyUntil = now + Math.max(0, left[1]);
            resendUntil = now + Math.max(0, left[2]);
        }
    }
}
