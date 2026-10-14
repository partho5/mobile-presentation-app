package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AutoPublishNotifierTest {

    @Test
    public void countsThePostIdsOfTheFinishedJob() {
        assertEquals(0, AutoPublishNotifier.countIds(null));
        assertEquals(0, AutoPublishNotifier.countIds(""));
        assertEquals(1, AutoPublishNotifier.countIds("p1"));
        assertEquals(3, AutoPublishNotifier.countIds("p1,p2, p3"));
    }
}
