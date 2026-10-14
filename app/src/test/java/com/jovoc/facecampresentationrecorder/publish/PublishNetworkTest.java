package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.work.NetworkType;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** Wi-Fi-only: constraint choice, the "Waiting for Wi-Fi" decision and upload-work detection. */
public class PublishNetworkTest {

    private static final long NOW = 9_000_000L;

    private static PublishJob job(String mode, String status) {
        PublishJob j = new PublishJob();
        j.mode = mode;
        j.status = status;
        return j;
    }

    @Test
    public void constraintIsUnmeteredWithTheSettingOnAndConnectedOtherwise() {
        assertEquals(NetworkType.UNMETERED, PublishNetwork.requiredNetwork(true));
        assertEquals(NetworkType.CONNECTED, PublishNetwork.requiredNetwork(false));
    }

    @Test
    public void queuedJobOnMeteredNetworkWithSettingOnIsWaitingForWifi() {
        PublishJob manual = job(PublishJob.MODE_NOW, PublishJob.QUEUED);
        assertTrue(PublishNetwork.waitingForWifi(manual, true, false, NOW));
        // A retry after a network error is also blocked by Wi-Fi, not just "connection".
        manual.errorKind = "NETWORK";
        assertTrue(PublishNetwork.waitingForWifi(manual, true, false, NOW));
        assertTrue(PublishNetwork.waitingForWifi(job(PublishJob.MODE_AUTO, PublishJob.QUEUED), true, false, NOW));
    }

    @Test
    public void notWaitingWhenOnWifiOrSettingOffOrNotQueued() {
        PublishJob q = job(PublishJob.MODE_NOW, PublishJob.QUEUED);
        assertFalse(PublishNetwork.waitingForWifi(q, true, true, NOW));
        assertFalse(PublishNetwork.waitingForWifi(q, false, false, NOW));
        assertFalse(PublishNetwork.waitingForWifi(null, true, false, NOW));
        for (String s : new String[]{PublishJob.UPLOADING, PublishJob.PUBLISHING, PublishJob.DONE,
                PublishJob.FAILED, PublishJob.WAITING_PROCESSING}) {
            assertFalse(s, PublishNetwork.waitingForWifi(job(PublishJob.MODE_NOW, s), true, false, NOW));
        }
    }

    @Test
    public void insideTheUndoWindowTheCardKeepsItsCountdown() {
        PublishJob auto = job(PublishJob.MODE_AUTO, PublishJob.QUEUED);
        auto.scheduleAt = NOW + 20_000L;
        assertFalse(PublishNetwork.waitingForWifi(auto, true, false, NOW));
        // Once the window ends the same job is waiting for Wi-Fi.
        assertTrue(PublishNetwork.waitingForWifi(auto, true, false, NOW + 20_000L));
    }

    @Test
    public void scheduledManualPostDateIsNotAnUndoDelay() {
        PublishJob scheduled = job(PublishJob.MODE_SCHEDULE, PublishJob.QUEUED);
        scheduled.scheduleAt = NOW + 3_600_000L;
        assertTrue(PublishNetwork.waitingForWifi(scheduled, true, false, NOW));
    }

    @Test
    public void uploadWorkIsToldFromTheUndoGate() {
        String cls = "com.example.PublishWorker";
        assertTrue(PublishNetwork.isUploadWork(
                new HashSet<>(Arrays.asList(cls, "publish-upload")), "publish-upload", cls));
        // Enqueued before the tag existed: the worker class tag still identifies it.
        assertTrue(PublishNetwork.isUploadWork(
                new HashSet<>(Collections.singleton(cls)), "publish-upload", cls));
        // The gate carries only its own class name.
        assertFalse(PublishNetwork.isUploadWork(
                new HashSet<>(Collections.singleton("com.example.GateWorker")), "publish-upload", cls));
        assertFalse(PublishNetwork.isUploadWork(new HashSet<>(), "publish-upload", cls));
        assertFalse(PublishNetwork.isUploadWork(null, "publish-upload", cls));
    }
}
