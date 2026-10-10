package com.jovoc.facecampresentationrecorder.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AudioInputSelectionTest {

    private static final int PHONE = AudioInputSelection.PHONE_MIC;

    @Test
    public void phoneMicWhenNoExternalIsConnected() {
        AudioInputSelection s = new AudioInputSelection();
        assertEquals(PHONE, s.resolve(new int[]{}));
        s.selectExternal(7);
        assertEquals(PHONE, s.resolve(new int[]{}));
    }

    @Test
    public void externalMicIsTheDefault() {
        assertEquals(7, new AudioInputSelection().resolve(new int[]{7, 9}));
    }

    @Test
    public void explicitChoicesAreHonoured() {
        AudioInputSelection s = new AudioInputSelection();
        s.selectExternal(9);
        assertEquals(9, s.resolve(new int[]{7, 9}));
        s.selectPhoneMic();
        assertEquals(PHONE, s.resolve(new int[]{7, 9}));
    }

    @Test
    public void unpluggedChoiceFallsBackToAnotherExternalMic() {
        AudioInputSelection s = new AudioInputSelection();
        s.selectExternal(9);
        assertEquals(7, s.resolve(new int[]{7}));
    }

    @Test
    public void newlyConnectedMicOverridesPhoneChoice() {
        AudioInputSelection s = new AudioInputSelection();
        s.selectPhoneMic();
        s.onExternalConnected();
        assertEquals(7, s.resolve(new int[]{7}));
    }
}
