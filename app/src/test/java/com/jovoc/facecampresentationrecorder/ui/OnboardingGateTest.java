package com.jovoc.facecampresentationrecorder.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OnboardingGateTest {

    private static final long INSTALLED = 1_700_000_000_000L;

    @Test
    public void freshInstallSeesOnboarding() {
        assertTrue(OnboardingGate.shouldShow(false, INSTALLED, INSTALLED));
    }

    @Test
    public void shownOnlyOnce() {
        assertFalse(OnboardingGate.shouldShow(true, INSTALLED, INSTALLED));
    }

    @Test
    public void existingUserWhoUpdatedDoesNotSeeIt() {
        assertFalse(OnboardingGate.shouldShow(false, INSTALLED, INSTALLED + 1));
    }
}
