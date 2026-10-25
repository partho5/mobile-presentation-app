package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EditorGateTest {

    @Test
    public void opensOnlyWhenSettingOnAndAutoPublishOff() {
        assertTrue(EditorGate.shouldShow(false, true));
    }

    @Test
    public void settingOffNeverOpens() {
        assertFalse(EditorGate.shouldShow(false, false));
    }

    @Test
    public void autoPublishOnSkipsTheEditorWhateverTheSetting() {
        assertFalse(EditorGate.shouldShow(true, true));
        assertFalse(EditorGate.shouldShow(true, false));
    }
}
