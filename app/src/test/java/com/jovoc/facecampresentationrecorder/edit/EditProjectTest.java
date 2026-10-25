package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EditProjectTest {

    private static final long DUR = 10_000;

    private static EditProject.Range r(long s, long e) {
        return new EditProject.Range(s, e);
    }

    private static EditProject whole() {
        return EditProject.wholeFile("/m.mp4");
    }

    @Test
    public void emptyKeepMeansWholeFile() {
        EditProject p = whole();
        assertEquals(Collections.singletonList(r(0, DUR)), p.effectiveKeep(DUR));
        assertEquals(DUR, p.outputDurationMs(DUR));
        assertFalse(p.hasCuts(DUR));
        assertFalse(p.hasAudioChanges());
    }

    @Test
    public void deleteMiddleKeepsBothSides() {
        EditProject p = whole().withDeleted(2000, 5000, DUR);
        assertEquals(Arrays.asList(r(0, 2000), r(5000, DUR)), p.effectiveKeep(DUR));
        assertEquals(7000, p.outputDurationMs(DUR));
        assertTrue(p.hasCuts(DUR));
    }

    @Test
    public void deleteAtStartAndEndTrims() {
        EditProject p = whole().withDeleted(0, 1000, DUR).withDeleted(9000, DUR, DUR);
        assertEquals(Collections.singletonList(r(1000, 9000)), p.effectiveKeep(DUR));
    }

    @Test
    public void deleteSpanningSeveralRanges() {
        EditProject p = whole().withDeleted(1000, 2000, DUR).withDeleted(4000, 5000, DUR)
                .withDeleted(1500, 4500, DUR);
        assertEquals(Arrays.asList(r(0, 1000), r(5000, DUR)), p.effectiveKeep(DUR));
    }

    @Test
    public void deleteOfAlreadyCutSpanChangesNothing() {
        EditProject p = whole().withDeleted(2000, 3000, DUR);
        assertEquals(p, p.withDeleted(2200, 2800, DUR));
    }

    @Test(expected = IllegalArgumentException.class)
    public void deletingEverythingIsRefused() {
        whole().withDeleted(0, DUR, DUR);
    }

    @Test
    public void splitMakesTwoSegmentsButCutsNothing() {
        EditProject p = whole().withSplit(4000, DUR);
        assertEquals(Arrays.asList(r(0, 4000), r(4000, DUR)), p.effectiveKeep(DUR));
        assertFalse(p.hasCuts(DUR));
        assertEquals(Collections.singletonList(r(0, DUR)), p.renderRanges(DUR));
        assertEquals(DUR, p.outputDurationMs(DUR));
    }

    @Test
    public void splitOnABoundaryChangesNothing() {
        EditProject p = whole().withSplit(4000, DUR);
        assertEquals(p, p.withSplit(4000, DUR));
        assertEquals(p, p.withSplit(0, DUR));
        assertEquals(p, p.withSplit(DUR, DUR));
    }

    @Test
    public void deleteASegmentAfterSplit() {
        EditProject p = whole().withSplit(3000, DUR).withSplit(6000, DUR).withDeleted(3000, 6000, DUR);
        assertEquals(Arrays.asList(r(0, 3000), r(6000, DUR)), p.effectiveKeep(DUR));
        assertEquals(Arrays.asList(r(0, 3000), r(6000, DUR)), p.renderRanges(DUR));
    }

    @Test
    public void trimKeepsOnlyTheWindow() {
        EditProject p = whole().withDeleted(4000, 5000, DUR).withTrim(1000, 4500, DUR);
        assertEquals(Arrays.asList(r(1000, 4000)), p.effectiveKeep(DUR));
    }

    @Test(expected = IllegalArgumentException.class)
    public void trimToNothingIsRefused() {
        whole().withTrim(20_000, 30_000, DUR);
    }

    @Test
    public void outputToSourceSkipsCutParts() {
        EditProject p = whole().withDeleted(2000, 5000, DUR);
        assertEquals(0, p.outputToSource(0, DUR));
        assertEquals(1999, p.outputToSource(1999, DUR));
        assertEquals(5000, p.outputToSource(2000, DUR));
        assertEquals(9000, p.outputToSource(6000, DUR));
        assertEquals(DUR, p.outputToSource(7000, DUR));
        assertEquals(DUR, p.outputToSource(99_999, DUR));
        assertEquals(0, p.outputToSource(-5, DUR));
    }

    @Test
    public void sourceToOutputIsMinusOneInsideACut() {
        EditProject p = whole().withDeleted(2000, 5000, DUR);
        assertEquals(0, p.sourceToOutput(0, DUR));
        assertEquals(1999, p.sourceToOutput(1999, DUR));
        assertEquals(-1, p.sourceToOutput(2000, DUR));
        assertEquals(-1, p.sourceToOutput(4999, DUR));
        assertEquals(2000, p.sourceToOutput(5000, DUR));
        assertEquals(6999, p.sourceToOutput(9999, DUR));
        assertEquals(-1, p.sourceToOutput(DUR, DUR));
    }

    @Test
    public void mappingRoundTripsInsideKeptRanges() {
        EditProject p = whole().withDeleted(1000, 2000, DUR).withDeleted(6000, 7500, DUR);
        for (long src = 0; src < DUR; src += 137) {
            long out = p.sourceToOutput(src, DUR);
            if (out < 0) continue;
            assertEquals(src, p.outputToSource(out, DUR));
        }
    }

    @Test
    public void overlappingAndUnsortedKeepIsNormalised() {
        List<EditProject.Range> raw = Arrays.asList(r(5000, 8000), r(-100, 2000), r(1500, 3000),
                r(9000, 99_000), r(4000, 4000));
        EditProject p = new EditProject("/m.mp4", raw, null, Collections.<EditProject.AddedAudio>emptyList());
        assertEquals(Arrays.asList(r(0, 3000), r(5000, 8000), r(9000, DUR)), p.effectiveKeep(DUR));
        assertEquals(3000 + 3000 + 1000, p.outputDurationMs(DUR));
    }

    @Test
    public void renderRangesJoinsTouchingRanges() {
        List<EditProject.Range> raw = Arrays.asList(r(0, 1000), r(1000, 2000), r(3000, 4000));
        EditProject p = new EditProject("/m.mp4", raw, null, Collections.<EditProject.AddedAudio>emptyList());
        assertEquals(Arrays.asList(r(0, 2000), r(3000, 4000)), p.renderRanges(DUR));
    }

    @Test
    public void editsAreImmutableSnapshots() {
        EditProject a = whole();
        EditProject b = a.withDeleted(0, 1000, DUR);
        assertEquals(Collections.emptyList(), a.getKeep());
        assertNotEquals(a, b);
        assertEquals(b, whole().withDeleted(0, 1000, DUR));
        assertEquals(b.hashCode(), whole().withDeleted(0, 1000, DUR).hashCode());
    }

    @Test
    public void audioSettingsClampAndDetectChange() {
        assertTrue(EditProject.AudioSettings.DEFAULT.isUnchanged());
        assertEquals(2f, new EditProject.AudioSettings(5f, false, false).volume, 0f);
        assertEquals(0f, new EditProject.AudioSettings(-1f, false, false).volume, 0f);
        EditProject muted = whole().withOriginal(new EditProject.AudioSettings(1f, true, false));
        assertTrue(muted.hasAudioChanges());
        assertSame(EditProject.AudioSettings.DEFAULT, whole().getOriginal());
        EditProject added = whole().withAdded(Collections.singletonList(
                new EditProject.AddedAudio("content://a", 0, 1000, 0, 1f)));
        assertTrue(added.hasAudioChanges());
    }

    private static EditProject.AddedAudio track(String uri) {
        return new EditProject.AddedAudio(uri, 0, 0, 0, 1f);
    }

    @Test
    public void addAndRemoveTracks() {
        EditProject p = whole().withAddedTrack(track("a")).withAddedTrack(track("b"));
        assertEquals(2, p.getAdded().size());
        EditProject q = p.withoutTrack(0);
        assertEquals("b", q.getAdded().get(0).uri);
        assertSame(q, q.withoutTrack(5));
        assertEquals(2, p.getAdded().size());
    }

    @Test
    public void moveTrackIsClamped() {
        EditProject p = whole().withAddedTrack(track("a"));
        assertEquals(1500, p.withTrackMoved(0, 1500, DUR).getAdded().get(0).startOnTimelineMs);
        assertEquals(0, p.withTrackMoved(0, -50, DUR).getAdded().get(0).startOnTimelineMs);
        assertEquals(DUR, p.withTrackMoved(0, DUR * 2, DUR).getAdded().get(0).startOnTimelineMs);
    }

    @Test
    public void trimTrackKeepsMinimumAndStoresEndAsZero() {
        EditProject p = whole().withAddedTrack(track("a"));
        EditProject.AddedAudio t = p.withTrackTrim(0, 1000, 4000, 8000).getAdded().get(0);
        assertEquals(1000, t.sourceInMs);
        assertEquals(4000, t.sourceOutMs);
        assertEquals(3000, t.usedLengthMs(8000));
        // Out at the file's end means "to the end"
        t = p.withTrackTrim(0, 1000, 9000, 8000).getAdded().get(0);
        assertEquals(0, t.sourceOutMs);
        assertEquals(7000, t.usedLengthMs(8000));
        // Crossed handles still leave the minimum
        t = p.withTrackTrim(0, 5000, 5000, 8000).getAdded().get(0);
        assertEquals(EditProject.MIN_TRACK_MS, t.usedLengthMs(8000));
    }

    @Test
    public void trackVolumeIsClampedAndCountsAsAudioChange() {
        EditProject p = whole().withAddedTrack(track("a")).withTrackVolume(0, 3f);
        assertEquals(2f, p.getAdded().get(0).volume, 0f);
        assertTrue(p.hasAudioChanges());
        assertSame(p, p.withTrackVolume(4, 1f));
    }
}
