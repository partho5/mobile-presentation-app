package com.jovoc.facecampresentationrecorder.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

public class SignInFlowTest {

    private final AtomicLong now = new AtomicLong(10_000L);
    private final SignInFlow flow = new SignInFlow(now::get);

    @Test
    public void startsOnEmailStepWithNothingLocked() {
        assertEquals(SignInFlow.Step.EMAIL, flow.step());
        assertTrue(flow.canSend());
        assertTrue(flow.canVerify());
        assertTrue(flow.canResend());
        assertFalse(flow.hasPendingDeadline());
    }

    @Test
    public void emailIsTrimmedLowercasedAndValidated() {
        assertEquals("a@b.co", flow.emailToSend("  A@B.Co ", false));
        assertNull(flow.emailToSend("nope", false));
        assertNull(flow.emailToSend("", false));
    }

    @Test
    public void resendReusesTheEmailAlreadySentTo() {
        flow.onCodeSent("a@b.co");
        assertEquals("a@b.co", flow.emailToSend("ignored typed text", true));
    }

    @Test
    public void codeSentMovesToCodeStepAndStartsThirtySecondCooldown() {
        flow.onCodeSent("a@b.co");
        assertEquals(SignInFlow.Step.CODE, flow.step());
        assertEquals("a@b.co", flow.email());
        assertFalse(flow.canResend());
        assertEquals(30, flow.resendSecondsLeft());
        now.addAndGet(12_500);
        assertEquals(18, flow.resendSecondsLeft()); // rounds up
        now.addAndGet(17_500);
        assertTrue(flow.canResend());
        assertEquals(0, flow.resendSecondsLeft());
    }

    @Test
    public void changeEmailGoesBackButKeepsCooldown() {
        flow.onCodeSent("a@b.co");
        flow.onChangeEmail();
        assertEquals(SignInFlow.Step.EMAIL, flow.step());
        assertFalse(flow.canResend());
    }

    @Test
    public void errorMapping() {
        assertEquals(SignInFlow.Problem.OFFLINE, SignInFlow.map(new AuthException(AuthException.Kind.NETWORK), true));
        assertEquals(SignInFlow.Problem.BAD_EMAIL, SignInFlow.map(new AuthException(AuthException.Kind.BAD_INPUT), false));
        assertEquals(SignInFlow.Problem.BAD_CODE, SignInFlow.map(new AuthException(AuthException.Kind.BAD_INPUT), true));
        assertEquals(SignInFlow.Problem.WRONG_CODE, SignInFlow.map(new AuthException(AuthException.Kind.INVALID_CODE), true));
        assertEquals(SignInFlow.Problem.RATE_LIMITED, SignInFlow.map(new AuthException(AuthException.Kind.RATE_LIMITED), true));
        assertEquals(SignInFlow.Problem.MAIL_FAILED, SignInFlow.map(new AuthException(AuthException.Kind.MAIL_FAILED), false));
        assertEquals(SignInFlow.Problem.SERVER, SignInFlow.map(new AuthException(AuthException.Kind.SERVER), false));
        assertEquals(SignInFlow.Problem.SERVER, SignInFlow.map(new AuthException(AuthException.Kind.SESSION_ENDED), false));
    }

    @Test
    public void rateLimitedSendLocksSendForRetryAfter() {
        SignInFlow.Problem p = flow.onSendFailed(
                new AuthException(AuthException.Kind.RATE_LIMITED, 45, null), false);
        assertEquals(SignInFlow.Problem.RATE_LIMITED, p);
        assertFalse(flow.canSend());
        assertTrue(flow.canVerify());
        assertEquals(45, flow.lastRetryAfterSeconds());
        assertTrue(flow.hasPendingDeadline());
        now.addAndGet(45_000);
        assertTrue(flow.canSend());
        assertFalse(flow.hasPendingDeadline());
    }

    @Test
    public void rateLimitedWithoutRetryAfterLocksForDefaultSixtySeconds() {
        flow.onVerifyFailed(new AuthException(AuthException.Kind.RATE_LIMITED, -1, null));
        assertFalse(flow.canVerify());
        now.addAndGet(59_000);
        assertFalse(flow.canVerify());
        now.addAndGet(1_000);
        assertTrue(flow.canVerify());
    }

    @Test
    public void rateLimitedResendLocksResendNotSend() {
        flow.onCodeSent("a@b.co");
        now.addAndGet(30_000);
        flow.onSendFailed(new AuthException(AuthException.Kind.RATE_LIMITED, 120, null), true);
        assertFalse(flow.canResend());
        assertTrue(flow.canSend());
    }

    @Test
    public void wrongCodeAndOfflineDoNotLock() {
        assertEquals(SignInFlow.Problem.WRONG_CODE,
                flow.onVerifyFailed(new AuthException(AuthException.Kind.INVALID_CODE)));
        assertEquals(SignInFlow.Problem.OFFLINE,
                flow.onSendFailed(new AuthException(AuthException.Kind.NETWORK), false));
        assertTrue(flow.canVerify());
        assertTrue(flow.canSend());
        assertEquals(-1, flow.lastRetryAfterSeconds());
    }

    @Test
    public void rotationKeepsStepEmailAndCountdowns() {
        flow.onCodeSent("a@b.co");
        flow.onVerifyFailed(new AuthException(AuthException.Kind.RATE_LIMITED, 20, null));
        now.addAndGet(5_000);
        long[] left = flow.remainingMillis();

        // The "new activity" has a fresh flow and a clock that kept running meanwhile.
        AtomicLong later = new AtomicLong(777_000L);
        SignInFlow restored = new SignInFlow(later::get);
        restored.restore(flow.step(), flow.email(), left);

        assertEquals(SignInFlow.Step.CODE, restored.step());
        assertEquals("a@b.co", restored.email());
        assertEquals(25, restored.resendSecondsLeft());
        assertFalse(restored.canVerify());
        later.addAndGet(15_000);
        assertTrue(restored.canVerify());
        assertFalse(restored.canResend());
        later.addAndGet(10_000);
        assertTrue(restored.canResend());
    }

    @Test
    public void restoreWithBadDataFallsBackToEmailStep() {
        flow.restore(SignInFlow.Step.CODE, "garbage", null);
        assertEquals(SignInFlow.Step.EMAIL, flow.step());
        flow.restore(null, null, new long[]{1});
        assertEquals(SignInFlow.Step.EMAIL, flow.step());
        assertTrue(flow.canSend());
    }

    @Test
    public void afterSignInContinuesToQueuedScreenElseReturns() {
        assertEquals(SignInFlow.Destination.NEXT, SignInFlow.afterSignIn(true));
        assertEquals(SignInFlow.Destination.PREVIOUS_SCREEN, SignInFlow.afterSignIn(false));
    }
}
