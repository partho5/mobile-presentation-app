package com.jovoc.facecampresentationrecorder.entitlements;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Which paid features were on at one moment. A recording takes one when it starts and keeps it
 * until it and its post-processing are done, so a plan that changes meanwhile (an upgrade, a
 * lapse, a token that runs out) never switches a feature on or off mid-recording.
 * {@link #allows} is what code that can run during or after a recording asks instead of
 * {@link Entitlements#has}: the running recording's snapshot, else the plan as it is now.
 */
public final class FeatureSnapshot {

    private static volatile FeatureSnapshot recording;

    private final Set<String> on;

    private FeatureSnapshot(Set<String> on) {
        this.on = Collections.unmodifiableSet(on);
    }

    /** The plan as it is now, for every feature the app knows. */
    public static FeatureSnapshot take() {
        Set<String> on = new HashSet<>();
        for (String feature : Feature.DEFAULT_LOWEST.keySet()) {
            if (Entitlements.has(feature)) on.add(feature);
        }
        return new FeatureSnapshot(on);
    }

    /** A snapshot with exactly these features on. */
    public static FeatureSnapshot of(String... features) {
        return new FeatureSnapshot(new HashSet<>(Arrays.asList(features)));
    }

    public boolean has(String feature) {
        return on.contains(feature);
    }

    /** Call when a recording starts; returns the snapshot it will keep until {@link #endRecording}. */
    public static FeatureSnapshot beginRecording() {
        return beginRecording(take());
    }

    static FeatureSnapshot beginRecording(FeatureSnapshot snapshot) {
        recording = snapshot;
        return snapshot;
    }

    /** Call once the recording has stopped and its post-processing job has been queued with its own copy. */
    public static void endRecording() {
        recording = null;
    }

    /** The running recording's snapshot while one runs, otherwise the plan as it is now. */
    public static boolean allows(String feature) {
        FeatureSnapshot s = recording;
        return s != null ? s.has(feature) : Entitlements.has(feature);
    }
}
