package com.jovoc.facecampresentationrecorder.aiedit.flow;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.jovoc.facecampresentationrecorder.aiedit.flow.AiEditListener.Stage;

public class AiEditStatusStageTest {

    @Test
    public void serverStatusesMapToStages() {
        assertEquals(Stage.QUEUED, AiEditStatus.stageFor(AiEditStatus.PENDING, Stage.PLANNING));
        assertEquals(Stage.TRANSCRIBING, AiEditStatus.stageFor(AiEditStatus.TRANSCRIBING, Stage.QUEUED));
        assertEquals(Stage.PLANNING, AiEditStatus.stageFor(AiEditStatus.PLANNING, Stage.TRANSCRIBING));
    }

    @Test
    public void unknownOrFinalStatusKeepsTheLastStage() {
        assertEquals(Stage.TRANSCRIBING, AiEditStatus.stageFor("SOMETHING_NEW", Stage.TRANSCRIBING));
        assertEquals(Stage.PLANNING, AiEditStatus.stageFor("", Stage.PLANNING));
        assertEquals(Stage.PLANNING, AiEditStatus.stageFor(AiEditStatus.READY, Stage.PLANNING));
    }
}
