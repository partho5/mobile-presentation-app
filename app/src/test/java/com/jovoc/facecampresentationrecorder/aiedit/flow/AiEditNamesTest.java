package com.jovoc.facecampresentationrecorder.aiedit.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AiEditNamesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void planLivesInThePlansDirNotBesideTheOutputs() throws IOException {
        File plans = AiEditNames.plansDir(tmp.newFolder("files"));
        File plan = AiEditNames.planFile(plans, "/sdcard/DCIM/Face Branding/clip_aiedit");
        assertEquals(plans, plan.getParentFile());
        assertTrue(plan.getName().endsWith(".plan.json"));
        assertFalse(plan.getPath().contains("DCIM"));
    }

    @Test
    public void sameNameInTwoFoldersGivesTwoPlans() throws IOException {
        File plans = AiEditNames.plansDir(tmp.newFolder("files"));
        assertNotEquals(AiEditNames.planFile(plans, "/a/clip_aiedit"), AiEditNames.planFile(plans, "/b/clip_aiedit"));
    }

    @Test
    public void nextBaseSkipsABaseWhosePlanExists() throws IOException {
        File plans = tmp.newFolder("plans");
        File dir = tmp.newFolder("dcim");
        File source = new File(dir, "clip.mp4");
        String first = AiEditNames.nextOutputBase(source, Collections.singletonList("9:16"), plans);
        assertEquals(new File(dir, "clip_aiedit").getPath(), first);
        assertTrue(AiEditNames.planFile(plans, first).createNewFile());
        String second = AiEditNames.nextOutputBase(source, Collections.singletonList("9:16"), plans);
        assertEquals(new File(dir, "clip_aiedit_2").getPath(), second);
    }
}
